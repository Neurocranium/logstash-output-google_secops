# 0.5.8
- Add thread-safe, configurable batch sampling for statistics through
  `stats_sample_rate`; invalid rates warn and fall back to full sampling

# 0.5.7
- Add the rounded-up average event size in bytes to per-log-type batch statistics
- Remove verbose HTTP response bodies from regional endpoint fallback and
  cooldown probe warnings
- Add batch-splitting guidance to warning messages and document how plugin
  `batch_size` should relate to Logstash's `pipeline.batch.size`

# 0.5.6
- Validate and UTC-normalize `logEntryTime` and `collectionTime` before batching,
  dropping malformed events locally instead of sending invalid timestamps
- Emit optional per-batch statistics as a compact JSON object with exact byte
  counts for machine-friendly log processing

# 0.5.5
- Fall back to the global-routed regional Chronicle endpoint when the preferred
  `.rep.googleapis.com` endpoint returns 404
- Probe the preferred endpoint again after 1 hour, 24 hours, and 7 days using a
  lock-free per-plugin cooldown state

# 0.5.4
- Add opt-in `ssl_verification_mode => "certificate"` to retain certificate-chain
  validation while disabling hostname matching for incompatible TLS endpoints
- Document troubleshooting for subject alternative DNS name mismatch errors

# 0.5.3
- Add support for configuring the Logstash plugin instance `id`; when omitted,
  Logstash continues to generate one
- Document the custom TLS truststore and PEM CA certificate configuration

# 0.5.2
- Update `google-auth-library-oauth2-http` from 1.30.0 to 1.48.0,
  `google-http-client` from 1.45.3 to 2.1.0, and
  `google-http-client-gson` from 1.45.3 to 2.1.0
- Add `gradle.properties` to `.gitignore` and document setup in README

# 0.5.1
- Fix credential error messages not being parsed for `GoogleAuthException`-wrapped
  `HttpResponseException` responses (e.g. expired ADC tokens)
- Add cause-chain unwrap to extract OAuth error details from the nested exception

# 0.5.0
- Rename Java package from `org.logstashplugins` to `chaos.caffeinandsarcasm.lsplugins`
- Rename `debug` config to `collect_stats` with cleaner semantics
- Improve credential initialization errors: human-readable messages for key file
  issues, ADC unavailability, OAuth rejections, and network errors
- Refactor build config, update README to match new package + config names

# 0.4.0
- Validate credentials at plugin startup via `credentials.refresh()` to fail fast
  on expired or invalid tokens instead of silently retrying and dropping batches
- Document automatic GCP authentication (Cloud Run, GCE, GKE) in README

# 0.3.0
- Validate `region` against an allowlist of 22 supported Chronicle API regions to
  prevent URL injection
- Fix 429 retry: disable `HttpRequest` default throw-on-error so the Retry-After window,
  4xx terminal handling, and 5xx backoff paths actually execute
- Enforce 4 MB request body limit by splitting oversized batches via binary
  recursion; dead-letter single entries that exceed the limit
- Bound Retry-After to 1-600 seconds and use overflow-safe duration conversion
- Add `shutdown()` coordination: poll `stopped` flag every 1s during long sleeps
  for responsive plugin shutdown
- Migrate all logging from `System.err`/`System.out` to Log4j logger
- Upgrade Log4j to 2.25.4 (matching Logstash 9.4.4)
- Replace `response.disconnect()` with `response.ignore()` on success to allow
  HTTP connection reuse
- Remove unused `shadowGradlePluginVersion` property from build script
- Throw explicit `GradleException` when `versions.yml` is missing
- Fix outdated class name in README project structure diagram

# 0.2.0
- SSL config now uses `NetHttpTransport.Builder.trustCertificates()` /
  `trustCertificatesFromStream()` instead of manual SSL socket factory setup
  for improved reliability

# 0.1.0
- Add SSL truststore support (`ssl_truststore_path`, `ssl_truststore_password`, `ssl_truststore_type`)
- Add PEM CA certificate support (`ssl_ca_cert_path`)

# 0.0.0
Initial release
