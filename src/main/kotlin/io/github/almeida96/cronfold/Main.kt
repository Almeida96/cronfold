package io.github.almeida96.cronfold

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.zone.ZoneRulesProvider
import java.util.Comparator
import kotlin.system.exitProcess

private const val VERSION = "0.1.0"
private const val REPORT_LIMIT = 32 * 1024 * 1024
private val help = """
    CronFold compares scheduled runs and shared resource use in an explicit UTC window.

      cronfold compare --before PLAN --after PLAN --from INSTANT --until INSTANT --out NEW_DIR [--strict]
      cronfold import-kube --input EXPORT --duration MINUTES --out NEW_DIR [--controller-zone ZONE] [--gap POLICY] [--overlap POLICY]
      cronfold demo --out NEW_DIR
      cronfold mcp --report FILE
      cronfold --version

    The window includes --from and excludes --until. Maximum 366 days.
    Exit 0 for PASS or CHANGE, 1 for CONFLICT or strict CHANGE, 2 for input or I/O errors.
""".trimIndent()

fun main(args: Array<String>) {
    exitProcess(command(args, System.`in`, System.out, System.err))
}

internal fun command(args: Array<String>, input: InputStream, out: PrintStream, errors: PrintStream): Int {
    try {
        if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) {
            out.println(help)
            return if (args.isEmpty()) 2 else 0
        }
        if (args.contentEquals(arrayOf("--version"))) { out.println(VERSION); return 0 }
        when (args.first()) {
            "compare" -> {
                val options = parse(args.drop(1), setOf("--before", "--after", "--from", "--until", "--out"), switches = setOf("--strict"))
                val before = PlanFiles.load(Path.of(options.getValue("--before")))
                val after = PlanFiles.load(Path.of(options.getValue("--after")))
                val start = Instant.parse(options.getValue("--from"))
                val end = Instant.parse(options.getValue("--until"))
                val dest = Path.of(options.getValue("--out"))
                val status = publish(dest) { dir ->
                    writeReport(dir, before, after, start, end).path("status").asText()
                }
                out.println("$status  " + dest.resolve("report.html"))
                return if (status == "CONFLICT" || status == "CHANGE" && "--strict" in options) 1 else 0
            }
            "import-kube" -> {
                val options = parse(args.drop(1), setOf("--input", "--duration", "--out"),
                    optional = setOf("--controller-zone", "--gap", "--overlap"))
                val file = Path.of(options.getValue("--input"))
                val source = PlanFiles.bytes(file, 16 * 1024 * 1024)
                val root = requireNotNull(Wire.json.readTree(source)) { "Expected Kubernetes JSON" }
                val result = Kube.convert(root, options.getValue("--duration").toInt(), options["--controller-zone"],
                    options["--gap"] ?: "skip", options["--overlap"] ?: "both")
                val dest = Path.of(options.getValue("--out"))
                publish(dest) { dir ->
                    val format = Wire.json.writer(DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter("  ", "\n")))
                    val plan = dir.resolve("plan.json")
                    Files.write(plan, format.writeValueAsBytes(result.required("plan")))
                    PlanFiles.load(plan)
                    Files.write(dir.resolve("import-notes.json"), format.writeValueAsBytes(result.required("notes")))
                }
                out.println("Imported ${result.at("/plan/jobs").size()} jobs to " + dest.resolve("plan.json"))
                out.println("Modeled durations and clock policies: " + dest.resolve("import-notes.json"))
                return 0
            }
            "demo" -> {
                val dest = Path.of(parse(args.drop(1), setOf("--out")).getValue("--out"))
                publish(dest, ::demo)
                out.println("CONFLICT  " + dest.resolve("conflict/report.html"))
                out.println("CHANGE    " + dest.resolve("fixed/report.html"))
                return 0
            }
            "mcp" -> {
                val path = Path.of(parse(args.drop(1), setOf("--report")).getValue("--report"))
                Mcp.serve(Wire.json.readTree(PlanFiles.bytes(path, REPORT_LIMIT)), input, out)
                return 0
            }
            else -> throw IllegalArgumentException("Unknown command: " + args.first())
        }
    } catch (e: Exception) {
        errors.println("cronfold: " + (e.message ?: e.javaClass.simpleName))
        return 2
    }
}

