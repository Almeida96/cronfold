package io.github.almeida96.cronfold

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliTest {
    @TempDir lateinit var temp: Path
    private fun cli(vararg args: String): Int = PrintStream(ByteArrayOutputStream()).use {
        command(arrayOf(*args), ByteArrayInputStream(byteArrayOf()), it, it)
    }

    @Test fun warehouseDemoShowsFifteenMinutesOfIntroducedContention() {
        val out = temp.resolve("demo")
        assertEquals(0, cli("demo", "--out", out.toString()))
        val bytes = Files.readAllBytes(out.resolve("conflict/report.json"))
        val report = Wire.json.readTree(bytes)
        val overlap = report["conflicts"].single()
        assertEquals("introduced", overlap["classification"].asText())
        assertEquals("2026-11-01T06:40:00Z", overlap["start"].asText())
        assertEquals("2026-11-01T06:55:00Z", overlap["end"].asText())
        val run = report["changes"].single()
        assertEquals("2026-11-01T06:30:00Z", run["instant"].asText())
        assertEquals("-05:00", run["offset"].asText())
        assertTrue(Files.readString(out.resolve("conflict/report.html")).contains("15 min"))
        assertTrue(report.at("/engine/tzdb").has("America/New_York"))
        assertEquals(2, cli("demo", "--out", out.toString()))
        assertTrue(bytes.contentEquals(Files.readAllBytes(out.resolve("conflict/report.json"))))
    }

    @Test fun strictModeGatesOrdinaryChangesAndLeavesTheReportForReview() {
        val dir = temp.resolve("fixture")
        assertEquals(0, cli("demo", "--out", dir.toString()))
        val common = arrayOf("compare", "--before", dir.resolve("plans/baseline.json").toString(),
            "--from","2026-11-01T04:00:00Z","--until","2026-11-01T09:00:00Z")
        fun run(after: String, place: String, vararg flags: String): Int =
            cli(*common, "--after", dir.resolve("plans/$after.json").toString(), "--out", temp.resolve(place).toString(), *flags)
        assertEquals(0, run("baseline", "clean", "--strict"))
        assertEquals(1, run("candidate", "conflict"))
        assertEquals(0, run("fixed", "change"))
        assertEquals(1, run("fixed", "strict-change", "--strict"))
        assertTrue(Files.readAllBytes(temp.resolve("change/report.json")).contentEquals(Files.readAllBytes(temp.resolve("strict-change/report.json"))))
    }

    @Test fun canCompareAnImportedPlanWithoutACluster() {
        val export = temp.resolve("cronjob-export.json")
        Files.writeString(export, """{"apiVersion":"batch/v1","kind":"CronJob","metadata":{"name":"cleanup"},
            "spec":{"schedule":"0 4 * * *","concurrencyPolicy":"Forbid"}}""")
        val folder = temp.resolve("imported")
        assertEquals(0, cli("import-kube","--input",export.toString(),"--duration","20","--controller-zone","UTC","--out",folder.toString()))
        val plan = folder.resolve("plan.json")
        assertEquals("default.cleanup", Wire.json.readTree(Files.readAllBytes(plan)).at("/jobs/0/id").asText())
        assertTrue(Files.readString(folder.resolve("import-notes.json")).contains("Forbid"))
        assertEquals(0, cli("compare","--before",plan.toString(),"--after",plan.toString(),"--from","2026-11-01T00:00:00Z",
            "--until","2026-11-02T00:00:00Z","--out",temp.resolve("review").toString(),"--strict"))
    }

    @Test fun rejectsTyposAmbiguousInputsAndDuplicateJobIds() {
        val empty = Files.writeString(temp.resolve("empty.json"), """{"version":1,"jobs":[]}""")
        val changed = temp.resolve("input.json")
        val base = """{"id":"jobs","cron":"* * * * *","zone":"UTC","durationMinutes":1}"""
        val invalid = listOf("""{"version":1,"version":1,"jobs":[]}""", """{"version":1,"jobs":[]} []""",
            """{"version":1,"jobs":[$base,$base]}""", """{"version":1,"jobs":[${base.replace("durationMinutes","durationMinute")}]}""",
            """{"version":1,"jobs":[${base.replace("UTC","NoSuch/Timezone")}]}""")
        for (body in invalid) {
            Files.writeString(changed, body)
            val dir = temp.resolve("fresh")
            assertEquals(2, cli("compare","--before",empty.toString(),"--after",changed.toString(),
                "--from","2026-01-01T00:00:00Z","--until","2026-01-02T00:00:00Z","--out",dir.toString()))
            assertFalse(Files.exists(dir))
        }
    }
}
