package io.github.almeida96.cronfold

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules
import java.util.Locale

data class Schedule(val cron: String, val zone: String, val gap: String = "skip", val overlap: String = "both")

/** local is the nominal cron time, offset is the offset in effect at the emitted instant. */
data class Occurrence(val instant: Instant, val local: LocalDateTime, val offset: ZoneOffset, val resolution: String)

data class ClockEvent(
    val local: LocalDateTime,
    val type: String,
    val before: ZoneOffset,
    val after: ZoneOffset,
    val emitted: List<Instant>
)

data class Projection(val runs: List<Occurrence>, val events: List<ClockEvent>)

internal fun ClockEvent.inWindow(start: Instant, end: Instant): Boolean {
    val first = local.toInstant(before)
    if (first >= start && first < end) return true
    return type == "overlap" && local.toInstant(after).let { it >= start && it < end }
}

object CalendarEngine {
    private val maxWindow = Duration.ofDays(5 * 366L)
    private val zoneIds = ZoneId.getAvailableZoneIds()
    private const val maxItems = 100_000
    private const val maxCandidates = 500_000

    fun validate(schedule: Schedule) { parseSchedule(schedule) }

    private fun parseSchedule(schedule: Schedule): CronExpression {
        require(schedule.zone in zoneIds) { "Unknown TZDB zone: ${schedule.zone}" }
        require(schedule.gap in setOf("skip", "shift-forward")) { "Unknown gap policy: ${schedule.gap}" }
        require(schedule.overlap in setOf("both", "earlier", "later")) { "Unknown overlap policy: ${schedule.overlap}" }
        return CronExpression.parse(schedule.cron)
    }

    fun project(schedule: Schedule, start: Instant, end: Instant): Projection {
        require(end > start && Duration.between(start, end) <= maxWindow) {
            "The end must follow the start by at most 1830 days"
        }
        val cron = parseSchedule(schedule)
        val rules = ZoneId.of(schedule.zone).rules
        val collector = Collector(start, end, schedule, rules)

        // An emitted instant relates to its nominal local time by one legal ZoneOffset.
        // For a gap that is the pre-transition offset, even if an entire date is skipped.
        val first = LocalDateTime.ofInstant(start, ZoneOffset.MIN).toLocalDate()
        val last = LocalDateTime.ofInstant(end.minusNanos(1), ZoneOffset.MAX).toLocalDate()
        var day = first
        var candidates = 0
        while (true) {
            if (cron.matchesDay(day)) {
                for (hour in cron.hours) {
                    for (minute in cron.minutes) {
                        require(++candidates <= maxCandidates) { "A schedule exceeds 500000 candidate minutes" }
                        collector.resolve(day.atTime(hour, minute))
                    }
                }
            }
            if (day == last) break
            day = day.plusDays(1)
        }
        return Projection(collector.runs.sortedWith(compareBy({ it.instant }, { it.local })), collector.events.toList())
    }

    private class Collector(
        private val start: Instant,
        private val end: Instant,
        private val schedule: Schedule,
        private val rules: ZoneRules
    ) {
        val runs = ArrayList<Occurrence>()
        val events = ArrayList<ClockEvent>()

        fun resolve(local: LocalDateTime) {
            val offsets = rules.getValidOffsets(local)
            if (offsets.size == 1) {
                add(local.toInstant(offsets[0]), local, offsets[0], "normal")
                return
            }
            val transition = checkNotNull(rules.getTransition(local))
            val before = transition.offsetBefore
            val after = transition.offsetAfter
            val oldMapping = local.toInstant(before)
            val emitted = ArrayList<Instant>(2)
            val event = ClockEvent(local, if (transition.isGap) "gap" else "overlap", before, after, emitted)
            if (!event.inWindow(start, end)) return
            if (transition.isGap) {
                // UTC of (nominal + gap) at the new offset equals UTC of nominal at the old offset.
                if (schedule.gap == "shift-forward") {
                    add(oldMapping, local, after, "gap-shifted")
                    emitted.add(oldMapping)
                }
            } else {
                val later = local.toInstant(after)
                if (schedule.overlap != "later" && add(oldMapping, local, before, "overlap-earlier")) {
                    emitted.add(oldMapping)
                }
                if (schedule.overlap != "earlier" && add(later, local, after, "overlap-later")) {
                    emitted.add(later)
                }
            }
            require(events.size < maxItems) { "A schedule exceeds 100000 clock events" }
            events.add(event)
        }

        private fun inWindow(instant: Instant) = instant >= start && instant < end

        private fun add(instant: Instant, local: LocalDateTime, offset: ZoneOffset, resolution: String): Boolean {
            if (!inWindow(instant)) return false
            require(runs.size < maxItems) { "A schedule exceeds 100000 emitted runs" }
            runs.add(Occurrence(instant, local, offset, resolution))
            return true
        }
    }
}

