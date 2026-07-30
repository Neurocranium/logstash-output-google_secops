package chaos.caffeinandsarcasm.lsplugins.client;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import chaos.caffeinandsarcasm.lsplugins.LogEntry;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SecOpsApiClient implements AutoCloseable {

    private static final long RETRY_AFTER_DEFAULT_SECONDS = 300;
    private static final long MIN_RETRY_AFTER_SECONDS = 1;
    private static final long MAX_RETRY_AFTER_SECONDS = 600;
    private static final long MAX_BATCH_BYTES = 4_000_000L;
    private static final long SLEEP_POLL_INTERVAL_MS = 1000;
    private static final long[] BACKOFF_DELAYS_MS = {1000, 2000, 4000};

    private final Logger logger = LogManager.getLogger(SecOpsApiClient.class);
    private final HttpTransport transport;
    private final HttpRequestFactory requestFactory;
    private final Gson gson;
    private final String baseUrl;
    private final int batchSize;
    private final int maxRetries;
    private final String forwarderId;
    private final String sourceFilename;
    private volatile boolean stopped;

    public SecOpsApiClient(GoogleCredentials credentials, String region,
                           String projectId, String instanceId,
                           int batchSize, int maxRetries,
                           String forwarderId, String sourceFilename,
                           String sslTruststorePath, String sslTruststorePassword,
                           String sslTruststoreType, String sslCaCertPath) throws IOException {
        if (sslTruststorePath != null && !sslTruststorePath.isEmpty()) {
            this.transport = buildTransportWithTruststore(
                    sslTruststorePath, sslTruststorePassword, sslTruststoreType);
        } else if (sslCaCertPath != null && !sslCaCertPath.isEmpty()) {
            this.transport = buildTransportWithCaCert(sslCaCertPath);
        } else {
            this.transport = new NetHttpTransport();
        }
        HttpCredentialsAdapter credentialsAdapter = new HttpCredentialsAdapter(credentials);
        this.requestFactory = transport.createRequestFactory(credentialsAdapter);
        this.gson = new Gson();
        this.baseUrl = String.format("https://chronicle.%s.rep.googleapis.com/v1", region);
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.forwarderId = forwarderId;
        this.sourceFilename = sourceFilename;
    }

    public void importLogs(String parent, List<LogEntry> entries, StatsCollector stats) {
        for (int i = 0; i < entries.size(); i += batchSize) {
            int end = Math.min(i + batchSize, entries.size());
            List<LogEntry> batch = entries.subList(i, end);
            splitAndSend(parent, batch, stats);
        }
    }

    private void splitAndSend(String parent, List<LogEntry> entries, StatsCollector stats) {
        if (entries.isEmpty()) {
            return;
        }
        byte[] body = buildRequestBody(entries);
        if (body.length <= MAX_BATCH_BYTES) {
            sendWithRetry(parent, entries, stats);
            return;
        }
        if (entries.size() == 1) {
            logger.warn("Dropping oversized entry: {} bytes exceeds {} byte limit, logType={}",
                    body.length, MAX_BATCH_BYTES, entries.get(0).getLogType());
            return;
        }
        int mid = entries.size() / 2;
        logger.warn("Batch of {} events is {} bytes, exceeding the {} byte limit by {} bytes. Splitting into smaller batches.",
                entries.size(), body.length, MAX_BATCH_BYTES, body.length - MAX_BATCH_BYTES);
        splitAndSend(parent, entries.subList(0, mid), stats);
        splitAndSend(parent, entries.subList(mid, entries.size()), stats);
    }

    private void sendWithRetry(String parent, List<LogEntry> batch, StatsCollector stats) {
        String url = baseUrl + "/" + parent + "/logs:import";
        long startTime = System.currentTimeMillis();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (stopped) {
                return;
            }
            try {
                byte[] bodyBytes = buildRequestBody(batch);

                HttpRequest request = buildPostRequest(url, bodyBytes);
                HttpResponse response = request.execute();
                int statusCode = response.getStatusCode();
                long durationMs = System.currentTimeMillis() - startTime;

                if (statusCode >= 200 && statusCode < 300) {
                    stats.recordCall(resolveLogTypeFromLogs(batch),
                            batch.size(), bodyBytes.length, statusCode, durationMs);
                    response.ignore();
                    return;
                }

                String responseBody = response.parseAsString();
                response.disconnect();

                if (statusCode == 429) {
                    long retryAfterSeconds = RETRY_AFTER_DEFAULT_SECONDS;
                    String retryAfterHeader = response.getHeaders().getRetryAfter();
                    if (retryAfterHeader != null) {
                        try {
                            retryAfterSeconds = Long.parseLong(retryAfterHeader);
                        } catch (NumberFormatException e) {
                            retryAfterSeconds = RETRY_AFTER_DEFAULT_SECONDS;
                        }
                    }
                    retryAfterSeconds = Math.max(MIN_RETRY_AFTER_SECONDS,
                            Math.min(retryAfterSeconds, MAX_RETRY_AFTER_SECONDS));

                    logger.warn("429 Too Many Requests for {}. Waiting {} seconds before retry. Response: {}",
                            url, retryAfterSeconds, responseBody);

                    if (interruptibleSleep(TimeUnit.SECONDS.toMillis(retryAfterSeconds))) {
                        stats.recordCall(resolveLogTypeFromLogs(batch),
                                batch.size(), bodyBytes.length, statusCode, durationMs);
                        return;
                    }

                    HttpRequest retryRequest = buildPostRequest(url, bodyBytes);
                    HttpResponse retryResponse = retryRequest.execute();
                    int retryStatusCode = retryResponse.getStatusCode();
                    long retryDurationMs = System.currentTimeMillis() - startTime;

                    if (retryStatusCode >= 200 && retryStatusCode < 300) {
                        stats.recordCall(resolveLogTypeFromLogs(batch),
                                batch.size(), bodyBytes.length, retryStatusCode, retryDurationMs);
                        retryResponse.disconnect();
                        return;
                    }

                    String retryBody = retryResponse.parseAsString();
                    retryResponse.disconnect();

                    logger.warn("Still receiving 429 after waiting {} seconds. Dropping batch. URL: {}, Response: {}",
                            retryAfterSeconds, url, retryBody);

                    stats.recordCall(resolveLogTypeFromLogs(batch),
                            batch.size(), bodyBytes.length, retryStatusCode, retryDurationMs);
                    return;
                }

                if (statusCode >= 400 && statusCode < 500) {
                    logger.warn("Client error {} for {}. Dropping batch. Response: {}",
                            statusCode, url, responseBody);
                    stats.recordCall(resolveLogTypeFromLogs(batch),
                            batch.size(), bodyBytes.length, statusCode, durationMs);
                    return;
                }

                if (statusCode >= 500) {
                    if (attempt < maxRetries) {
                        long delayMs = getBackoffDelay(attempt);
                        logger.warn("Server error {} for {}. Retrying in {} ms (attempt {}/{}). Response: {}",
                                statusCode, url, delayMs, attempt + 1, maxRetries, responseBody);
                        if (interruptibleSleep(delayMs)) {
                            stats.recordCall(resolveLogTypeFromLogs(batch),
                                    batch.size(), bodyBytes.length, statusCode, durationMs);
                            return;
                        }
                    } else {
                        logger.warn("Server error {} for {}. Max retries exhausted. Dropping batch. Response: {}",
                                statusCode, url, responseBody);
                        stats.recordCall(resolveLogTypeFromLogs(batch),
                                batch.size(), bodyBytes.length, statusCode, durationMs);
                        return;
                    }
                }

            } catch (SocketTimeoutException e) {
                long durationMs = System.currentTimeMillis() - startTime;
                if (attempt < maxRetries) {
                    long delayMs = getBackoffDelay(attempt);
                    logger.warn("Timeout for {}. Retrying in {} ms (attempt {}/{}): {}",
                            url, delayMs, attempt + 1, maxRetries, e.getMessage());
                    if (interruptibleSleep(delayMs)) {
                        stats.recordCall(resolveLogTypeFromLogs(batch),
                                batch.size(), 0, 0, durationMs);
                        return;
                    }
                } else {
                    logger.warn("Timeout for {}. Max retries exhausted. Dropping batch: {}",
                            url, e.getMessage());
                    stats.recordCall(resolveLogTypeFromLogs(batch),
                            batch.size(), 0, 0, durationMs);
                    return;
                }
            } catch (IOException e) {
                long durationMs = System.currentTimeMillis() - startTime;
                if (attempt < maxRetries) {
                    long delayMs = getBackoffDelay(attempt);
                    logger.warn("IO error for {}. Retrying in {} ms (attempt {}/{}): {}",
                            url, delayMs, attempt + 1, maxRetries, e.getMessage());
                    if (interruptibleSleep(delayMs)) {
                        stats.recordCall(resolveLogTypeFromLogs(batch),
                                batch.size(), 0, 0, durationMs);
                        return;
                    }
                } else {
                    logger.warn("IO error for {}. Max retries exhausted. Dropping batch: {}",
                            url, e.getMessage());
                    stats.recordCall(resolveLogTypeFromLogs(batch),
                            batch.size(), 0, 0, durationMs);
                    return;
                }
            }
        }
    }

    private HttpRequest buildPostRequest(String url, byte[] bodyBytes) throws IOException {
        ByteArrayContent content = new ByteArrayContent("application/json", bodyBytes);
        HttpRequest request = requestFactory.buildPostRequest(new GenericUrl(url), content);
        request.setSuppressUserAgentSuffix(true);
        request.setReadTimeout(60000);
        request.setConnectTimeout(30000);
        request.setThrowExceptionOnExecuteError(false);
        return request;
    }

    private byte[] buildRequestBody(List<LogEntry> entries) {
        Map<String, Object> body = new HashMap<>();

        Map<String, Object> inlineSource = new HashMap<>();
        List<Map<String, Object>> logsList = new ArrayList<>();

        for (LogEntry entry : entries) {
            Map<String, Object> logObj = new HashMap<>();
            logObj.put("data", entry.getData());
            logObj.put("logEntryTime", entry.getLogEntryTime());
            logObj.put("collectionTime", entry.getCollectionTime());

            if (entry.getLabels() != null && !entry.getLabels().isEmpty()) {
                logObj.put("labels", entry.getLabels());
            }

            logsList.add(logObj);
        }

        inlineSource.put("logs", logsList);

        if (forwarderId != null && !forwarderId.isEmpty()) {
            inlineSource.put("forwarder", forwarderId);
        }

        if (sourceFilename != null && !sourceFilename.isEmpty()) {
            inlineSource.put("sourceFilename", sourceFilename);
        }

        body.put("inlineSource", inlineSource);

        String json = gson.toJson(body);
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public void shutdown() {
        this.stopped = true;
    }

    private boolean interruptibleSleep(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (true) {
            if (stopped || Thread.currentThread().isInterrupted()) {
                return true;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.min(remaining, SLEEP_POLL_INTERVAL_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
    }

    private static long getBackoffDelay(int attempt) {
        int index = Math.min(attempt, BACKOFF_DELAYS_MS.length - 1);
        return BACKOFF_DELAYS_MS[index];
    }

    private static String resolveLogTypeFromLogs(List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return "unknown";
        }
        return entries.get(0).getLogType();
    }

    private static NetHttpTransport buildTransportWithTruststore(
            String truststorePath, String truststorePassword, String truststoreType) throws IOException {
        try {
            KeyStore trustStore = KeyStore.getInstance(truststoreType);
            try (InputStream in = new FileInputStream(truststorePath)) {
                trustStore.load(in, truststorePassword.toCharArray());
            }
            return new NetHttpTransport.Builder()
                    .trustCertificates(trustStore)
                    .build();
        } catch (Exception e) {
            throw new IOException("Failed to initialize custom truststore: " + e.getMessage(), e);
        }
    }

    private static NetHttpTransport buildTransportWithCaCert(String caCertPath) throws IOException {
        try (InputStream in = new FileInputStream(caCertPath)) {
            return new NetHttpTransport.Builder()
                    .trustCertificatesFromStream(in)
                    .build();
        } catch (Exception e) {
            throw new IOException("Failed to initialize CA certificate: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            transport.shutdown();
        } catch (IOException e) {
            logger.warn("Error shutting down HTTP transport: {}", e.getMessage());
        }
    }
}
