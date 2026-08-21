package chaos.caffeinandsarcasm.lsplugins;

import java.util.Map;

public class LogEntry {

    private final String data;
    private final String logEntryTime;
    private final String collectionTime;
    private final String logType;
    private final Map<String, LogLabel> labels;

    public LogEntry(String data, String logEntryTime, String collectionTime,
                    String logType, Map<String, LogLabel> labels) {
        this.data = data;
        this.logEntryTime = logEntryTime;
        this.collectionTime = collectionTime;
        this.logType = logType;
        this.labels = labels;
    }

    public String getData() {
        return data;
    }

    public String getLogEntryTime() {
        return logEntryTime;
    }

    public String getCollectionTime() {
        return collectionTime;
    }

    public String getLogType() {
        return logType;
    }

    public Map<String, LogLabel> getLabels() {
        return labels;
    }
}
