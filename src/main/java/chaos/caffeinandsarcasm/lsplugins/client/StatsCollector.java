package chaos.caffeinandsarcasm.lsplugins.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StatsCollector {

    private final Logger logger = LogManager.getLogger(StatsCollector.class);
    private final boolean enabled;
    private final List<ApiCallStat> calls = new ArrayList<>();

    public StatsCollector(boolean enabled) {
        this.enabled = enabled;
    }

    public void recordCall(String logType, int eventCount, long payloadBytes, int statusCode, long durationMs) {
        if (!enabled) {
            return;
        }
        calls.add(new ApiCallStat(logType, eventCount, payloadBytes, statusCode, durationMs));
    }

    public void printSummary(Map<String, List<?>> groups) {
        if (!enabled || calls.isEmpty()) {
            return;
        }

        int totalEvents = 0;
        for (List<?> list : groups.values()) {
            totalEvents += list.size();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("[google_secops] Batch stats (").append(totalEvents)
                .append(" events, ").append(groups.size()).append(" log type group(s)):");
        sb.append(System.lineSeparator());

        Map<String, List<ApiCallStat>> byType = new LinkedHashMap<>();
        for (ApiCallStat stat : calls) {
            byType.computeIfAbsent(stat.logType, k -> new ArrayList<>()).add(stat);
        }

        for (Map.Entry<String, List<ApiCallStat>> entry : byType.entrySet()) {
            String logType = entry.getKey();
            List<ApiCallStat> typeCalls = entry.getValue();
            int totalEv = 0;
            long totalBytes = 0;
            for (ApiCallStat s : typeCalls) {
                totalEv += s.eventCount;
                totalBytes += s.payloadBytes;
            }
            sb.append("  ").append(logType).append(": ")
                    .append(typeCalls.size()).append(" API call(s), ")
                    .append(totalEv).append(" events, ")
                    .append(formatBytes(totalBytes)).append(" total");
            sb.append(System.lineSeparator());

            for (int i = 0; i < typeCalls.size(); i++) {
                ApiCallStat s = typeCalls.get(i);
                sb.append("    [").append(i + 1).append("/").append(typeCalls.size()).append("] ")
                        .append(s.eventCount).append(" ev, ")
                        .append(formatBytes(s.payloadBytes)).append(", ")
                        .append(s.statusCode).append(" (")
                        .append(s.durationMs).append("ms)");
                sb.append(System.lineSeparator());
            }
        }

        logger.info(sb.toString());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
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
