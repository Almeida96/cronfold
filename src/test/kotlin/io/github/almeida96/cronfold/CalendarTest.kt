package io.github.almeida96.cronfold

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarTest {
    private fun project(cron: String, zone: String, start: String, end: String,
                        gap: String = "skip", overlap: String = "both"): Projection {
        return CalendarEngine.project(Schedule(cron, zone, gap, overlap), Instant.parse(start), Instant.parse(end))
    }

    @Test fun newYorkFoldKeepsBothDistinctInstantsWithTheirOwnOffsets() {
        val p = project("30 1 * * *", "America/New_York", "2026-11-01T04:00:00Z", "2026-11-01T08:00:00Z")
        val first = Instant.parse("2026-11-01T05:30:00Z")
        val second = Instant.parse("2026-11-01T06:30:00Z")
        assertEquals(listOf(first, second), p.runs.map { it.instant })
        assertEquals(listOf(ZoneOffset.ofHours(-4), ZoneOffset.ofHours(-5)), p.runs.map { it.offset })
        assertEquals(listOf("overlap-earlier", "overlap-later"), p.runs.map { it.resolution })
        assertEquals(listOf(first, second), p.events.single().emitted)
        assertEquals("overlap", p.events.single().type)

        val later = project("30 1 * * *", "America/New_York", first.toString(), second.toString(), overlap = "later")
        assertTrue(later.runs.isEmpty())
        assertTrue(later.events.single().emitted.isEmpty())
        val early = project("30 1 * * *", "America/New_York", first.toString(), second.toString(), overlap = "earlier")
        assertEquals(listOf(first), early.runs.map { it.instant })
        assertFalse(early.events.single().inWindow(first.plusSeconds(1), second))
    }

    @Test fun newYorkGapUsesASyntheticInstantForSkippedEventsAndRetainsCoincidentRuns() {
        val at = "2026-03-08T07:30:00Z"
        val end = "2026-03-08T07:30:00.000000001Z"
        val skipped = project("30 2 * * *", "America/New_York", at, end)
        assertTrue(skipped.runs.isEmpty())
        assertEquals("gap", skipped.events.single().type)
        assertTrue(skipped.events.single().emitted.isEmpty())
        assertTrue(project("30 2 * * *", "America/New_York", "2026-03-08T07:00:00Z", at).events.isEmpty())

        val shifted = project("30 2,3 * * *", "America/New_York", at, end, gap = "shift-forward")
        assertEquals(listOf("gap-shifted", "normal"), shifted.runs.map { it.resolution })
        assertEquals(listOf(2, 3), shifted.runs.map { it.local.hour })
        assertEquals(listOf(Instant.parse(at), Instant.parse(at)), shifted.runs.map { it.instant })
        assertEquals(listOf(Instant.parse(at)), shifted.events.single().emitted)
        assertTrue(shifted.runs.all { it.offset == ZoneOffset.ofHours(-4) })
    }

    @Test fun lordHoweUsesTheActualThirtyMinuteTransition() {
        val gap = project("15 2 * * *", "Australia/Lord_Howe", "2026-10-03T15:30:00Z", "2026-10-03T16:00:00Z",
            gap = "shift-forward")
        assertEquals(Instant.parse("2026-10-03T15:45:00Z"), gap.runs.single().instant)
        assertEquals(LocalDateTime.parse("2026-10-04T02:15:00"), gap.runs.single().local)
        assertEquals(1800, gap.events.single().after.totalSeconds - gap.events.single().before.totalSeconds)
        val fold = project("45 1 * * *", "Australia/Lord_Howe", "2026-04-04T14:00:00Z", "2026-04-04T16:00:00Z")
        assertEquals(listOf("2026-04-04T14:45:00Z", "2026-04-04T15:15:00Z"), fold.runs.map { it.instant.toString() })
    }

    @Test fun aSkippedCalendarDateCanShiftIntoTheNextDatesNormalRun() {
        val p = project("0 9 * * *", "Pacific/Apia", "2011-12-30T19:00:00Z", "2011-12-30T19:01:00Z",
            gap = "shift-forward")
        assertEquals(listOf("2011-12-30T09:00", "2011-12-31T09:00"), p.runs.map { it.local.toString() })
        assertEquals(p.runs[0].instant, p.runs[1].instant)
        assertEquals(ZoneOffset.ofHours(14), p.runs[0].offset)
        assertEquals(ZoneOffset.ofHours(-10), p.events.single().before)
    }

    @Test fun dayFlagsFollowFirstCharacterAndSundayCanBeSeven() {
        val start = "2026-02-01T00:00:00Z"
        val end = "2026-03-01T00:00:00Z"
        fun days(expression: String) = project(expression, "UTC", start, end).runs.map { it.local.dayOfMonth }
        assertEquals(listOf(1, 8, 10, 15, 22), days("0 0 10 feb 7"))
        assertEquals(listOf(1, 8, 15, 22), days("0 0 * FEB sun"))
        assertEquals(listOf(1, 15), days("0 0 */2 FEB SUN"))
        assertEquals((1..28).toList(), days("0 0 1-31 FEB SUN"))
        assertEquals(listOf(2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 16, 17, 18, 19, 20, 23, 24, 25, 26, 27),
            days("0 0 * FEB MON-FRI"))
    }

    @Test fun rangesStepsLeapYearsAndFractionalHalfOpenWindow() {
        val p = project("0,10-41/15 9 29 FEB *", "UTC", "2027-01-01T00:00:00Z", "2028-03-01T00:00:00Z")
        assertEquals(listOf(0, 10, 25, 40), p.runs.map { it.local.minute })
        assertTrue(p.runs.all { it.local.dayOfMonth == 29 && it.local.year == 2028 && it.resolution == "normal" })
        assertTrue(p.events.isEmpty())
        val clipped = project("*/15 * * * *", "UTC", "2026-04-10T00:00:00.000000001Z", "2026-04-10T00:30:00Z")
        assertEquals(Instant.parse("2026-04-10T00:15:00Z"), clipped.runs.single().instant)
    }

    @Test fun inputAndResourceLimitsFailExplicitly() {
        val start = "2026-01-01T00:00:00Z"
        val end = "2026-01-02T00:00:00Z"
        for (cron in listOf("@daily", "0 0 ? * *", "0 0 L * *", "0 0 * * MON#1", "0 0 * * FRI-MON",
                "60 0 * * *", "5/2 0 * * *", "*/0 * * * *", "*/ * * * *", "0, 0 * * *", "* * * * * *")) {
            assertThrows(IllegalArgumentException::class.java) { project(cron, "UTC", start, end) }
        }
        assertThrows(IllegalArgumentException::class.java) { project("0 0 * * *", "No/Such_Zone", start, end) }
        assertThrows(IllegalArgumentException::class.java) { project("0 0 * * *", "UTC", start, end, gap = "next") }
        assertThrows(IllegalArgumentException::class.java) { project("0 0 * * *", "UTC", start, end, overlap = "once") }
        assertThrows(IllegalArgumentException::class.java) { project("0 0 * * *", "UTC", end, end) }
        assertThrows(IllegalArgumentException::class.java) { project("0 0 * * *", "UTC", start, "2040-01-01T00:00:00Z") }
        val overflow = assertThrows(IllegalArgumentException::class.java) {
            project("* * * * *", "UTC", start, "2026-04-01T00:00:00Z")
        }
        assertTrue(overflow.message.orEmpty().contains("100000 emitted runs"))
    }
}