private fun parse(tokens: List<String>, required: Set<String>, optional: Set<String> = emptySet(),
                  switches: Set<String> = emptySet()): Map<String,String> {
    val result = mutableMapOf<String,String>()
    var i = 0
    while (i < tokens.size) {
        val flag = tokens[i++]
        require(flag !in result) { "Repeated option: $flag" }
        if (flag in switches) { result[flag] = ""; continue }
        require(flag in required || flag in optional) { "Unknown option: $flag" }
        require(i < tokens.size && tokens[i].isNotBlank() && !tokens[i].startsWith("--")) { "Missing value for $flag" }
        result[flag] = tokens[i++]
    }
    require(result.keys.containsAll(required)) { "Missing options: " + (required - result.keys).joinToString() }
    return result
}

private fun writeReport(dir: Path, before: SourcePlan, after: SourcePlan, start: Instant, end: Instant): ObjectNode {
    val duration = Duration.between(start, end)
    require(!duration.isNegative && !duration.isZero && duration <= Duration.ofDays(366)) { "Choose a window over zero and up to 366 days" }
    val report = Diff.compare(before.plan, after.plan, start, end)
    val inputs = report.putObject("inputs")
    for ((side, input) in listOf("before" to before, "after" to after)) {
        inputs.putObject(side).put("file", input.file).put("sha256", input.sha256)
    }
    val provenance = report.putObject("engine").put("version", VERSION).putObject("tzdb")
    for (zone in (before.plan.jobs + after.plan.jobs).map { it.schedule.zone }.toSortedSet()) {
        val rulesVersion = try { ZoneRulesProvider.getVersions(zone).lastKey() }
            catch (_: java.time.zone.ZoneRulesException) { "fixed-offset" }
        provenance.put(zone, rulesVersion)
    }
    val pretty = DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter("  ", "\n"))
    val bytes = Wire.json.writer(pretty).writeValueAsBytes(report)
    require(bytes.size <= REPORT_LIMIT) { "Report exceeds 32 MiB. Shorten the time window." }
    Files.write(dir.resolve("report.json"), bytes)
    Files.writeString(dir.resolve("report.html"), Html.render(report))
    return report
}

private fun demo(dir: Path) {
    val plans = Files.createDirectory(dir.resolve("plans"))
    val source = listOf("baseline", "candidate", "fixed").associateWith { name ->
        val file = plans.resolve("$name.json")
        checkNotNull(object {}.javaClass.getResourceAsStream("/warehouse/$name.json")).use { Files.copy(it, file) }
        PlanFiles.load(file)
    }
    val from = Instant.parse("2026-11-01T04:00:00Z")
    val until = Instant.parse("2026-11-01T09:00:00Z")
    for ((tag, later) in listOf("conflict" to "candidate", "fixed" to "fixed")) {
        val report = writeReport(Files.createDirectory(dir.resolve(tag)), source.getValue("baseline"), source.getValue(later), from, until)
        val expected = if (tag == "conflict") "CONFLICT" else "CHANGE"
        check(report.path("status").asText() == expected) { "Unexpected demo verdict in $tag" }
    }
}

private fun <T> publish(dest: Path, writer: (Path) -> T): T {
    val target = dest.toAbsolutePath().normalize()
    require(target.parent != null && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Choose a new output directory: $target" }
    Files.createDirectories(target.parent)
    val staging = Files.createTempDirectory(target.parent, ".cronfold-")
    try {
        val result = writer(staging)
        Files.move(staging, target)
        return result
    } finally {
        if (Files.exists(staging)) Files.walk(staging).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
