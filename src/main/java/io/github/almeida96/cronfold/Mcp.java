package io.github.almeida96.cronfold;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** A stdio MCP session bound to one report loaded by the operator. */
public final class Mcp {
    private static final ObjectMapper JSON = Wire.json;
    private static final String LATEST = "2025-11-25";
    private static final int MAX_FRAME = 1024 * 1024;
    private static final int PAGE_LIMIT = 100;
    private static final List<String> TOOLS = List.of("schedule_summary", "job_detail", "list_changes", "list_conflicts");
    private final JsonNode report;
    private final Map<String, JsonNode> jobs = new HashMap<>();
    private boolean negotiated;
    private boolean ready;

    private static final Set<String> CHANGE_KINDS = Set.of("added", "removed");
    private static final Set<String> CONFLICT_KINDS = Set.of("introduced", "existing", "resolved");

    private Mcp(JsonNode report) {
        if (report == null || !report.path("formatVersion").isInt() || report.get("formatVersion").intValue() != 1
                || !Set.of("PASS", "CHANGE", "CONFLICT").contains(report.path("status").asText(""))
                || !report.at("/window/start").isTextual() || !report.at("/window/end").isTextual()
                || !report.path("jobs").isArray() || !report.path("changes").isArray() || !report.path("conflicts").isArray()) {
            throw invalid("Expected a CronFold version 1 report");
        }
        for (JsonNode job : report.get("jobs")) {
            String id = string(job, "id");
            if (!job.path("beforeEvents").isArray() || !job.path("afterEvents").isArray()
                    || jobs.putIfAbsent(id, job) != null) throw invalid("Incomplete or duplicate job in report");
        }
        for (JsonNode change : report.get("changes")) {
            if (!jobs.containsKey(string(change, "job")) || !CHANGE_KINDS.contains(string(change, "kind"))) {
                throw invalid("Invalid change in report");
            }
        }
        for (JsonNode conflict : report.get("conflicts")) {
            if (!CONFLICT_KINDS.contains(string(conflict, "classification")) || !conflict.path("resource").isTextual()) {
                throw invalid("Invalid resource conflict in report");
            }
        }
        this.report = report;
    }

    public static void serve(JsonNode report, InputStream input, OutputStream output) throws IOException {
        var session = new Mcp(report);
        var stream = new BufferedInputStream(input);
        byte[] frame = new byte[MAX_FRAME];
        int used = 0;
        boolean oversized = false;
        int next;
        while ((next = stream.read()) != -1) {
            if (next == '\n') {
                session.respond(frame, used, oversized, output);
                used = 0;
                oversized = false;
            } else if (used < MAX_FRAME) {
                frame[used++] = (byte) next;
            } else {
                oversized = true;
            }
        }
        if (used != 0 || oversized) session.respond(frame, used, oversized, output);
    }

