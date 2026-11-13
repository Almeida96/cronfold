package io.github.almeida96.cronfold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.PriorityQueue

/** A finite comparison of UTC executions and capacity-one resource reservations. */
object Diff {
    private const val maxProjectedItems = 300_000
    private const val maxEvidenceRows = 40_000
    private const val maxPairs = 40_000
    private val absent = Projection(emptyList(), emptyList())

    private class Budget {
        var projected = 0
        var evidence = 0
        fun addEvidence() {
            require(++evidence <= maxEvidenceRows) {
                "Report exceeds $maxEvidenceRows evidence rows; use a shorter window"
            }
        }
    }

    private data class RunKey(val job: String, val instant: Instant, val ordinal: Int)
    private data class Reservation(val key: RunKey, val end: Instant)
    private data class PairKey(val resource: String, val left: RunKey, val right: RunKey)
    private data class Span(val start: Instant, val end: Instant)

    private val runOrder = compareBy<RunKey>({ it.job }, { it.instant }, { it.ordinal })
    private val reservationOrder = compareBy<Reservation> { it.key.instant }.thenBy(runOrder) { it.key }
    private val pairOrder = compareBy<PairKey> { it.resource }.thenBy(runOrder) { it.left }.thenBy(runOrder) { it.right }

    fun compare(before: Plan, after: Plan, start: Instant, end: Instant): ObjectNode {
        require(start < end) { "Window must be nonempty" }
        val baseline = byId(before)
        val candidate = byId(after)
        val budget = Budget()
        val report = Wire.json.createObjectNode().put("formatVersion", 1)
        report.putObject("window").put("start", start.toString()).put("end", end.toString())
        val jobs = report.putArray("jobs")
        val changes = report.putArray("changes")
        val conflicts = report.putArray("conflicts")
        val leftRuns = mutableMapOf<String, MutableList<Reservation>>()
        val rightRuns = mutableMapOf<String, MutableList<Reservation>>()
        var configChanged = false
        for (id in (baseline.keys + candidate.keys).sorted()) {
            val old = baseline[id]
            val new = candidate[id]
            val left = project(old, start, end, budget)
            // Reuse projections for unchanged job definitions. Reservations are still compared separately.
            val right = if (old == new) left else project(new, start, end, budget)
            reserve(old, left, leftRuns, start)
            reserve(new, right, rightRuns, start)
            val job = jobs.addObject().put("id", id).put("scheduleChanged", old != new)
            job.set<JsonNode>("before", config(old))
            job.set<JsonNode>("after", config(new))
            events(job.putArray("beforeEvents"), left, start, end, budget)
            events(job.putArray("afterEvents"), right, start, end, budget)
            compareStarts(id, left.runs, right.runs, start, job, changes, budget)
            configChanged = configChanged || old != new
        }

        val left = collisions(leftRuns, start, end)
        val right = collisions(rightRuns, start, end)
        var introduced = 0
        var resolved = 0
        var existing = 0
        val sha = MessageDigest.getInstance("SHA-256")
        for (pair in (left.keys + right.keys).sortedWith(pairOrder)) {
            val old = left[pair]
            val new = right[pair]
            // A lengthened overlap remains existing on its original interval and is
            // introduced on the added slice. Common work must not mask new contention.
            val boundaries = listOfNotNull(old?.start, old?.end, new?.start, new?.end).distinct().sorted()
            for (i in 0 until boundaries.lastIndex) {
                val a = boundaries[i]
                val b = boundaries[i + 1]
                val had = old != null && a >= old.start && a < old.end
                val has = new != null && a >= new.start && a < new.end
                val kind = when {
                    had && has -> { existing++; "existing" }
                    has -> { introduced++; "introduced" }
                    had -> { resolved++; "resolved" }
                    else -> continue
                }
                budget.addEvidence()
                val leftKey = pair.left
                val rightKey = pair.right
                val key = Wire.json.createArrayNode().add(pair.resource)
                    .add(leftKey.job).add(leftKey.instant.toString()).add(leftKey.ordinal)
                    .add(rightKey.job).add(rightKey.instant.toString()).add(rightKey.ordinal)
                    .add(a.toString()).add(b.toString())
                val hash = HexFormat.of().formatHex(sha.digest(Wire.json.writeValueAsBytes(key)), 0, 12)
                conflicts.addObject().put("id", "CF-$hash").put("classification", kind).put("resource", pair.resource)
                    .put("left", leftKey.job).put("leftInstant", leftKey.instant.toString()).put("leftOrdinal", leftKey.ordinal)
                    .put("right", rightKey.job).put("rightInstant", rightKey.instant.toString()).put("rightOrdinal", rightKey.ordinal)
                    .put("start", a.toString()).put("end", b.toString())
            }
        }
        report.putObject("stats").put("jobs", jobs.size()).put("changes", changes.size())
            .put("introduced", introduced).put("existing", existing).put("resolved", resolved)
        val status = when {
            introduced > 0 -> "CONFLICT"
            configChanged || !changes.isEmpty || resolved > 0 -> "CHANGE"
            else -> "PASS"
        }
        return report.put("status", status)
    }

    private fun byId(plan: Plan): Map<String, Job> {
        val jobs = plan.jobs.associateBy { it.id }
        require(jobs.size == plan.jobs.size) { "Duplicate job ID" }
        return jobs
    }

    private fun config(job: Job?): JsonNode {
        if (job == null) return Wire.json.nodeFactory.nullNode()
        val schedule = job.schedule
        return Wire.json.createObjectNode().put("cron", schedule.cron).put("zone", schedule.zone)
            .put("gap", schedule.gap).put("overlap", schedule.overlap)
            .put("durationMinutes", job.durationMinutes).put("resource", job.resource)
    }

