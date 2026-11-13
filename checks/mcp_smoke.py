"""Read CronFold's report with the official MCP Python SDK and compare its pages to disk."""

import argparse
import asyncio
import json
import os
from pathlib import Path
import shutil

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def call(client, name, **arguments):
    result = await client.call_tool(name, arguments)
    assert not result.isError, result.content
    assert json.loads(result.content[0].text) == result.structuredContent
    return result.structuredContent


async def compare_pages(client, tool, key, expected, **filters):
    # Both ends exercise offset handling without scanning a large report one row at a time.
    offsets = sorted({0, max(0, len(expected) - 1), len(expected)})
    for offset in offsets:
        page = await call(client, tool, offset=offset, limit=1, **filters)
        assert page[key] == expected[offset:offset + 1]
        assert page["total"] == len(expected) and page["offset"] == offset
        assert page["nextOffset"] == (offset + 1 if offset + 1 < len(expected) else None)


async def check(install_dir, report_path):
    before = report_path.read_bytes()
    report = json.loads(before)
    lib = install_dir / "lib"
    if not list(lib.glob("*.jar")):
        raise ValueError(f"Missing distribution JARs in {lib}")
    java_home = os.environ.get("JAVA_HOME")
    executable = "java.exe" if os.name == "nt" else "java"
    java = str(Path(java_home) / "bin" / executable) if java_home else shutil.which(executable)
    if not java:
        raise RuntimeError("A Java 21 runtime is required")
    server = StdioServerParameters(command=java, args=[
        "-cp", str(lib / "*"), "io.github.almeida96.cronfold.MainKt", "mcp", "--report", str(report_path),
    ])
    async with stdio_client(server) as (reader, writer), ClientSession(reader, writer) as client:
        assert (await client.initialize()).serverInfo.name == "cronfold"
        await client.send_ping()
        tools = (await client.list_tools()).tools
        assert {tool.name for tool in tools} == {"schedule_summary", "job_detail", "list_changes", "list_conflicts"}
        assert all(tool.annotations.readOnlyHint and not tool.annotations.openWorldHint for tool in tools)
        summary = await call(client, "schedule_summary", limit=1)
        assert summary["status"] == report["status"] and summary["window"] == report["window"]
        assert summary["jobCount"] == len(report["jobs"])
        assert summary["changeCount"] == len(report["changes"])
        assert summary["conflictCount"] == len(report["conflicts"])
        keys = {"id", "beforeCount", "afterCount", "added", "removed", "unchanged", "scheduleChanged"}
        jobs = [{k: v for k, v in job.items() if k in keys} for job in report["jobs"]]
        await compare_pages(client, "schedule_summary", "jobs", jobs)
        if jobs:
            job = max(report["jobs"], key=lambda j: len(j["beforeEvents"]) + len(j["afterEvents"]))
            for side in ("before", "after"):
                await compare_pages(client, "job_detail", "events", job[side + "Events"], job=job["id"], side=side)
        await compare_pages(client, "list_changes", "changes", report["changes"])
        if report["changes"]:
            first = report["changes"][0]
            filters = {"job": first["job"], "kind": first["kind"]}
            rows = [r for r in report["changes"] if all(r[k] == v for k, v in filters.items())]
            await compare_pages(client, "list_changes", "changes", rows, **filters)
        await compare_pages(client, "list_conflicts", "conflicts", report["conflicts"])
        if report["conflicts"]:
            first = report["conflicts"][0]
            filters = {"resource": first["resource"], "classification": first["classification"]}
            rows = [r for r in report["conflicts"] if all(r[k] == v for k, v in filters.items())]
            await compare_pages(client, "list_conflicts", "conflicts", rows, **filters)
        assert (await client.call_tool("schedule_summary", {"path": "other.json"})).isError
    assert report_path.read_bytes() == before
    print(f"Read {len(jobs)} jobs, {len(report['changes'])} changes and {len(report['conflicts'])} conflicts ({report['status']})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    asyncio.run(asyncio.wait_for(check(args.install_dir.resolve(), args.report.resolve()), timeout=60))
