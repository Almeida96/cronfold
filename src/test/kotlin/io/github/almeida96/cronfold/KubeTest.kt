package io.github.almeida96.cronfold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import kotlin.test.*

class KubeTest {
    private fun cronJob(name: String = "close", cron: String = "0 1 * * *", zone: String? = "UTC"): ObjectNode {
        val root = Wire.json.createObjectNode().put("apiVersion", "batch/v1").put("kind", "CronJob")
        root.putObject("metadata").put("name", name)
        val spec = root.putObject("spec").put("schedule", cron)
        if (zone != null) spec.put("timeZone", zone)
        return root
    }

    private fun convert(input: JsonNode, zone: String? = null): ObjectNode = Kube.convert(input, 40, zone, "skip", "both")

    @Test fun importsProductionShapedTypedListWithDurationsResourcesAndConcurrencyNotes() {
        val root = javaClass.getResourceAsStream("/kubernetes/warehouse.json")!!.use(Wire.json::readTree)
        val report = convert(root)
        assertEquals("warehouse.ledger-close", report.at("/plan/jobs/0/id").textValue())
        assertEquals("America/New_York", report.at("/plan/jobs/1/zone").textValue())
        assertEquals(25, report.at("/plan/jobs/1/durationMinutes").intValue())
        assertEquals("warehouse-writer", report.at("/plan/jobs/0/resource").textValue())
        assertEquals(2, report["notes"].count { "concurrencyPolicy=Forbid" in it.textValue() })
        assertFalse(report.toString().contains("busybox"))
    }

    @Test fun requiresExplicitControllerZoneAndReportsSuspensionAndDeadlines() {
        val input = cronJob(zone = null)
        assertFailsWith<IllegalArgumentException> { convert(input) }
        val spec = input["spec"] as ObjectNode
        spec.put("startingDeadlineSeconds", 120).put("concurrencyPolicy", "Replace")
        val report = convert(input, zone = "America/Chicago")
        assertEquals("America/Chicago", report.at("/plan/jobs/0/zone").textValue())
        assertEquals("default.close", report.at("/plan/jobs/0/id").textValue())
        for (needle in listOf("controller zone", "startingDeadlineSeconds=120", "concurrencyPolicy=Replace"))
            assertTrue(report["notes"].any { needle in it.textValue() })
        spec.put("suspend", true)
        val omitted = convert(input)
        assertTrue(omitted.at("/plan/jobs").isEmpty)
        assertTrue(omitted["notes"].any { "default.close excluded" in it.textValue() })
        spec.put("suspend", "false")
        assertFailsWith<IllegalArgumentException> { convert(input) }
    }

    @Test fun normalizesKubernetesCalendarFlagsWithoutChangingWhichDatesMatch() {
        val start = Instant.parse("2026-02-01T00:00:00Z")
        val until = Instant.parse("2026-03-01T00:00:00Z")
        val days = (1..28).map { LocalDate.of(2026, 2, it) }
        fun dates(cron: String): List<LocalDate> {
            val spec = Schedule(convert(cronJob(cron = cron)).at("/plan/jobs/0/cron").textValue(), "UTC")
            return CalendarEngine.project(spec, start, until).runs.map { it.instant.atOffset(ZoneOffset.UTC).toLocalDate() }
        }
        // A stepped wildcard is restricted in robfig. Two restricted day fields use OR.
        assertEquals(days.filter { it.dayOfMonth % 2 == 1 || it.dayOfWeek.value == 1 }, dates("0 0 */2 * MON"))
        // Any unstepped wildcard list term sets the star bit and enables AND.
        assertEquals(days.filter { it.dayOfWeek.value == 1 }, dates("0 0 5,?/1 * MON"))
        // robfig ends the weekday range at 6. Extending to 7 would add another Sunday.
        assertEquals(days.filter { it.dayOfWeek.value in setOf(2, 4, 6) }, dates("0 0 * * 2/2"))
        assertEquals(days.filter { it.dayOfMonth == 1 }, dates("@monthly"))
        assertEquals("10-59/15 0 * * *", convert(cronJob(cron = "10/15 0 ? * *")).at("/plan/jobs/0/cron").textValue())
        for (malformed in listOf("0 0 * * 7", "0 0 *,40 * MON", "@every 15m", "CRON_TZ=UTC 0 * * * *"))
            assertFailsWith<IllegalArgumentException> { dates(malformed) }
    }

    @Test fun rejectsWrongKindsDuplicateIdentitiesPartialListsAndBadAnnotations() {
        val generic = Wire.json.createObjectNode().put("kind", "List").put("apiVersion", "v1")
        val items = generic.putArray("items")
        items.add(cronJob())
        assertEquals(1, convert(generic).at("/plan/jobs").size())
        val second = cronJob()
        items.add(second)
        assertFailsWith<IllegalArgumentException> { convert(generic) }
        (second["metadata"] as ObjectNode).put("namespace", "other")
        assertEquals(2, convert(generic).at("/plan/jobs").size())
        second.put("kind", "Deployment")
        assertFailsWith<IllegalArgumentException> { convert(generic) }
        items.remove(1)
        generic.putObject("metadata").put("continue", "token")
        assertFailsWith<IllegalArgumentException> { convert(generic) }
        val input = cronJob()
        val annotations = (input["metadata"] as ObjectNode).putObject("annotations")
        annotations.put("cronfold.dev/duration-minutes", "1441")
        assertFailsWith<IllegalArgumentException> { convert(input) }
        annotations.put("cronfold.dev/duration-minutes", 5)
        assertFailsWith<IllegalArgumentException> { convert(input) }
        annotations.removeAll().put("cronfold.dev/resource", "invalid spaces")
        assertFailsWith<IllegalArgumentException> { convert(input) }
        assertFailsWith<IllegalArgumentException> { convert(cronJob(zone = "Never/Land")) }
    }
}
