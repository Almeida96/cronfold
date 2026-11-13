package io.github.almeida96.cronfold

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DiffTest {
    private fun at(value: String) = Instant.parse(value)
    private fun job(id: String, cron: String, duration: Int = 5, resource: String? = "ledger") =
        Job(id, Schedule(cron, "UTC"), duration, resource)
    private val dayStart = at("2026-11-01T00:00:00Z")
    private val dayEnd = at("2026-11-02T00:00:00Z")

    @Test fun newYorkFoldIntroducesAnActualCollision() {
        val schedule = Schedule("30 1 * * *", "America/New_York", overlap = "earlier")
        val batch = Job("export", schedule, 20, "ledger")
        val settle = job("close", "35 6 * * *")
        val report = Diff.compare(Plan(listOf(batch, settle)), Plan(listOf(settle, batch.copy(
            schedule = schedule.copy(overlap = "both")))), dayStart, dayEnd)
        assertEquals("CONFLICT", report.path("status").asText())
        val run = report.path("changes").single()
        assertEquals("2026-11-01T06:30:00Z", run.path("instant").asText())
        assertEquals("overlap-later", run.path("resolution").asText())
        assertEquals("-05:00", run.path("offset").asText())
        val conflict = report.path("conflicts").single()
        assertEquals("introduced", conflict.path("classification").asText())
        assertEquals("2026-11-01T06:35:00Z", conflict.path("start").asText())
        val summary = report.path("jobs").single { it.path("id").asText() == "export" }
        assertEquals(1, summary.path("beforeCount").asInt())
        assertEquals(2, summary.path("afterCount").asInt())
        assertEquals(2, summary.path("afterEvents").single().path("emitted").size())
    }

    @Test fun increasingDurationOnPersistentRunPairReportsOnlyTheNewlyExposedTail() {
        val left = job("inventory", "0 0 * * *", 10)
        val right = job("settle", "5 0 * * *", 25)
        val before = Plan(listOf(left, right))
        val after = Plan(listOf(left.copy(durationMinutes = 20), right))
        val report = Diff.compare(before, after, dayStart, dayEnd)
        assertTrue(report.path("changes").isEmpty)
        assertEquals("CONFLICT", report.path("status").asText())
        val conflicts = report.path("conflicts")
        assertEquals(listOf("existing", "introduced"), conflicts.map { it.path("classification").asText() })
        assertEquals("2026-11-01T00:05:00Z", conflicts[0].path("start").asText())
        assertEquals("2026-11-01T00:10:00Z", conflicts[0].path("end").asText())
        assertEquals("2026-11-01T00:10:00Z", conflicts[1].path("start").asText())
        assertEquals("2026-11-01T00:20:00Z", conflicts[1].path("end").asText())
        assertNotEquals(conflicts[0].path("id"), conflicts[1].path("id"))
        val reverse = Diff.compare(after, before, dayStart, dayEnd)
        assertEquals("CHANGE", reverse.path("status").asText())
        assertEquals("resolved", reverse.path("conflicts")[1].path("classification").asText())
    }

    @Test fun inFlightWorkCountsEvenIfBothRunsStartedBeforeTheWindow() {
        val start = at("2026-11-01T01:00:00Z")
        val end = at("2026-11-01T01:01:30Z")
        val left = job("long", "30 0 * * *", 65)
        val right = job("other", "55 0 * * *", 10)
        val before = Plan(listOf(left))
        val after = Plan(listOf(right, left))
        val report = Diff.compare(before, after, start, end)
        assertTrue(report.path("changes").isEmpty)
        assertTrue(report.path("jobs").all { it.path("afterCount").asInt() == 0 })
        val conflict = report.path("conflicts").single()
        assertEquals("2026-11-01T00:30:00Z", conflict.path("leftInstant").asText())
        assertEquals("2026-11-01T00:55:00Z", conflict.path("rightInstant").asText())
        assertEquals(start.toString(), conflict.path("start").asText())
        assertEquals(end.toString(), conflict.path("end").asText())
        assertEquals("CONFLICT", report.path("status").asText())
        val unchanged = Diff.compare(after, after, start, end)
        assertEquals("PASS", unchanged.path("status").asText())
        assertEquals("existing", unchanged.path("conflicts").single().path("classification").asText())
    }

    @Test fun sameInstantSpringExecutionsRetainMultiplicityAndIdentifyShiftedOccurrence() {
        val current = Job("payments", Schedule("30 2,3 * * *", "America/New_York", gap = "skip"), 10, "transfer")
        val shifted = current.copy(schedule = current.schedule.copy(gap = "shift-forward"))
        val report = Diff.compare(Plan(listOf(current)), Plan(listOf(shifted)),
            at("2026-03-08T06:00:00Z"), at("2026-03-08T09:00:00Z"))
        val delta = report.path("changes").single()
        assertEquals("added", delta.path("kind").asText())
        assertEquals("gap-shifted", delta.path("resolution").asText())
        assertEquals("2026-03-08T02:30", delta.path("local").asText())
        assertEquals("2026-03-08T07:30:00Z", delta.path("instant").asText())
        val pair = report.path("conflicts").single()
        assertEquals(pair.path("rightInstant"), pair.path("leftInstant"))
        assertEquals(pair.path("right"), pair.path("left"))
        assertNotEquals(pair.path("rightOrdinal"), pair.path("leftOrdinal"))
        assertEquals("CONFLICT", report.path("status").asText())
    }

    @Test fun halfOpenDurationsCanMeetAtTheBoundaryAndIndependentResourcesCanOverlap() {
        val jobs = listOf(job("a", "0 1 * * *", 5), job("b", "5 1 * * *", 7),
            job("other", "0 1 * * *", 30, "other"), job("free", "0 1 * * *", 30, null))
        val report = Diff.compare(Plan(jobs.reversed()), Plan(jobs), dayStart, dayEnd)
        assertEquals("PASS", report.path("status").asText())
        assertTrue(report.path("conflicts").isEmpty)
        assertTrue(report.path("changes").isEmpty)
        assertEquals(listOf("a","b","free","other"), report.path("jobs").map { it.path("id").asText() })
    }

    @Test fun localScheduleCanChangeWithoutMovingAnyUtcStart() {
        val utc = job("load", "0 12 * * *")
        val report = Diff.compare(Plan(listOf(utc)), Plan(listOf(utc.copy(
            schedule = Schedule("0 7 * * *", "America/New_York")))), dayStart, dayEnd)
        assertEquals("CHANGE", report.path("status").asText())
        assertTrue(report.path("changes").isEmpty)
        assertEquals(1, report.path("jobs").single().path("unchanged").asInt())
    }

    @Test fun foldEventEmissionsAreClippedAfterLookingBackForInFlightRuns() {
        val job = Job("summary", Schedule("30 1 * * *", "America/New_York"), 120, "ledger")
        val start = at("2026-11-01T06:00:00Z")
        val report = Diff.compare(Plan(listOf(job)), Plan(listOf(job)), start, at("2026-11-01T07:00:00Z"))
        val summary = report.path("jobs").single()
        assertEquals(1, summary.path("afterCount").asInt())
        assertEquals(listOf("2026-11-01T06:30:00Z"),
            summary.path("afterEvents").single().path("emitted").map { it.asText() })
        assertEquals("existing", report.path("conflicts").single().path("classification").asText())
    }

    @Test fun highContentionInputsFailExplicitlyAtThePairLimit() {
        val busy = job("minutely", "* * * * *", 1440)
        val error = assertFailsWith<IllegalArgumentException> {
            Diff.compare(Plan(emptyList()), Plan(listOf(busy)), dayStart, dayEnd)
        }
        assertTrue("overlapping run pairs" in error.message.orEmpty())
    }
}