    private void respond(byte[] frame, int used, boolean oversized, OutputStream output) throws IOException {
        ObjectNode reply;
        if (oversized) {
            reply = error(null, -32700, "Message exceeds 1 MiB");
        } else {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(frame, 0, used)).toString();
                JsonNode request = JSON.readTree(text);
                reply = request == null || request.isMissingNode()
                        ? error(null, -32700, "Empty JSON message") : handle(request);
            } catch (JsonProcessingException | CharacterCodingException invalid) {
                reply = error(null, -32700, "Invalid UTF-8 JSON message");
            }
        }
        if (reply == null) return;
        byte[] bytes = JSON.writeValueAsBytes(reply);
        if (bytes.length > MAX_FRAME) {
            bytes = JSON.writeValueAsBytes(error(reply.get("id"), -32603, "Result exceeds 1 MiB. Request a smaller page."));
        }
        output.write(bytes);
        output.write('\n');
        output.flush();
    }

    private ObjectNode handle(JsonNode request) {
        JsonNode id = request.get("id");
        if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").textValue())
                || !request.path("method").isTextual()
                || (id != null && !(id.isIntegralNumber() || id.isTextual()))
                || (id != null && id.toString().length() > 1024)) {
            return error(null, -32600, "Invalid JSON-RPC request");
        }
        String method = request.path("method").textValue();
        if (id == null) {
            if ("notifications/initialized".equals(method) && negotiated) ready = true;
            return null;
        }
        JsonNode params = request.has("params") ? request.get("params") : JSON.createObjectNode();
        if (!params.isObject()) return error(id, -32602, "params must be an object");
        try {
            if ("ping".equals(method)) return success(id, JSON.createObjectNode());
            if ("initialize".equals(method)) {
                if (negotiated) return error(id, -32600, "Session already initialized");
                String version = string(params, "protocolVersion");
                if (!params.path("capabilities").isObject()) throw invalid("Missing client capabilities");
                string(params.path("clientInfo"), "name");
                string(params.path("clientInfo"), "version");
                ObjectNode result = JSON.createObjectNode();
                result.put("protocolVersion", "2025-06-18".equals(version) ? version : LATEST);
                result.putObject("serverInfo").put("name", "cronfold").put("version", "0.1.0");
                result.putObject("capabilities").putObject("tools").put("listChanged", false);
                negotiated = true;
                return success(id, result);
            }
            if (!ready) return error(id, -32600, "Complete initialization first");
            if ("tools/list".equals(method)) {
                fields(params, Set.of("_meta"));
                return success(id, inventory());
            }
            if (!"tools/call".equals(method)) return error(id, -32601, "Method not found");
            fields(params, Set.of("name", "arguments", "_meta"));
            JsonNode args = params.has("arguments") ? params.get("arguments") : JSON.createObjectNode();
            if (!args.isObject()) throw invalid("arguments must be an object");
            String name = string(params, "name");
            if (!TOOLS.contains(name)) return error(id, -32602, "Unknown tool");
            ObjectNode data;
            try {
                data = call(name, args);
            } catch (IllegalArgumentException e) {
                ObjectNode failure = JSON.createObjectNode().put("isError", true);
                failure.putArray("content").addObject().put("type", "text").put("text", e.getMessage());
                return success(id, failure);
            }
            ObjectNode result = JSON.createObjectNode().put("isError", false);
            result.set("structuredContent", data);
            result.putArray("content").addObject().put("type", "text").put("text", data.toString());
            return success(id, result);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }
    }

    private ObjectNode call(String name, JsonNode args) {
        int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = integer(args, "limit", 25, 1, PAGE_LIMIT);
        ObjectNode result = JSON.createObjectNode();
        switch (name) {
            case "schedule_summary" -> {
                fields(args, Set.of("offset", "limit"));
                copyFields(report, result, "formatVersion", "status", "window", "stats", "engine", "inputs");
                result.put("jobCount", jobs.size()).put("changeCount", report.get("changes").size())
                        .put("conflictCount", report.get("conflicts").size());
                JsonNode rows = report.get("jobs");
                ArrayNode page = result.putArray("jobs");
                int start = Math.min(rows.size(), offset);
                int end = start + Math.min(limit, rows.size() - start);
                for (int i = start; i < end; i++) {
                    copyFields(rows.get(i), page.addObject(), "id", "beforeCount", "afterCount", "added", "removed",
                            "unchanged", "scheduleChanged");
                }
                return pagination(result, offset, page.size(), rows.size());
            }
            case "job_detail" -> {
                fields(args, Set.of("job", "side", "offset", "limit"));
                JsonNode job = jobs.get(string(args, "job"));
                if (job == null) throw invalid("Unknown job");
                String side = option(args, "side", Set.of("before", "after"), "after");
                copyFields(job, result, "id", "before", "after", "beforeCount", "afterCount", "added", "removed",
                        "unchanged", "scheduleChanged");
                result.put("side", side).put("beforeEventCount", job.get("beforeEvents").size())
                        .put("afterEventCount", job.get("afterEvents").size());
                return page(result, "events", job.get(side + "Events"), row -> true, offset, limit);
            }
            case "list_changes" -> {
                fields(args, Set.of("job", "kind", "offset", "limit"));
                String job = args.has("job") ? string(args, "job") : null;
                if (job != null && !jobs.containsKey(job)) throw invalid("Unknown job");
                String kind = option(args, "kind", CHANGE_KINDS, null);
                return page(result, "changes", report.get("changes"),
                        row -> matches(row, "job", job) && matches(row, "kind", kind), offset, limit);
            }
            case "list_conflicts" -> {
                fields(args, Set.of("resource", "classification", "offset", "limit"));
                String resource = args.has("resource") ? string(args, "resource") : null;
                String kind = option(args, "classification", CONFLICT_KINDS, null);
                return page(result, "conflicts", report.get("conflicts"),
                        row -> matches(row, "resource", resource) && matches(row, "classification", kind), offset, limit);
            }
            default -> throw invalid("Unknown tool");
        }
    }

    private static boolean matches(JsonNode row, String key, String expected) {
        return expected == null || expected.equals(row.path(key).textValue());
    }

    private static String option(JsonNode args, String key, Set<String> allowed, String fallback) {
        if (!args.has(key)) return fallback;
        String value = string(args, key);
        if (!allowed.contains(value)) throw invalid("Unknown value for " + key);
        return value;
    }

    private static ObjectNode page(ObjectNode result, String key, JsonNode rows, Predicate<JsonNode> filter,
                                   int offset, int limit) {
        ArrayNode items = result.putArray(key);
        int total = 0;
        for (JsonNode row : rows) {
            if (filter.test(row) && total++ >= offset && items.size() < limit) items.add(row);
        }
        return pagination(result, offset, items.size(), total);
    }

    private static ObjectNode pagination(ObjectNode result, int offset, int count, int total) {
        result.put("offset", offset).put("total", total);
        if (offset < total - count) result.put("nextOffset", offset + count);
        else result.putNull("nextOffset");
        return result;
    }

    private static ObjectNode inventory() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode list = result.putArray("tools");
        for (String name : TOOLS) {
            ObjectNode tool = list.addObject().put("name", name);
            tool.put("description", switch (name) {
                case "schedule_summary" -> "Read the projection window, verdict and counts. Page through job IDs and run counts.";
                case "job_detail" -> "Read one job's configurations and counts. Page through its clock-transition events for one side, before or after.";
                case "list_changes" -> "Page through added and removed scheduled instants. Optional filters select an exact job ID and change kind.";
                default -> "Page through resource collisions intersecting the comparison window. Optional filters select an exact resource and conflict classification.";
            });
            tool.putObject("annotations").put("readOnlyHint", true).put("destructiveHint", false)
                    .put("idempotentHint", true).put("openWorldHint", false);
            tool.putObject("outputSchema").put("type", "object");
            ObjectNode schema = tool.putObject("inputSchema").put("type", "object").put("additionalProperties", false);
            ObjectNode properties = schema.putObject("properties");
            properties.putObject("offset").put("type", "integer").put("minimum", 0).put("maximum", Integer.MAX_VALUE);
            properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", PAGE_LIMIT);
            switch (name) {
                case "job_detail" -> {
                    properties.putObject("job").put("type", "string").put("minLength", 1);
                    properties.putObject("side").put("type", "string").putArray("enum").add("before").add("after");
                    schema.putArray("required").add("job");
                }
                case "list_changes" -> {
                    properties.putObject("job").put("type", "string").put("minLength", 1);
                    properties.putObject("kind").put("type", "string").putArray("enum").add("added").add("removed");
                }
                case "list_conflicts" -> {
                    properties.putObject("resource").put("type", "string").put("minLength", 1);
                    properties.putObject("classification").put("type", "string").putArray("enum")
                            .add("introduced").add("existing").add("resolved");
                }
            }
        }
        return result;
    }

    private static void copyFields(JsonNode source, ObjectNode destination, String... keys) {
        for (String key : keys) if (source.has(key)) destination.set(key, source.get(key));
    }

    private static String string(JsonNode params, String key) {
        JsonNode value = params.path(key);
        if (!value.isTextual() || value.textValue().isEmpty()) throw invalid("Expected a nonempty string for " + key);
        return value.textValue();
    }

    private static int integer(JsonNode node, String key, int fallback, int min, int max) {
        if (!node.has(key)) return fallback;
        JsonNode value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw invalid(key + " must be an integer between " + min + " and " + max);
        return value.intValue();
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw invalid("Unexpected parameter");
        });
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static ObjectNode envelope(JsonNode id) {
        ObjectNode reply = JSON.createObjectNode().put("jsonrpc", "2.0");
        return reply.set("id", id);
    }

    private static ObjectNode success(JsonNode id, ObjectNode data) {
        return envelope(id).set("result", data);
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode reply = envelope(id);
        reply.putObject("error").put("code", code).put("message", message);
        return reply;
    }
}