    private fun project(job: Job?, start: Instant, end: Instant, budget: Budget): Projection {
        if (job == null) return absent
        val lookback = if (job.resource == null) 0L else job.durationMinutes * 60L
        val result = CalendarEngine.project(job.schedule, start.minusSeconds(lookback), end)
        budget.projected += result.runs.size + result.events.size
        require(budget.projected <= maxProjectedItems) {
            "Comparison exceeds $maxProjectedItems projected runs and clock events; use a shorter window"
        }
        return result
    }

    private fun events(target: ArrayNode, projection: Projection, start: Instant, end: Instant, budget: Budget) {
        for (event in projection.events) {
            if (!event.inWindow(start, end)) continue
            budget.addEvidence()
            val emitted = target.addObject().put("local", event.local.toString()).put("type", event.type)
                .put("before", event.before.toString()).put("after", event.after.toString()).putArray("emitted")
            event.emitted.filter { it >= start && it < end }.forEach { emitted.add(it.toString()) }
        }
    }

    private fun compareStarts(id: String, left: List<Occurrence>, right: List<Occurrence>, start: Instant,
                              job: ObjectNode, changes: ArrayNode, budget: Budget) {
        var i = left.indexOfFirst { it.instant >= start }.let { if (it < 0) left.size else it }
        var j = right.indexOfFirst { it.instant >= start }.let { if (it < 0) right.size else it }
        val beforeCount = left.size - i
        val afterCount = right.size - j
        var matched = 0
        while (i < left.size || j < right.size) {
            val old = left.getOrNull(i)
            val new = right.getOrNull(j)
            when {
                new == null || (old != null && old.instant < new.instant) -> {
                    appendChange(changes, id, "removed", left[i++], budget)
                }
                old == null || new.instant < old.instant -> {
                    appendChange(changes, id, "added", right[j++], budget)
                }
                else -> {
                    val stamp = old.instant
                    val oldStart = i
                    val newStart = j
                    while (i < left.size && left[i].instant == stamp) i++
                    while (j < right.size && right[j].instant == stamp) j++
                    val oldSize = i - oldStart
                    val newSize = j - newStart
                    matched += minOf(oldSize, newSize)
                    if (oldSize != newSize) {
                        val oldGroup = left.subList(oldStart, i)
                        val newGroup = right.subList(newStart, j)
                        if (oldSize > newSize) {
                            for (run in excess(oldGroup, newGroup)) appendChange(changes, id, "removed", run, budget)
                        } else {
                            for (run in excess(newGroup, oldGroup)) appendChange(changes, id, "added", run, budget)
                        }
                    }
                }
            }
        }
        job.put("beforeCount", beforeCount).put("afterCount", afterCount).put("unchanged", matched)
            .put("removed", beforeCount - matched).put("added", afterCount - matched)
    }

    // At a spring transition, a shifted 02:30 and an ordinary 03:30 can share
    // one UTC instant. Preserve exact matches first to identify the extra run.
    private fun excess(more: List<Occurrence>, fewer: List<Occurrence>): List<Occurrence> {
        val counts = fewer.groupingBy { it }.eachCount().toMutableMap()
        val remaining = more.filter {
            val n = counts.getOrDefault(it, 0)
            if (n > 0) counts[it] = n - 1
            n == 0
        }
        return remaining.takeLast(more.size - fewer.size)
    }

    private fun appendChange(changes: ArrayNode, id: String, kind: String, run: Occurrence, budget: Budget) {
        budget.addEvidence()
        changes.addObject().put("job", id).put("kind", kind).put("instant", run.instant.toString())
            .put("local", run.local.toString()).put("offset", run.offset.toString()).put("resolution", run.resolution)
    }

    private fun reserve(job: Job?, projection: Projection, calendar: MutableMap<String, MutableList<Reservation>>, start: Instant) {
        val resource = job?.resource ?: return
        val list = calendar.getOrPut(resource) { mutableListOf() }
        var previous: Instant? = null
        var ordinal = 0
        for (run in projection.runs) {
            ordinal = if (run.instant == previous) ordinal + 1 else 0
            previous = run.instant
            val finish = run.instant.plusSeconds(job.durationMinutes * 60L)
            if (finish > start) list.add(Reservation(RunKey(job.id, run.instant, ordinal), finish))
        }
    }

    private fun collisions(calendar: Map<String, MutableList<Reservation>>, start: Instant, end: Instant): Map<PairKey, Span> {
        val result = mutableMapOf<PairKey, Span>()
        for ((resource, runs) in calendar) {
            runs.sortWith(reservationOrder)
            val byEnd = PriorityQueue(compareBy<Reservation> { it.end }.then(reservationOrder))
            val active = LinkedHashSet<Reservation>()
            for (run in runs) {
                while (byEnd.isNotEmpty() && byEnd.peek().end <= run.key.instant) {
                    active.remove(byEnd.remove())
                }
                // Every pair still active overlaps inside the reporting window.
                // Bound pair materialization before allocating the next entry.
                for (other in active) {
                    require(result.size < maxPairs) {
                        "One plan exceeds $maxPairs overlapping run pairs; use a shorter window or fewer jobs"
                    }
                    val key = if (runOrder.compare(other.key, run.key) < 0) PairKey(resource, other.key, run.key)
                              else PairKey(resource, run.key, other.key)
                    result[key] = Span(maxOf(start, run.key.instant), minOf(end, other.end, run.end))
                }
                active.add(run)
                byEnd.add(run)
            }
        }
        return result
    }
}
