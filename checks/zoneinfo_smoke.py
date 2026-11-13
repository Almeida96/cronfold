"""Verify the installed CLI against a UTC-minute oracle using Python's zoneinfo."""
import argparse
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
import json
import os
import subprocess
import tempfile
from zoneinfo import ZoneInfo, reset_tzpath


UTC = timezone.utc
MINUTE = timedelta(minutes=1)
# The fixtures cover historical rules shared by Java and the pinned tzdata wheel.
CASES = [
    ("America/New_York", "2024-03-10T04:00:00Z", "2024-03-11T04:00:00Z",
     ("2024-03-10T02:15", "2024-03-10T03:15"), 60),
    ("America/New_York", "2024-11-03T04:00:00Z", "2024-11-04T04:00:00Z", None, -60),
    ("Australia/Lord_Howe", "2024-10-05T13:00:00Z", "2024-10-06T13:00:00Z",
     ("2024-10-06T02:15", "2024-10-06T02:45"), 30),
    ("Australia/Lord_Howe", "2024-04-06T13:00:00Z", "2024-04-07T13:00:00Z", None, -30),
    ("Pacific/Apia", "2011-12-30T09:00:00Z", "2011-12-31T09:00:00Z",
     ("2011-12-30T09:15", "2011-12-31T09:15"), 1440),
]


def stamp(value):
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def offset_string(delta):
    minutes = int(delta.total_seconds() / 60)
    if minutes == 0:
        return "Z"
    hours, rest = divmod(abs(minutes), 60)
    return f"{'+' if minutes > 0 else '-'}{hours:02}:{rest:02}"


def verify_window(directory, launcher, name, start_text, end_text, missing_pair, delta_minutes):
    zone = ZoneInfo(name)
    start = datetime.fromisoformat(start_text.replace("Z", "+00:00"))
    end = datetime.fromisoformat(end_text.replace("Z", "+00:00"))
    # Enumerate UTC first. A skipped wall minute is invisible here; a repeated minute
    # appears twice with distinct offsets. Use fixed examples below for skipped labels.
    selected = {datetime.fromisoformat(x).strftime("%H:%M") for x in missing_pair} if missing_pair else {"01:45"}
    hours = sorted({int(label[:2]) for label in selected})
    minutes = sorted({int(label[3:]) for label in selected})
    by_local = defaultdict(list)
    current = start
    while current < end:
        wall = current.astimezone(zone)
        if wall.hour in hours and wall.minute in minutes:
            by_local[wall.replace(tzinfo=None).isoformat(timespec="minutes")].append(wall)
        current += MINUTE

    cron = f"{','.join(map(str, minutes))} {','.join(map(str, hours))} * * *"
    policies = [("skip", "both"), ("shift-forward", "both")] if missing_pair else [
        ("skip", "both"), ("skip", "earlier"), ("skip", "later")]
    jobs = [{"id": f"{gap}-{overlap}", "cron": cron, "zone": name, "gap": gap,
             "overlap": overlap, "durationMinutes": 1} for gap, overlap in policies]
    before = directory / "before.json"
    after = directory / "after.json"
    before.write_text(json.dumps({"version": 1, "jobs": []}), encoding="utf-8")
    after.write_text(json.dumps({"version": 1, "jobs": jobs}), encoding="utf-8")
    out = directory / "report"
    run = subprocess.run([str(launcher), "compare", "--before", str(before), "--after", str(after),
                          "--from", start_text, "--until", end_text, "--out", str(out)],
                         capture_output=True, text=True, timeout=75)
    if run.returncode:
        raise RuntimeError(f"CLI exited {run.returncode}\n{run.stdout}{run.stderr}")
    report = json.loads((out / "report.json").read_bytes())
    assert report["status"] == "CHANGE" and not report["conflicts"]
    details = {job["id"]: job for job in report["jobs"]}
    count = 0
    for job in jobs:
        expected = []
        expected_events = []
        for local, walls in by_local.items():
            repeated = len(walls) == 2
            emitted = []
            for index, wall in enumerate(walls):
                if repeated:
                    label = ("earlier", "later")[index]
                    if job["overlap"] not in ("both", label):
                        continue
                    resolution = "overlap-" + label
                else:
                    resolution = "normal"
                utc = stamp(wall.astimezone(UTC))
                expected.append((utc, local, offset_string(wall.utcoffset()), resolution))
                emitted.append(utc)
            if repeated:
                assert walls[1].utcoffset() - walls[0].utcoffset() == timedelta(minutes=delta_minutes)
                expected_events.append({"local": local, "type": "overlap", "emitted": emitted,
                                        "before": offset_string(walls[0].utcoffset()),
                                        "after": offset_string(walls[1].utcoffset())})
        if missing_pair:
            missing, shifted = missing_pair
            assert missing not in by_local
            # The fixture names the actual wall minute reached by shifting the nonexistent
            # time. Use its independently observed UTC instant for both coincident runs.
            target, = by_local[shifted]
            before_offset = start.astimezone(zone).utcoffset()
            assert target.utcoffset() - before_offset == timedelta(minutes=delta_minutes)
            utc = stamp(target.astimezone(UTC))
            emitted = []
            if job["gap"] == "shift-forward":
                expected.append((utc, missing, offset_string(target.utcoffset()), "gap-shifted"))
                emitted.append(utc)
            expected_events.append({"local": missing, "type": "gap", "emitted": emitted,
                                    "before": offset_string(before_offset), "after": offset_string(target.utcoffset())})
        assert expected_events, f"Fixture failed to exercise a transition in {name}"
        rows = [row for row in report["changes"] if row["job"] == job["id"]]
        assert all(row["kind"] == "added" for row in rows)
        actual = [(r["instant"], r["local"], r["offset"], r["resolution"]) for r in rows]
        if actual != sorted(expected):
            raise AssertionError(f"{name} {job['id']}: expected {sorted(expected)}, got {actual}")
        assert details[job["id"]]["afterEvents"] == sorted(expected_events, key=lambda event: event["local"])
        count += len(rows)
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", type=Path, required=True)
    args = parser.parse_args()
    launcher = args.install_dir.resolve() / "bin" / ("cronfold.bat" if os.name == "nt" else "cronfold")
    # Use the installed tzdata wheel on every OS, independent of the host's zone files.
    reset_tzpath([])
    count = 0
    with tempfile.TemporaryDirectory(prefix="cronfold-zoneinfo-") as scratch:
        for number, case in enumerate(CASES):
            directory = Path(scratch) / str(number)
            directory.mkdir()
            checked = verify_window(directory, launcher, *case)
            count += checked
            print(f"{case[0]} {case[1][:10]}: matched {checked} executions")
    print(f"Verified {count} executions and their transition evidence in {len(CASES)} windows.")


if __name__ == "__main__":
    main()