private class CronExpression(
    val minutes: List<Int>,
    val hours: List<Int>,
    private val monthDays: Set<Int>,
    private val months: Set<Int>,
    private val weekDays: Set<Int>,
    private val dayStartsWithStar: Boolean
) {
    fun matchesDay(day: LocalDate): Boolean {
        if (day.monthValue !in months) return false
        val dom = day.dayOfMonth in monthDays
        val dow = day.dayOfWeek.value % 7 in weekDays
        // Cronie's DOM_STAR/DOW_STAR flags depend on the first character of the field.
        return if (dayStartsWithStar) dom && dow else dom || dow
    }

    companion object {
        private val whitespace = Regex("[ \\t]+")
        private val monthNames = "JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC".split(" ")
            .mapIndexed { index, name -> name to index + 1 }.toMap()
        private val dayNames = "SUN MON TUE WED THU FRI SAT".split(" ")
            .mapIndexed { index, name -> name to index }.toMap()

        fun parse(value: String): CronExpression {
            require(value.length in 1..256 && value.none { it == '\n' || it == '\r' }) {
                "Expected a single five-field cron expression up to 256 characters"
            }
            val parts = value.trim(' ', '\t').split(whitespace)
            require(parts.size == 5) { "Expected five cron fields (minute hour day-of-month month day-of-week)" }
            return CronExpression(
                field(parts[0], 0, 59).toList(), field(parts[1], 0, 23).toList(),
                field(parts[2], 1, 31), field(parts[3], 1, 12, monthNames),
                field(parts[4], 0, 7, dayNames).mapTo(mutableSetOf()) { it % 7 },
                parts[2].startsWith('*') || parts[4].startsWith('*')
            )
        }

        private fun field(source: String, min: Int, max: Int, names: Map<String, Int> = emptyMap()): Set<Int> {
            fun number(token: String): Int {
                val value = names[token.uppercase(Locale.ROOT)] ?: token.takeIf {
                    it.isNotEmpty() && it.all { char -> char in '0'..'9' }
                }?.toIntOrNull()
                require(value != null && value in min..max) { "Invalid value '$token' in cron field '$source'" }
                return value
            }
            val result = sortedSetOf<Int>()
            for (term in source.split(',')) {
                val stepParts = term.split('/')
                require(stepParts.size in 1..2) { "Invalid step in cron field '$source'" }
                val spec = stepParts[0]
                val stride = if (stepParts.size == 1) 1 else {
                    val stepText = stepParts[1]
                    require(stepText.isNotEmpty() && stepText.all { it in '0'..'9' }) { "Invalid cron step '$term'" }
                    requireNotNull(stepText.toIntOrNull()?.takeIf { it > 0 }) { "Invalid cron step '$term'" }
                }
                val range = if (spec == "*") min..max else {
                    val bounds = spec.split('-')
                    require(bounds.size in 1..2 && (stepParts.size == 1 || bounds.size == 2)) {
                        "Use an ascending range or * before '/' in '$term'"
                    }
                    val lo = number(bounds[0])
                    val hi = if (bounds.size == 1) lo else number(bounds[1])
                    require(lo <= hi) { "Wrapping cron ranges are unsupported: '$term'" }
                    lo..hi
                }
                for (candidate in range step stride) result.add(candidate)
            }
            return result
        }
    }
}
