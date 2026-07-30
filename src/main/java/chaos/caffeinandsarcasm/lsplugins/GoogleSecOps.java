package chaos.caffeinandsarcasm.lsplugins;

import co.elastic.logstash.api.Configuration;
import co.elastic.logstash.api.Context;
import co.elastic.logstash.api.Event;
import co.elastic.logstash.api.LogstashPlugin;
import co.elastic.logstash.api.Output;
import co.elastic.logstash.api.PluginConfigSpec;
import com.google.api.client.http.HttpResponseException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import chaos.caffeinandsarcasm.lsplugins.client.SecOpsApiClient;
import chaos.caffeinandsarcasm.lsplugins.client.StatsCollector;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

@LogstashPlugin(name = "google_secops")
public class GoogleSecOps implements Output {

    static final PluginConfigSpec<String> PROJECT_ID_CONFIG =
            PluginConfigSpec.stringSetting("project_id", "");
    static final PluginConfigSpec<String> INSTANCE_ID_CONFIG =
            PluginConfigSpec.stringSetting("instance_id", "");
    static final PluginConfigSpec<String> REGION_CONFIG =
            PluginConfigSpec.stringSetting("region", "us");
    static final PluginConfigSpec<String> LOG_TYPE_CONFIG =
            PluginConfigSpec.stringSetting("log_type", "");
    static final PluginConfigSpec<String> LOG_TYPE_FIELD_CONFIG =
            PluginConfigSpec.stringSetting("log_type_field", "[log_type]");
    static final PluginConfigSpec<String> FALLBACK_LOG_TYPE_CONFIG =
            PluginConfigSpec.stringSetting("fallback_log_type", "CATCH_ALL");
    static final PluginConfigSpec<String> DATA_FIELD_CONFIG =
            PluginConfigSpec.stringSetting("data_field", "message");
    static final PluginConfigSpec<String> LOG_ENTRY_TIME_FIELD_CONFIG =
            PluginConfigSpec.stringSetting("log_entry_time_field", "@timestamp");
    static final PluginConfigSpec<String> COLLECTION_TIME_FIELD_CONFIG =
            PluginConfigSpec.stringSetting("collection_time_field", "[event][created]");
    static final PluginConfigSpec<String> LABELS_FIELD_CONFIG =
            PluginConfigSpec.stringSetting("labels_field", "");
    static final PluginConfigSpec<String> FORWARDER_ID_CONFIG =
            PluginConfigSpec.stringSetting("forwarder_id", "");
    static final PluginConfigSpec<String> SOURCE_FILENAME_CONFIG =
            PluginConfigSpec.stringSetting("source_filename", "");
    static final PluginConfigSpec<String> SA_KEY_PATH_CONFIG =
            PluginConfigSpec.stringSetting("service_account_key_path", "");
    static final PluginConfigSpec<Long> BATCH_SIZE_CONFIG =
            PluginConfigSpec.numSetting("batch_size", 500L);
    static final PluginConfigSpec<Long> MAX_RETRIES_CONFIG =
            PluginConfigSpec.numSetting("max_retries", 3L);
    static final PluginConfigSpec<Boolean> COLLECT_STATS_CONFIG =
            PluginConfigSpec.booleanSetting("collect_stats", false);
    static final PluginConfigSpec<String> SSL_TRUSTSTORE_PATH_CONFIG =
            PluginConfigSpec.stringSetting("ssl_truststore_path", "");
    static final PluginConfigSpec<String> SSL_TRUSTSTORE_PASSWORD_CONFIG =
            PluginConfigSpec.stringSetting("ssl_truststore_password", "changeit");
    static final PluginConfigSpec<String> SSL_TRUSTSTORE_TYPE_CONFIG =
            PluginConfigSpec.stringSetting("ssl_truststore_type", "JKS");
    static final PluginConfigSpec<String> SSL_CA_CERT_PATH_CONFIG =
            PluginConfigSpec.stringSetting("ssl_ca_cert_path", "");

    private static final Set<String> VALID_REGIONS = Set.of(
            "us", "eu", "europe",
            "africa-south1",
            "asia-east1",
            "asia-northeast1",
            "asia-northeast3",
            "asia-south1",
            "asia-southeast1",
            "asia-southeast2",
            "australia-southeast1",
            "europe-central2",
            "europe-west12",
            "europe-west2",
            "europe-west3",
            "europe-west6",
            "europe-west9",
            "me-central1",
            "me-central2",
            "me-west1",
            "northamerica-northeast2",
            "southamerica-east1");

    private final String id;
    private final String projectId;
    private final String instanceId;
    private final String region;
    private final String logType;
    private final String logTypeField;
    private final String fallbackLogType;
    private final String dataField;
    private final String logEntryTimeField;
    private final String collectionTimeField;
    private final String labelsField;
    private final int batchSize;
    private final boolean collectStats;

