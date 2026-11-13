package io.github.almeida96.cronfold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Convert exported Kubernetes calendar definitions. Uses only fields needed for projection. */
internal object Kube {
    private const val durationKey = "cronfold.dev/duration-minutes"
    private const val resourceKey = "cronfold.dev/resource"
    private val label = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
    private val resourceName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
    private val macros = mapOf(
        "@hourly" to "0 * * * *", "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *",
        "@weekly" to "0 0 * * 0", "@monthly" to "0 0 1 * *",
        "@yearly" to "0 0 1 1 *", "@annually" to "0 0 1 1 *"
    )

    fun convert(root: JsonNode, defaultDuration: Int, defaultZone: String?, gap: String, overlap: String): ObjectNode {
        require(defaultDuration in 1..1440) { "Default duration must be 1..1440 minutes" }
        CalendarEngine.validate(Schedule("* * * * *", defaultZone ?: "UTC", gap, overlap))
        val result = Wire.json.createObjectNode()
        val jobs = result.putObject("plan").put("version", 1).putArray("jobs")
        val notes = result.putArray("notes")
        notes.add("Run times are projections. Job creation delays, missed runs, retries and termination depend on the controller and workload.")
        notes.add("Assumed duration is $defaultDuration minutes unless overridden by $durationKey. Clock policies are gap=$gap and overlap=$overlap.")
        val active = sortedMapOf<String, ObjectNode>()
        val ids = mutableSetOf<String>()
        for (item in resources(root)) {
            val metadata = item.path("metadata")
            val name = text(metadata, "name")
            require(name.length <= 52 && name.split('.').all(label::matches)) { "Invalid Kubernetes CronJob name: $name" }
            val namespace = optional(metadata, "namespace") ?: "default"
            require(namespace.length <= 63 && label.matches(namespace)) { "Invalid Kubernetes namespace: $namespace" }
            val id = "$namespace.$name"
            require(ids.add(id)) { "Duplicate CronJob: $id" }
            val spec = item.path("spec")
            require(spec.isObject) { "$id: expected spec object" }
            val suspended = spec.path("suspend").let {
                require(it.isMissingNode || it.isBoolean) { "$id: suspend must be boolean" }
                it.asBoolean(false)
            }
            val policy = optional(spec, "concurrencyPolicy") ?: "Allow"
            require(policy in setOf("Allow", "Forbid", "Replace")) { "$id: invalid concurrencyPolicy '$policy'" }
            val deadline = spec.get("startingDeadlineSeconds")
            require(deadline == null || deadline.isIntegralNumber && deadline.canConvertToLong() && deadline.longValue() >= 0) {
                "$id: startingDeadlineSeconds must be a nonnegative integer"
            }
            val declaredZone = optional(spec, "timeZone")
            require(suspended || declaredZone != null || defaultZone != null) {
                "$id has no spec.timeZone. Supply --controller-zone with the controller's time zone"
            }
            val zone = declaredZone ?: defaultZone ?: "UTC"
            val sourceCron = text(spec, "schedule")
            val cron = normalize(sourceCron)
            CalendarEngine.validate(Schedule(cron, zone, gap, overlap))

            val annotations = metadata.path("annotations")
            require(annotations.isObject || annotations.isMissingNode) { "$id: annotations must be an object" }
            val duration = optional(annotations, durationKey)?.let { value ->
                require(value.matches(Regex("[0-9]{1,4}")) && value.toInt() in 1..1440) { "$id: invalid $durationKey" }
                value.toInt()
            } ?: defaultDuration
            val shared = optional(annotations, resourceKey)
            require(shared == null || resourceName.matches(shared)) { "$id: invalid $resourceKey" }
            if (suspended) {
                notes.add("$id excluded because spec.suspend is true.")
                continue
            }
            if (declaredZone == null) notes.add("$id uses the supplied controller zone $zone.")
            if (cron != sourceCron) notes.add("$id calendar expression normalized from '$sourceCron' to '$cron'.")
            if (policy != "Allow") {
                notes.add("$id uses concurrencyPolicy=$policy. The plan shows scheduled starts before the controller applies this policy.")
            }
            if (deadline != null) notes.add("$id has startingDeadlineSeconds=${deadline.longValue()}. Controller catch-up is outside this projection.")
            val job = Wire.json.createObjectNode().put("id", id).put("cron", cron).put("zone", zone)
                .put("gap", gap).put("overlap", overlap).put("durationMinutes", duration)
            if (shared != null) job.put("resource", shared)
            active[id] = job
        }
        active.values.forEach(jobs::add)
        return result
    }

