package io.github.almeida96.cronfold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class McpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    private static final String REPORT = """
            {"formatVersion":1,"status":"CONFLICT","window":{"start":"2025-01-01T00:00:00Z","end":"2026-01-01T00:00:00Z"},
             "jobs":[{"id":"ledger","beforeCount":3,"afterCount":4,"added":1,"removed":0,"unchanged":3,"scheduleChanged":true,
                      "before":{"zone":"UTC"},"after":{"zone":"America/New_York"},"beforeEvents":[],
                      "afterEvents":[{"local":"2025-03-09T02:30","type":"gap","emitted":[]},
                                     {"local":"2025-11-02T01:30","type":"overlap","emitted":["2025-11-02T05:30:00Z","2025-11-02T06:30:00Z"]}]},
                     {"id":"invoice","beforeCount":1,"afterCount":0,"added":0,"removed":1,"unchanged":0,"scheduleChanged":true,
                      "beforeEvents":[],"afterEvents":[]}],
             "changes":[{"job":"ledger","kind":"added","instant":"2025-11-02T06:30:00Z"},
                        {"job":"invoice","kind":"removed","instant":"2025-11-03T06:30:00Z"}],
             "conflicts":[{"id":"c1","resource":"db","left":"ledger","right":"ledger","classification":"introduced"},
                          {"id":"c2","resource":"fs","left":"invoice","right":"ledger","classification":"resolved"}]}
            """;
    private static String request(String method, ObjectNode params) {
        return JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "request").put("method", method)
                .set("params", params).toString();
    }

    private static String init(String version) {
        ObjectNode params = JSON.createObjectNode().put("protocolVersion", version);
        params.putObject("clientInfo").put("name", "test").put("version", "1");
        params.putObject("capabilities");
        return request("initialize", params);
    }

    private static String call(String tool, String args) throws Exception {
        ObjectNode params = JSON.createObjectNode().put("name", tool);
        params.set("arguments", JSON.readTree(args));
        return request("tools/call", params);
    }

    private static List<JsonNode> exchange(JsonNode report, byte[] frames) throws Exception {
        var out = new ByteArrayOutputStream();
        Mcp.serve(report, new ByteArrayInputStream(frames), out);
        var messages = new ArrayList<JsonNode>();
        for (String frame : out.toString(UTF_8).lines().toList()) messages.add(JSON.readTree(frame));
        return messages;
    }

    private static List<JsonNode> session(JsonNode report, String... frames) throws Exception {
        String batch = init("2025-11-25") + "\n" + INITIALIZED + "\n" + String.join("\n", frames) + "\n";
        var messages = exchange(report, batch.getBytes(UTF_8));
        assertEquals("2025-11-25", messages.removeFirst().at("/result/protocolVersion").asText());
        return messages;
    }

    @Test void negotiatesVersionAndWaitsForClientReadiness() throws Exception {
        String inventory = request("tools/list", JSON.createObjectNode());
        var frames = List.of(inventory, init("2025-06-18"), inventory, INITIALIZED,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}", inventory, init("2025-06-18"));
        var messages = exchange(JSON.readTree(REPORT), String.join("\r\n", frames).getBytes(UTF_8));
        assertEquals(5, messages.size());
        assertEquals(-32600, messages.get(0).at("/error/code").asInt());
        assertEquals("2025-06-18", messages.get(1).at("/result/protocolVersion").asText());
        assertEquals(-32600, messages.get(2).at("/error/code").asInt());
        var tools = messages.get(3).at("/result/tools");
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> {
            names.add(tool.path("name").asText());
            assertTrue(tool.at("/annotations/readOnlyHint").booleanValue());
            assertFalse(tool.at("/annotations/openWorldHint").booleanValue());
        });
        assertEquals(List.of("schedule_summary", "job_detail", "list_changes", "list_conflicts"), names);
        assertEquals(-32600, messages.get(4).at("/error/code").asInt());
        var fallback = exchange(JSON.readTree(REPORT), init("future").getBytes(UTF_8));
        assertEquals("2025-11-25", fallback.getFirst().at("/result/protocolVersion").asText());
    }

    @Test void pagesTheJobInventoryAndBothSidesOfItsClockEvents() throws Exception {
        JsonNode report = JSON.readTree(REPORT);
        var replies = session(report, call("schedule_summary", "{\"limit\":1}"),
                call("schedule_summary", "{\"offset\":1}"),
                call("job_detail", "{\"job\":\"ledger\",\"limit\":1}"),
                call("job_detail", "{\"job\":\"ledger\",\"offset\":1}"),
                call("job_detail", "{\"job\":\"ledger\",\"side\":\"before\"}"));
        JsonNode summary = data(replies.getFirst());
        assertEquals(2, summary.get("changeCount").intValue());
        assertEquals(2, summary.get("conflictCount").intValue());
        assertEquals("ledger", summary.at("/jobs/0/id").asText());
        assertFalse(summary.at("/jobs/0").has("afterEvents"));
        assertEquals(1, summary.get("nextOffset").intValue());
        assertEquals("invoice", data(replies.get(1)).at("/jobs/0/id").asText());
        JsonNode job = data(replies.get(2));
        assertEquals(report.at("/jobs/0/after"), job.get("after"));
        assertEquals("after", job.get("side").asText());
        assertEquals(2, job.get("afterEventCount").asInt());
        assertFalse(job.has("afterEvents"));
        assertEquals(1, job.get("nextOffset").intValue());
        assertEquals(report.at("/jobs/0/afterEvents/1"), data(replies.get(3)).at("/events/0"));
        assertTrue(data(replies.get(3)).get("nextOffset").isNull());
        assertTrue(data(replies.get(4)).get("events").isEmpty());
    }

    @Test void filtersChangesAndConflictsBeforeApplyingOffsets() throws Exception {
        JsonNode report = JSON.readTree(REPORT);
        var replies = session(report, call("list_changes", "{\"kind\":\"removed\",\"job\":\"invoice\"}"),
                call("list_conflicts", "{\"offset\":1,\"limit\":1}"),
                call("list_conflicts", "{\"resource\":\"fs\",\"classification\":\"resolved\"}"),
                call("list_changes", "{\"offset\":2147483647}"),
                call("list_conflicts", "{\"classification\":\"existing\"}"));
        assertEquals(1, data(replies.getFirst()).get("total").asInt());
        assertEquals(report.at("/changes/1"), data(replies.getFirst()).at("/changes/0"));
        assertEquals(report.at("/conflicts/1"), data(replies.get(1)).at("/conflicts/0"));
        assertEquals(1, data(replies.get(2)).get("total").intValue());
        assertEquals(report.at("/conflicts/1"), data(replies.get(2)).at("/conflicts/0"));
        assertTrue(data(replies.get(3)).get("changes").isEmpty());
        assertTrue(data(replies.get(3)).get("nextOffset").isNull());
        assertEquals(0, data(replies.get(4)).get("total").asInt());
        assertEquals(data(replies.get(2)), JSON.readTree(replies.get(2).at("/result/content/0/text").asText()));
    }

    private static JsonNode data(JsonNode message) {
        assertFalse(message.at("/result/isError").asBoolean(), message::toString);
        return message.at("/result/structuredContent");
    }

    @Test void rejectsUnknownToolsAndInvalidFiltersWithoutChangingTheReport() throws Exception {
        JsonNode report = JSON.readTree(REPORT);
        var replies = session(report, call("run_job", "{}"), request("report/load", JSON.createObjectNode()),
                call("schedule_summary", "{\"path\":\"other.json\"}"), call("list_changes", "{\"limit\":101}"),
                call("job_detail", "{\"side\":\"before\"}"), call("job_detail", "{\"job\":\"Ledger\"}"),
                call("job_detail", "{\"job\":\"ledger\",\"side\":\"other\"}"),
                call("list_changes", "{\"kind\":\"modified\"}"), call("list_conflicts", "{\"classification\":null}"),
                call("list_changes", "{\"offset\":2147483648}"), request("ping", JSON.createObjectNode()));
        assertEquals(-32602, replies.get(0).at("/error/code").asInt());
        assertEquals(-32601, replies.get(1).at("/error/code").asInt());
        for (JsonNode reply : replies.subList(2, replies.size() - 1)) assertTrue(reply.at("/result/isError").asBoolean());
        assertEquals(JSON.createObjectNode(), replies.getLast().get("result"));
        assertEquals(JSON.readTree(REPORT), report);
    }

    @Test void rejectsInvalidVersionAndInvalidJobReferencesOnStartup() throws Exception {
        ObjectNode report = (ObjectNode) JSON.readTree(REPORT);
        assertThrows(IllegalArgumentException.class, () -> exchange(report.put("formatVersion", "1"), new byte[0]));
        report.put("formatVersion", 1);
        ((ObjectNode) report.at("/changes/0")).put("job", "missing");
        assertThrows(IllegalArgumentException.class, () -> exchange(report, new byte[0]));
    }

    @Test void rejectsMalformedAndOversizedFramesThenResynchronizes() throws Exception {
        var input = new ByteArrayOutputStream();
        input.write((" ".repeat(1024 * 1024 + 1) + "\n").getBytes(UTF_8));
        input.write(new byte[] {(byte) 0xc3, 10});
        input.write("{}{}\n{\"jsonrpc\":\"1\",\"jsonrpc\":\"2\"}\n".getBytes(UTF_8));
        input.write(request("ping", JSON.createObjectNode()).getBytes(UTF_8));
        var messages = exchange(JSON.readTree(REPORT), input.toByteArray());
        assertEquals(5, messages.size());
        for (JsonNode message : messages.subList(0, 4)) {
            assertEquals(-32700, message.at("/error/code").intValue());
            assertTrue(message.get("id").isNull());
        }
        assertEquals("request", messages.getLast().path("id").textValue());
    }
}