    private final Logger logger = LogManager.getLogger(GoogleSecOps.class);
    private final SecOpsApiClient client;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean stopped = false;

    public GoogleSecOps(final String id, final Configuration config, final Context context) {
        this.id = id;

        this.projectId = config.get(PROJECT_ID_CONFIG);
        this.instanceId = config.get(INSTANCE_ID_CONFIG);
        this.region = config.get(REGION_CONFIG);
        this.logType = config.get(LOG_TYPE_CONFIG);
        this.logTypeField = config.get(LOG_TYPE_FIELD_CONFIG);
        this.fallbackLogType = config.get(FALLBACK_LOG_TYPE_CONFIG);
        this.dataField = config.get(DATA_FIELD_CONFIG);
        this.logEntryTimeField = config.get(LOG_ENTRY_TIME_FIELD_CONFIG);
        this.collectionTimeField = config.get(COLLECTION_TIME_FIELD_CONFIG);
        this.labelsField = config.get(LABELS_FIELD_CONFIG);
        this.batchSize = config.get(BATCH_SIZE_CONFIG).intValue();
        this.collectStats = config.get(COLLECT_STATS_CONFIG);

        long maxRetries = config.get(MAX_RETRIES_CONFIG);

        String forwarderId = config.get(FORWARDER_ID_CONFIG);
        String sourceFilename = config.get(SOURCE_FILENAME_CONFIG);
        String saKeyPath = config.get(SA_KEY_PATH_CONFIG);

        if (projectId == null || projectId.isEmpty()) {
            throw new IllegalArgumentException("'project_id' is required");
        }
        if (instanceId == null || instanceId.isEmpty()) {
            throw new IllegalArgumentException("'instance_id' is required");
        }
        if (batchSize < 1 || batchSize > 5000) {
            throw new IllegalArgumentException(
                    "'batch_size' must be between 1 and 5000 (got " + batchSize + ")");
        }
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException(
                    "'max_retries' must be between 0 and 10 (got " + maxRetries + ")");
        }
        if (!VALID_REGIONS.contains(region)) {
            throw new IllegalArgumentException(
                    "'region' must be one of the supported Chronicle API regions. See README for the full list. (got \""
                            + region + "\")");
        }