    private fun resources(root: JsonNode): List<JsonNode> {
        val kind = text(root, "kind")
        val api = text(root, "apiVersion")
        if (kind == "CronJob" && api == "batch/v1") return listOf(root)
        val typed = kind == "CronJobList" && api == "batch/v1"
        require(typed || kind == "List" && api == "v1") { "Expected batch/v1 CronJob, batch/v1 CronJobList or v1 List" }
        val cursor = root.path("metadata").path("continue")
        require(cursor.isMissingNode || cursor.isTextual && cursor.textValue().isEmpty()) {
            "Expected a complete Kubernetes list. Fetch the remaining pages before importing."
        }
        val items = root.path("items")
        require(items.isArray && items.size() <= 128) { "Expected items array containing at most 128 CronJobs" }
        return items.mapIndexed { index, item ->
            require(item.isObject) { "items[$index] must be an object" }
            val itemKind = optional(item, "kind")
            val version = optional(item, "apiVersion")
            // Typed Kubernetes lists may omit TypeMeta on each object. Generic Lists have no such guarantee.
            require((itemKind == "CronJob" || typed && itemKind == null) &&
                    (version == "batch/v1" || typed && version == null)) { "items[$index] must be a batch/v1 CronJob" }
            item
        }
    }

    private fun normalize(expression: String): String {
        require(expression.length <= 256 && expression.none { it == '\r' || it == '\n' }) { "Invalid Kubernetes schedule" }
        macros[expression]?.let { return it }
        val fields = expression.trim(' ', '\t').split(Regex("[ \\t]+"))
        require(fields.size == 5) { "Expected a five-field Kubernetes calendar or a supported macro: $expression" }
        val min = listOf(0, 0, 1, 1, 0)
        val max = listOf(59, 23, 31, 12, 6)
        return fields.mapIndexed { i, field ->
            var unrestricted = false
            val terms = field.split(',').map { term ->
                val parts = term.split('/')
                require(parts.size <= 2) { "Invalid step in Kubernetes schedule: $term" }
                val stride = parts.getOrNull(1)?.let { raw ->
                    require(raw.isNotEmpty() && raw.all { it in '0'..'9' }) { "Invalid step: $term" }
                    requireNotNull(raw.toIntOrNull()?.takeIf { it > 0 }) { "Invalid step: $term" }
                }
                val base = parts[0]
                val wildcard = base == "*" || base == "?"
                if (!wildcard && i == 4) {
                    require(base.split('-').none { it.toIntOrNull()?.let { day -> day !in 0..6 } == true }) {
                        "Kubernetes uses 0 through 6 for day-of-week"
                    }
                }
                // robfig/cron sets the day star flag when ANY list term is an unstepped wildcard.
                // */N for N>1 clears the flag. Preserve that distinction in CronFold's grammar.
                if (wildcard && (stride == null || stride == 1)) unrestricted = true
                when {
                    wildcard && (stride == null || stride == 1) -> "*"
                    wildcard -> "${min[i]}-${max[i]}/$stride"
                    stride != null && '-' !in base -> "$base-${max[i]}/$stride"
                    else -> term
                }
            }
            val normalized = terms.joinToString(",")
            if (unrestricted && terms.size > 1) {
                // Validate even the redundant terms before folding the wildcard list.
                val probe = arrayOf("0", "0", "*", "*", "*")
                probe[i] = normalized
                CalendarEngine.validate(Schedule(probe.joinToString(" "), "UTC"))
            }
            if (unrestricted) "*" else normalized
        }.joinToString(" ")
    }

    private fun optional(node: JsonNode, key: String): String? = if (node.has(key)) text(node, key) else null

    private fun text(node: JsonNode, key: String): String {
        val field = node.path(key)
        require(field.isTextual && field.textValue().length in 1..256 && field.textValue().isNotBlank()) {
            "Expected a nonempty string of at most 256 characters for $key"
        }
        return field.textValue()
    }
}
