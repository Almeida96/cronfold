package io.github.almeida96.cronfold

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

data class Plan(val jobs: List<Job>)
data class Job(val id: String, val schedule: Schedule, val durationMinutes: Int, val resource: String?)
internal data class SourcePlan(val plan: Plan, val file: String, val sha256: String)

object Wire {
    @JvmField val json = ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
}

internal object PlanFiles {
    private val jobIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val resourcePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")

    fun bytes(path: Path, limit: Int): ByteArray {
        require(Files.isRegularFile(path)) { "Expected a regular file: $path" }
        return Files.newInputStream(path).use { stream ->
            stream.readNBytes(limit + 1).also { require(it.size <= limit) { "File exceeds $limit bytes: $path" } }
        }
    }

    fun load(path: Path): SourcePlan {
        val bytes = bytes(path, 1024 * 1024)
        val root = Wire.json.readTree(bytes)
        require(root != null && root.isObject) { "Plan must be a JSON object" }
        fields(root, setOf("version", "jobs"))
        require(root.path("version").isInt && root.path("version").intValue() == 1) { "Expected plan version 1" }
        val jobs = root.path("jobs")
        require(jobs.isArray && jobs.size() <= 128) { "jobs must be an array of at most 128 entries" }
        val ids = mutableSetOf<String>()
        val parsed = jobs.mapIndexed { index, item ->
            require(item.isObject) { "jobs[$index] must be an object" }
            fields(item, setOf("id", "cron", "zone", "gap", "overlap", "durationMinutes", "resource"))
            val id = text(item, "id")
            require(jobIdPattern.matches(id) && ids.add(id)) { "Invalid or duplicate job id: $id" }
            val duration = item.path("durationMinutes")
            require(duration.isInt && duration.intValue() in 1..1440) { "$id: durationMinutes must be 1..1440" }
            val resource = if (item.has("resource")) text(item, "resource") else null
            require(resource == null || resourcePattern.matches(resource)) { "$id: invalid resource name" }
            val rule = Schedule(text(item, "cron"), text(item, "zone"),
                if (item.has("gap")) text(item, "gap") else "skip",
                if (item.has("overlap")) text(item, "overlap") else "both")
            Job(id, rule, duration.intValue(), resource)
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
        return SourcePlan(Plan(parsed), path.fileName.toString(), HexFormat.of().formatHex(sha))
    }

    private fun fields(node: JsonNode, allowed: Set<String>) {
        val unknown = node.fieldNames().asSequence().firstOrNull { it !in allowed }
        require(unknown == null) { "Unknown plan field: $unknown" }
    }

    private fun text(node: JsonNode, key: String): String {
        val value = node.path(key)
        require(value.isTextual && value.textValue().isNotBlank() && value.textValue().length <= 256) {
            "Expected a nonempty string of at most 256 characters for $key"
        }
        return value.textValue()
    }
}
