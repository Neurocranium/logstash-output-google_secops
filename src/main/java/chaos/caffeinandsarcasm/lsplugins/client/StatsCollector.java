package chaos.caffeinandsarcasm.lsplugins.client;

import com.google.gson.Gson;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StatsCollector {

    private static final Gson GSON = new Gson();

    private final Logger logger;
    private final boolean enabled;
    private final List<ApiCallStat> calls = new ArrayList<>();

    public StatsCollector(boolean enabled, Logger logger) {
        this.enabled = enabled;
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
    }

    public void recordCall(String logType, int eventCount, long payloadBytes, int statusCode, long durationMs) {
        if (!enabled) {
            return;
        }
        calls.add(new ApiCallStat(logType, eventCount, payloadBytes, statusCode, durationMs));
    }

    public void printSummary(Map<String, List<?>> groups) {
        String summary = buildSummaryJson(groups);
        if (summary != null) {
            logger.info(summary);
        }
    }

    String buildSummaryJson(Map<String, List<?>> groups) {
        if (!enabled || calls.isEmpty()) {
            return null;
        }

        int totalEvents = 0;
        for (List<?> list : groups.values()) {
            totalEvents += list.size();
        }

        Map<String, List<ApiCallStat>> byType = new LinkedHashMap<>();
        long totalBytes = 0;
        for (ApiCallStat stat : calls) {
            byType.computeIfAbsent(stat.logType, k -> new ArrayList<>()).add(stat);
            totalBytes += stat.payloadBytes;
        }

        List<Map<String, Object>> logTypes = new ArrayList<>();
        for (Map.Entry<String, List<ApiCallStat>> entry : byType.entrySet()) {
            List<ApiCallStat> typeCalls = entry.getValue();
            int typeEvents = 0;
            long typeBytes = 0;
            List<Map<String, Object>> callObjects = new ArrayList<>();
            for (ApiCallStat stat : typeCalls) {
                typeEvents += stat.eventCount;
                typeBytes += stat.payloadBytes;

                Map<String, Object> call = new LinkedHashMap<>();
                call.put("events", stat.eventCount);
                call.put("bytes", stat.payloadBytes);
                call.put("status", stat.statusCode);
                call.put("duration_ms", stat.durationMs);
                callObjects.add(call);
            }

            Map<String, Object> logType = new LinkedHashMap<>();
            logType.put("name", entry.getKey());
            logType.put("events", typeEvents);
            logType.put("bytes", typeBytes);
            long averageEventBytes = typeEvents == 0
                    ? 0
                    : typeBytes / typeEvents + (typeBytes % typeEvents == 0 ? 0 : 1);
            logType.put("avg_event_bytes", averageEventBytes);
            logType.put("calls", callObjects);
            logTypes.add(logType);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("events", totalEvents);
        summary.put("bytes", totalBytes);
        summary.put("log_types", logTypes);
        return GSON.toJson(summary);
    }

    private static class ApiCallStat {
        final String logType;
        final int eventCount;
        final long payloadBytes;
        final int statusCode;
        final long durationMs;

        ApiCallStat(String logType, int eventCount, long payloadBytes, int statusCode, long durationMs) {
            this.logType = logType;
            this.eventCount = eventCount;
            this.payloadBytes = payloadBytes;
            this.statusCode = statusCode;
            this.durationMs = durationMs;
        }
    }
}