        try {
            GoogleCredentials credentials;
            if (saKeyPath != null && !saKeyPath.isEmpty()) {
                credentials = ServiceAccountCredentials.fromStream(new FileInputStream(saKeyPath));
            } else {
                credentials = GoogleCredentials.getApplicationDefault();
            }
            credentials = credentials.createScoped(
                    Collections.singletonList("https://www.googleapis.com/auth/chronicle"));
            credentials.refresh();

            this.client = new SecOpsApiClient(
                    credentials, region, projectId, instanceId,
                    batchSize, (int) maxRetries, forwarderId, sourceFilename,
                    config.get(SSL_TRUSTSTORE_PATH_CONFIG),
                    config.get(SSL_TRUSTSTORE_PASSWORD_CONFIG),
                    config.get(SSL_TRUSTSTORE_TYPE_CONFIG),
                    config.get(SSL_CA_CERT_PATH_CONFIG));

        } catch (IOException e) {
            throw new RuntimeException(buildCredentialErrorMessage(e, saKeyPath), e);
        }
    }

    private static String buildCredentialErrorMessage(IOException e, String saKeyPath) {
        if (e instanceof FileNotFoundException) {
            return "Service account key file not found at '" + saKeyPath + "'.\n"
                    + "Verify that 'service_account_key_path' points to an existing readable file.";
        }

        String msg = e.getMessage();
        if (msg != null) {
            if (msg.contains("Error reading service account credential") || msg.contains("Error parsing")) {
                return "Service account key file at '" + saKeyPath + "' is malformed or invalid.\n"
                        + "Re-download the key file from the GCP Console.";
            }
            if (msg.contains("Application Default Credentials")) {
                return "Application Default Credentials (ADC) are not available.\n"
                        + "Run 'gcloud auth application-default login' or set GOOGLE_APPLICATION_CREDENTIALS.";
            }
        }

        HttpResponseException hre = null;
        if (e instanceof HttpResponseException) {
            hre = (HttpResponseException) e;
        } else if (e.getCause() instanceof HttpResponseException) {
            hre = (HttpResponseException) e.getCause();
        }

        if (hre != null) {
            String content = hre.getContent();
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                    String oauthError = json.has("error") ? json.get("error").getAsString() : "";
                    String description = json.has("error_description") ? json.get("error_description").getAsString() : "";
                    String uri = json.has("error_uri") ? json.get("error_uri").getAsString() : "";
                    StringBuilder sb = new StringBuilder();
                    sb.append("Google rejected the credentials (").append(hre.getStatusCode()).append(")");
                    if (!oauthError.isEmpty()) {
                        sb.append(": ").append(oauthError);
                        if (!description.isEmpty()) {
                            sb.append(" - ").append(description);
                        }
                    }
                    if (!uri.isEmpty()) {
                        sb.append("\nSee: ").append(uri);
                    }
                    return sb.toString();
                } catch (Exception ignored) {
                }
            }
            return "Google rejected the credentials (" + hre.getStatusCode() + ").\n"
                    + "Check that your credentials are valid and have the required permissions.";
        }

        if (e instanceof SocketException || e instanceof ConnectException || e instanceof UnknownHostException) {
            return "Cannot reach the OAuth token endpoint at https://oauth2.googleapis.com/token.\n"
                    + "Check network connectivity and firewall settings.";
        }

        return e.getClass().getSimpleName() + " - " + (msg != null ? msg : "No details available.");
    }

    @Override
    public void output(final Collection<Event> events) {
        StatsCollector stats = new StatsCollector(collectStats);

        List<LogEntry> entries = new ArrayList<>(events.size());
        for (Event event : events) {
            LogEntry entry = convertToLogEntry(event);
            if (entry != null) {
                entries.add(entry);
            }
        }

        Map<String, List<LogEntry>> groups = entries.stream()
                .collect(Collectors.groupingBy(LogEntry::getLogType, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<LogEntry>> group : groups.entrySet()) {
            String resolvedLogType = group.getKey();
            List<LogEntry> logEntries = group.getValue();

            String parent = String.format("projects/%s/locations/%s/instances/%s/logTypes/%s",
                    projectId, region, instanceId, resolvedLogType);

            client.importLogs(parent, logEntries, stats);
        }

        stats.printSummary(
                groups.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private LogEntry convertToLogEntry(Event event) {
        String data = extractData(event);
        if (data == null || data.isEmpty()) {
            return null;
        }

        String logEntryTime = extractTimestamp(event, logEntryTimeField);
        if (logEntryTime == null) {
            logEntryTime = formatInstant(event.getEventTimestamp());
        }

        String collectionTime = extractTimestamp(event, collectionTimeField);
        if (collectionTime == null) {
            collectionTime = logEntryTime;
        }

        String resolvedLogType = resolveLogType(event);

        Map<String, Object> labels = extractLabels(event);

        return new LogEntry(data, logEntryTime, collectionTime, resolvedLogType, labels);
    }

    private String extractData(Event event) {
        Object value = event.getField(dataField);
        if (value == null) {
            return "";
        }
        byte[] utf8Bytes = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(utf8Bytes);
    }

    private String extractTimestamp(Event event, String field) {
        if ("@timestamp".equals(field)) {
            return formatInstant(event.getEventTimestamp());
        }
        Object value = event.getField(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant) {
            return formatInstant((Instant) value);
        }
        return value.toString();
    }

    private static String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private String resolveLogType(Event event) {
        if (logType != null && !logType.isEmpty()) {
            return logType;
        }
        Object value = event.getField(logTypeField);
        if (value != null && !value.toString().isEmpty()) {
            return value.toString();
        }
        return fallbackLogType;
    }

    private Map<String, Object> extractLabels(Event event) {
        if (labelsField == null || labelsField.isEmpty()) {
            return null;
        }
        Object value = event.getField(labelsField);
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> labels = (Map<String, Object>) value;
            return labels.isEmpty() ? null : labels;
        }
        return null;
    }

    @Override
    public Collection<PluginConfigSpec<?>> configSchema() {
        return List.of(
                PROJECT_ID_CONFIG, INSTANCE_ID_CONFIG, REGION_CONFIG,
                LOG_TYPE_CONFIG, LOG_TYPE_FIELD_CONFIG, FALLBACK_LOG_TYPE_CONFIG,
                DATA_FIELD_CONFIG, LOG_ENTRY_TIME_FIELD_CONFIG, COLLECTION_TIME_FIELD_CONFIG,
                LABELS_FIELD_CONFIG, FORWARDER_ID_CONFIG, SOURCE_FILENAME_CONFIG,
                SA_KEY_PATH_CONFIG, BATCH_SIZE_CONFIG, MAX_RETRIES_CONFIG, COLLECT_STATS_CONFIG,
                SSL_TRUSTSTORE_PATH_CONFIG, SSL_TRUSTSTORE_PASSWORD_CONFIG,
                SSL_TRUSTSTORE_TYPE_CONFIG, SSL_CA_CERT_PATH_CONFIG);
    }

    @Override
    public void stop() {
        stopped = true;
        client.shutdown();
        try {
            client.close();
        } catch (Exception e) {
            logger.warn("Error during shutdown: {}", e.getMessage());
        }
        done.countDown();
    }

    @Override
    public void awaitStop() throws InterruptedException {
        done.await();
    }

    @Override
    public String getId() {
        return id;
    }
}
