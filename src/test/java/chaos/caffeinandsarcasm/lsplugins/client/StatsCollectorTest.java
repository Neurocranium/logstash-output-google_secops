package chaos.caffeinandsarcasm.lsplugins.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class StatsCollectorTest {

    @Test
    public void buildsCompactJsonWithAggregatesAndOrderedCalls() {
        StatsCollector collector = new StatsCollector(true);
        collector.recordCall("PAN_FIREWALL", 20, 20_000, 200, 80);
        collector.recordCall("PAN_FIREWALL", 18, 17_990, 202, 54);
        collector.recordCall("FORTINET_FIREWALL", 19, 19_456, 200, 104);

        Map<String, List<?>> groups = new LinkedHashMap<>();
        groups.put("PAN_FIREWALL", items(38));
        groups.put("FORTINET_FIREWALL", items(19));

        String json = collector.buildSummaryJson(groups);
        JsonObject summary = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(57, summary.get("events").getAsInt());
        assertEquals(57_446, summary.get("bytes").getAsLong());

        JsonArray logTypes = summary.getAsJsonArray("log_types");
        assertEquals(2, logTypes.size());
        JsonObject pan = logTypes.get(0).getAsJsonObject();
        assertEquals("PAN_FIREWALL", pan.get("name").getAsString());
        assertEquals(38, pan.get("events").getAsInt());
        assertEquals(37_990, pan.get("bytes").getAsLong());

        JsonArray calls = pan.getAsJsonArray("calls");
        assertEquals(2, calls.size());
        assertCall(calls.get(0).getAsJsonObject(), 20, 20_000, 200, 80);
        assertCall(calls.get(1).getAsJsonObject(), 18, 17_990, 202, 54);

        assertFalse(json.contains("\n"));
        assertFalse(json.contains("[google_secops]"));
        assertFalse(json.contains("KB"));
    }

    @Test
    public void safelyEscapesLogTypeNames() {
        StatsCollector collector = new StatsCollector(true);
        collector.recordCall("TYPE_\"A\\B\n", 1, 10, 200, 1);

        Map<String, List<?>> groups = new LinkedHashMap<>();
        groups.put("TYPE_\"A\\B\n", items(1));

        String json = collector.buildSummaryJson(groups);
        String name = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("log_types").get(0).getAsJsonObject()
                .get("name").getAsString();

        assertEquals("TYPE_\"A\\B\n", name);
    }

    @Test
    public void suppressesDisabledOrEmptyStatistics() {
        StatsCollector disabled = new StatsCollector(false);
        disabled.recordCall("TYPE", 1, 10, 200, 1);
        assertNull(disabled.buildSummaryJson(Map.of("TYPE", items(1))));

        StatsCollector empty = new StatsCollector(true);
        assertNull(empty.buildSummaryJson(Map.of("TYPE", items(1))));
    }

    private static void assertCall(JsonObject call, int events, long bytes, int status, long durationMs) {
        assertEquals(events, call.get("events").getAsInt());
        assertEquals(bytes, call.get("bytes").getAsLong());
        assertEquals(status, call.get("status").getAsInt());
        assertEquals(durationMs, call.get("duration_ms").getAsLong());
    }

    private static List<?> items(int count) {
        return java.util.Collections.nCopies(count, new Object());
    }
}
