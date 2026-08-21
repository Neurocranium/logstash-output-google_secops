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

    private final Logger logger;
    private final HttpTransport fullVerificationTransport;
    private final HttpTransport restrictedRegionalTransport;
    private final HttpRequestFactory fullVerificationRequestFactory;
    private final HttpRequestFactory restrictedRegionalRequestFactory;
    private final HostnameMismatchTracker hostnameMismatchTracker;
    private final boolean restrictedRegionalFallbackEnabled;
    private final Gson gson;
    private final EndpointSelector endpointSelector;
    private final int batchSize;
    private final int maxRetries;
    private final String forwarderId;
    private final String sourceFilename;
    private volatile boolean stopped = false;

    public SecOpsApiClient(GoogleCredentials credentials, String region,
                           String projectId, String instanceId,
                           int batchSize, int maxRetries,
                           String forwarderId, String sourceFilename,
                           String sslTruststorePath, String sslTruststorePassword,
                           String sslTruststoreType, String sslCaCertPath,
                           String sslVerificationMode, Logger logger) throws IOException {
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
        this.endpointSelector = new EndpointSelector(region);
        this.hostnameMismatchTracker = new HostnameMismatchTracker();
        this.restrictedRegionalFallbackEnabled = "certificate".equals(sslVerificationMode);

        NetHttpTransport.Builder fullTransportBuilder = createTransportBuilder(
                sslTruststorePath, sslTruststorePassword, sslTruststoreType, sslCaCertPath);
        fullTransportBuilder.setHostnameVerifier(hostnameMismatchTracker);
        this.fullVerificationTransport = fullTransportBuilder.build();

        HttpCredentialsAdapter credentialsAdapter = new HttpCredentialsAdapter(credentials);
        this.fullVerificationRequestFactory =
                fullVerificationTransport.createRequestFactory(credentialsAdapter);

        if (restrictedRegionalFallbackEnabled) {
            NetHttpTransport.Builder restrictedTransportBuilder = createTransportBuilder(
                    sslTruststorePath, sslTruststorePassword, sslTruststoreType, sslCaCertPath);
            restrictedTransportBuilder.setHostnameVerifier(
                    new RegionalGoogleApisHostnameVerifier(endpointSelector.regionalHostname()));
            this.restrictedRegionalTransport = restrictedTransportBuilder.build();
            this.restrictedRegionalRequestFactory =
                    restrictedRegionalTransport.createRequestFactory(credentialsAdapter);
            logger.warn("Restricted regional TLS fallback is enabled. Full hostname verification will be tried for "
                    + "both Chronicle endpoints first; the regional endpoint may then accept a chain-trusted leaf "
                    + "certificate with a DNS SAN in the googleapis.com namespace.");
        } else {
            this.restrictedRegionalTransport = null;
            this.restrictedRegionalRequestFactory = null;
        }
        this.gson = new Gson();
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.forwarderId = forwarderId;
        this.sourceFilename = sourceFilename;
    }

    private static NetHttpTransport.Builder createTransportBuilder(
            String sslTruststorePath, String sslTruststorePassword,
            String sslTruststoreType, String sslCaCertPath) throws IOException {
        NetHttpTransport.Builder builder = new NetHttpTransport.Builder();
        if (sslTruststorePath != null && !sslTruststorePath.isEmpty()) {
            configureTruststore(builder, sslTruststorePath, sslTruststorePassword, sslTruststoreType);
        } else if (sslCaCertPath != null && !sslCaCertPath.isEmpty()) {
            configureCaCert(builder, sslCaCertPath);
        }
        return builder;
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
        logger.warn("Batch of {} events is {} bytes, exceeding the {} byte limit by {} bytes. Splitting into smaller "
                        + "batches. If this occurs repeatedly, consider lowering batch_size.",
                entries.size(), body.length, MAX_BATCH_BYTES, body.length - MAX_BATCH_BYTES);
        splitAndSend(parent, entries.subList(0, mid), stats);
        splitAndSend(parent, entries.subList(mid, entries.size()), stats);
    }

    private void sendWithRetry(String parent, List<LogEntry> batch, StatsCollector stats) {
        EndpointSelector.Route route = endpointSelector.selectRoute();
        EndpointSelector.Route probeFallback = route.isProbe()
                ? endpointSelector.fallbackRouteForProbe() : null;
        boolean probing = route.isProbe();
        String url = buildImportUrl(route.baseUrl(), parent);
        TlsFallbackPlanner.Activation fallbackActivation = TlsFallbackPlanner.Activation.NONE;
        TlsFallbackPlanner tlsFallbackPlanner = new TlsFallbackPlanner(
                endpointSelector, restrictedRegionalFallbackEnabled);
        long startTime = System.currentTimeMillis();
        byte[] bodyBytes = buildRequestBody(batch);

        int attempt = 0;
        while (attempt <= maxRetries) {
            if (stopped) {
                return;
            }
            try {
                HttpResponse response = executePostRequest(route, url, bodyBytes);
                int statusCode = response.getStatusCode();
                long durationMs = System.currentTimeMillis() - startTime;

                if (statusCode >= 200 && statusCode < 300) {
                    handleEndpointSuccess(route, probing, probeFallback, fallbackActivation);
                    stats.recordCall(resolveLogTypeFromLogs(batch),
                            batch.size(), bodyBytes.length, statusCode, durationMs);
                    response.ignore();
                    return;
                }

                String responseBody = response.parseAsString();
                response.disconnect();

                if (probing) {
                    EndpointSelector.FallbackMode fallbackMode = fallbackModeFor(probeFallback);
                    logProbeFailure(endpointSelector.preferredProbeFailed(fallbackMode),
                            "HTTP " + statusCode);
                    route = probeFallback;
                    url = buildImportUrl(route.baseUrl(), parent);
                    probing = false;
                    fallbackActivation = TlsFallbackPlanner.Activation.NONE;
                    attempt = 0;
                    continue;
                }

                if (route.isRegional() && statusCode == 404) {
                    tlsFallbackPlanner.recordRegionalHttp404();
                    logger.warn("Preferred regional Chronicle endpoint returned 404 for {}. Retrying this batch through "
                            + "the global-routed endpoint {}.",
                            url, endpointSelector.globalFullRoute().baseUrl());
                    route = endpointSelector.globalFullRoute();
                    url = buildImportUrl(route.baseUrl(), parent);
                    fallbackActivation = TlsFallbackPlanner.Activation.GLOBAL_FULL;
                    attempt = 0;
                    continue;
                }

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

                    HttpResponse retryResponse = executePostRequest(route, url, bodyBytes);
                    int retryStatusCode = retryResponse.getStatusCode();
                    long retryDurationMs = System.currentTimeMillis() - startTime;

                    if (retryStatusCode >= 200 && retryStatusCode < 300) {
                        handleEndpointSuccess(route, probing, probeFallback, fallbackActivation);
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

                attempt++;

            } catch (HostnameMismatchIOException e) {
                EndpointSelector.Route failedRoute = route;
                TlsFallbackPlanner.Decision decision =
                        tlsFallbackPlanner.afterHostnameMismatch(failedRoute);
                if (decision.isTerminal()) {
                    logTerminalHostnameMismatch(url, batch, bodyBytes, stats, startTime);
                    return;
                }
                route = decision.route();
                fallbackActivation = decision.activation();
                if (route.isRestrictedVerification()) {
                    if (probing) {
                        logProbeFailure(endpointSelector.preferredProbeFailed(
                                        EndpointSelector.FallbackMode.REGIONAL_RESTRICTED),
                                "full hostname verification failed for both Chronicle endpoints");
                        probing = false;
                        fallbackActivation = TlsFallbackPlanner.Activation.NONE;
                    }
                    logger.warn("Full hostname verification failed for both Chronicle endpoints. Retrying this "
                            + "batch through the regional endpoint with restricted certificate verification {}.",
                            route.baseUrl());
                } else if (failedRoute.isRegional()) {
                    logger.warn("Full hostname verification failed for the regional Chronicle endpoint {}. "
                            + "Retrying this batch through the fully verified global-routed endpoint {}.",
                            url, route.baseUrl());
                } else {
                    logger.warn("Full hostname verification failed for the global-routed Chronicle endpoint {}. "
                            + "Retrying this batch through the fully verified regional endpoint {}.",
                            url, route.baseUrl());
                }
                url = buildImportUrl(route.baseUrl(), parent);
                attempt = 0;
            } catch (SocketTimeoutException e) {
                if (probing) {
                    EndpointSelector.FallbackMode fallbackMode = fallbackModeFor(probeFallback);
                    logProbeFailure(endpointSelector.preferredProbeFailed(fallbackMode),
                            "timeout: " + e.getMessage());
                    route = probeFallback;
                    url = buildImportUrl(route.baseUrl(), parent);
                    probing = false;
                    fallbackActivation = TlsFallbackPlanner.Activation.NONE;
                    attempt = 0;
                    continue;
                }
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
                    attempt++;
                } else {
                    logger.warn("Timeout for {}. Max retries exhausted. Dropping batch: {}",
                            url, e.getMessage());
                    stats.recordCall(resolveLogTypeFromLogs(batch),
                            batch.size(), 0, 0, durationMs);
                    return;
                }
            } catch (IOException e) {
                if (probing) {
                    EndpointSelector.FallbackMode fallbackMode = fallbackModeFor(probeFallback);
                    logProbeFailure(endpointSelector.preferredProbeFailed(fallbackMode),
                            "IO error: " + e.getMessage());
                    route = probeFallback;
                    url = buildImportUrl(route.baseUrl(), parent);
                    probing = false;
                    fallbackActivation = TlsFallbackPlanner.Activation.NONE;
                    attempt = 0;
                    continue;
                }
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
                    attempt++;
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

    private static String buildImportUrl(String baseUrl, String parent) {
        return baseUrl + "/" + parent + "/logs:import";
    }

    private void handleEndpointSuccess(EndpointSelector.Route route, boolean probing,
                                       EndpointSelector.Route probeFallback,
                                       TlsFallbackPlanner.Activation fallbackActivation) {
        if (probing && route.isRegional() && route.isFullVerification()
                && endpointSelector.preferredProbeSucceeded()) {
            logger.warn("Regional Chronicle endpoint routing is available again. Restoring preferred endpoint {}.",
                    route.baseUrl());
            return;
        }
        if (probing && route.isGlobal()) {
            boolean recoveredFromRestricted = probeFallback != null
                    && probeFallback.isRestrictedVerification();
            EndpointSelector.ProbeFailure failure = endpointSelector.preferredProbeFailed(
                    EndpointSelector.FallbackMode.GLOBAL_FULL);
            if (recoveredFromRestricted) {
                if (failure.isPermanent()) {
                    logger.warn("Full TLS verification succeeded through the global-routed Chronicle endpoint {}. "
                            + "Leaving restricted regional verification; no further preferred regional endpoint "
                            + "probes will be made for this plugin instance.", route.baseUrl());
                } else {
                    logger.warn("Full TLS verification succeeded through the global-routed Chronicle endpoint {}. "
                            + "Leaving restricted regional verification; the preferred regional endpoint will be "
                            + "probed again after {}.", route.baseUrl(), formatDuration(failure.retryAfter()));
                }
            } else {
                logProbeFailure(failure, "regional full hostname verification remains unavailable");
            }
            return;
        }
        if (fallbackActivation == TlsFallbackPlanner.Activation.REGIONAL_FULL
                && endpointSelector.restoreRegional()) {
            logger.warn("Full TLS verification succeeded through the preferred regional Chronicle endpoint {}. "
                    + "Restoring it as the active endpoint.", route.baseUrl());
        } else if (fallbackActivation == TlsFallbackPlanner.Activation.GLOBAL_FULL
                && endpointSelector.activateFallback(EndpointSelector.FallbackMode.GLOBAL_FULL)) {
            logger.warn("Using fully verified global-routed Chronicle endpoint {}. The preferred regional endpoint "
                    + "will be probed again in 1 hour.", route.baseUrl());
        } else if (fallbackActivation == TlsFallbackPlanner.Activation.REGIONAL_RESTRICTED
                && endpointSelector.activateFallback(EndpointSelector.FallbackMode.REGIONAL_RESTRICTED)) {
            logger.warn("Using restricted certificate verification for regional Chronicle endpoint {}. Full "
                    + "verification will be probed again in 1 hour.", route.baseUrl());
        }
    }

    private static EndpointSelector.FallbackMode fallbackModeFor(EndpointSelector.Route route) {
        return route != null && route.isRestrictedVerification()
                ? EndpointSelector.FallbackMode.REGIONAL_RESTRICTED
                : EndpointSelector.FallbackMode.GLOBAL_FULL;
    }

    private void logTerminalHostnameMismatch(String url, List<LogEntry> batch,
                                             byte[] bodyBytes, StatsCollector stats,
                                             long startTime) {
        logger.warn("Full hostname verification failed for Chronicle endpoint {}. No permitted TLS fallback remains; "
                + "dropping batch.", url);
        stats.recordCall(resolveLogTypeFromLogs(batch), batch.size(), bodyBytes.length, 0,
                System.currentTimeMillis() - startTime);
    }

    private void logProbeFailure(EndpointSelector.ProbeFailure failure, String reason) {
        if (!failure.isApplied()) {
            return;
        }
        String activeEndpoint = failure.fallbackMode() == EndpointSelector.FallbackMode.REGIONAL_RESTRICTED
                ? "the regional endpoint with restricted certificate verification"
                : "the fully verified global-routed endpoint";
        if (failure.isPermanent()) {
            logger.warn("Cooldown probe of full Chronicle endpoint verification failed ({}). Continuing with {} "
                    + "permanently for this plugin instance; no further probes will be made.", reason, activeEndpoint);
        } else {
            logger.warn("Cooldown probe of full Chronicle endpoint verification failed ({}). Continuing with {}; "
                    + "the next probe will run after {}.",
                    reason, activeEndpoint, formatDuration(failure.retryAfter()));
        }
    }

    private static String formatDuration(java.time.Duration duration) {
        if (duration.toDays() > 0) {
            return duration.toDays() + (duration.toDays() == 1 ? " day" : " days");
        }
        return duration.toHours() + (duration.toHours() == 1 ? " hour" : " hours");
    }

    private HttpResponse executePostRequest(EndpointSelector.Route route, String url,
                                            byte[] bodyBytes) throws IOException {
        String hostname = new GenericUrl(url).getHost();
        if (route.isFullVerification()) {
            hostnameMismatchTracker.clear();
        }
        try {
            return buildPostRequest(route, url, bodyBytes).execute();
        } catch (IOException e) {
            if (route.isFullVerification() && hostnameMismatchTracker.consumeMismatchFor(hostname)) {
                throw new HostnameMismatchIOException(hostname, e);
            }
            throw e;
        } finally {
            if (route.isFullVerification()) {
                hostnameMismatchTracker.clear();
            }
        }
    }

    private HttpRequest buildPostRequest(EndpointSelector.Route route, String url,
                                         byte[] bodyBytes) throws IOException {
        ByteArrayContent content = new ByteArrayContent("application/json", bodyBytes);
        HttpRequestFactory requestFactory = route.isRestrictedVerification()
                ? restrictedRegionalRequestFactory : fullVerificationRequestFactory;
        if (requestFactory == null) {
            throw new IOException("Restricted regional TLS fallback is not enabled");
        }
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

    private static void configureTruststore(
            NetHttpTransport.Builder builder, String truststorePath,
            String truststorePassword, String truststoreType) throws IOException {
        try {
            KeyStore trustStore = KeyStore.getInstance(truststoreType);
            try (InputStream in = new FileInputStream(truststorePath)) {
                trustStore.load(in, truststorePassword.toCharArray());
            }
            builder.trustCertificates(trustStore);
        } catch (Exception e) {
            throw new IOException("Failed to initialize custom truststore: " + e.getMessage(), e);
        }
    }

    private static void configureCaCert(NetHttpTransport.Builder builder, String caCertPath) throws IOException {
        try (InputStream in = new FileInputStream(caCertPath)) {
            builder.trustCertificatesFromStream(in);
        } catch (Exception e) {
            throw new IOException("Failed to initialize CA certificate: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            fullVerificationTransport.shutdown();
        } catch (IOException e) {
            logger.warn("Error shutting down full-verification HTTP transport: {}", e.getMessage());
        }
        if (restrictedRegionalTransport != null) {
            try {
                restrictedRegionalTransport.shutdown();
            } catch (IOException e) {
                logger.warn("Error shutting down restricted regional HTTP transport: {}", e.getMessage());
            }
        }
    }

    private static final class HostnameMismatchIOException extends IOException {
        private HostnameMismatchIOException(String hostname, IOException cause) {
            super("Hostname verification failed for " + hostname, cause);
        }
    }
}
