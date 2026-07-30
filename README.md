# Logstash Output Plugin for Google Security Operations (SecOps)

A Java-based Logstash output plugin that sends log events to Google Security Operations
(formerly Chronicle) using the modern `logs.import` REST API (`v1`).

## Features

- Uses the `logs.import` API endpoint (`POST .../logs:import`)
- Supports Application Default Credentials (ADC)
- Supports service account key files
- Configurable log type: hard-coded or per-event from a field
- Configurable data field, timestamp fields, and labels
- Batching with configurable batch size
- 429 retry strategy with Retry-After compliance (5-minute burst window)
- Exponential backoff for 5xx and network errors
- Statistics collection with per-batch statistics (events, byte sizes, API calls)

## Requirements

- Java 11+
- Logstash 7.2+ (with Java plugin support enabled)
- Gradle (optional — the Gradle wrapper is included)

## Installation

### 1. Build Logstash core jar

The plugin requires the `logstash-core.jar` from your Logstash installation.
Clone the Logstash repository matching your Logstash version and build it:

```bash
git clone https://github.com/elastic/logstash.git
cd logstash
./gradlew assemble
```

### 2. Configure the plugin build

Edit `gradle.properties` in the plugin root directory and set `LOGSTASH_CORE_PATH`
to point to the directory containing `logstash-core.jar`:

```properties
LOGSTASH_CORE_PATH=/path/to/logstash/logstash-core/build/libs
```

Then build the plugin:

```bash
./gradlew gem
```

This produces a `.gem` file in `build/` that can be installed into Logstash.

### 3. Install the plugin

```bash
/path/to/logstash/bin/logstash-plugin install --no-verify --local build/logstash-output-google_secops-1.0.0.gem
```

## Configuration

### Minimal configuration (ADC authentication)

```ruby
output {
    google_secops {
        project_id  => "my-gcp-project"
        instance_id => "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        region      => "us"
    }
}
```

### Full configuration

```ruby
output {
    google_secops {
        # Required
        project_id             => "my-gcp-project"
        instance_id            => "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

        # Authentication (omit service_account_key_path to use ADC)
        service_account_key_path => "/etc/secrets/secops-sa-key.json"

        # Log type strategy — either hard-coded or per-event
        log_type               => "OKTA"                        # optional, overrides per-event
        log_type_field         => "[log_type]"                  # per-event field (default)
        fallback_log_type      => "CATCH_ALL"                   # fallback if field missing

        # Event mapping
        data_field             => "message"                     # field to send as log data
        log_entry_time_field   => "@timestamp"                  # field for log entry time
        collection_time_field  => "[event][created]"            # field for collection time
        labels_field           => "[secops_labels]"             # optional, Map field for labels

        # Forwarder metadata
        forwarder_id           => "my-forwarder"
        source_filename        => "source.log"

        # Batching and retry
        batch_size             => 500
        max_retries            => 3

        # Region
        region                 => "us"

        # Stats Collection
        collect_stats          => true
    }
}
```

## Configuration Reference

| Setting | Type | Required | Default | Description |
|---|---|---|---|---|
| `project_id` | string | yes | — | GCP project ID |
| `instance_id` | string | yes | — | SecOps instance UUID |
| `region` | string | no | `"us"` | Chronicle API region (see [Supported Regions](#supported-regions) below) |
| `log_type` | string | no | — | Hard-coded log type. If set, all events use this type. |
| `log_type_field` | string | no | `"[log_type]"` | Event field to read the log type from (used when `log_type` is unset) |
| `fallback_log_type` | string | no | `"CATCH_ALL"` | Fallback log type when the dynamic field is missing |
| `data_field` | string | no | `"message"` | Event field whose value becomes the base64-encoded log data |
| `log_entry_time_field` | string | no | `"@timestamp"` | Event field for the log entry timestamp (RFC 3339) |
| `collection_time_field` | string | no | `"[event][created]"` | Event field for collection time. Falls back to `logEntryTime` value |
| `labels_field` | string | no | — | Event field containing a key-value map for SecOps labels |
| `forwarder_id` | string | no | — | Forwarder identifier sent in the API request |
| `source_filename` | string | no | — | Source file name sent in the API request |
| `service_account_key_path` | path | no | — | Path to a GCP service account JSON key. Omit to use ADC |
| `batch_size` | number | no | `500` | Max log entries per API request (max 4 MB uncompressed) |
| `max_retries` | number | no | `3` | Retries for 5xx and network errors (exponential backoff) |
| `collect_stats` | boolean | no | `false` | Enable per-batch statistics |

## Supported Regions

The `region` setting is validated against an allowlist. The following
Chronicle API regions are supported:

| Region |
|--------|
| `us` |
| `eu` |
| `europe` |
| `africa-south1` |
| `asia-east1` |
| `asia-northeast1` |
| `asia-northeast3` |
| `asia-south1` |
| `asia-southeast1` |
| `asia-southeast2` |
| `australia-southeast1` |
| `europe-central2` |
| `europe-west12` |
| `europe-west2` |
| `europe-west3` |
| `europe-west6` |
| `europe-west9` |
| `me-central1` |
| `me-central2` |
| `me-west1` |
| `northamerica-northeast2` |
| `southamerica-east1` |

## Authentication

The plugin supports two authentication methods:

### Application Default Credentials (ADC)

Used when `service_account_key_path` is not set. ADC searches the following
locations in order:

1. `GOOGLE_APPLICATION_CREDENTIALS` environment variable
2. Credentials from `gcloud auth application-default login`
3. Attached service account (GCE, GKE, Cloud Run, Cloud Functions, etc.)

This is the recommended authentication method. On GCP compute platforms (Cloud
Run, Compute Engine, GKE) the plugin authenticates automatically using the
resource's attached service account — no configuration is needed.

```bash
gcloud auth application-default login
```

### Service Account Key File

Set `service_account_key_path` to the path of a JSON service account key file.
The plugin loads this file directly to authenticate.

The service account must have the IAM permission `chronicle.logs.import` on the
SecOps instance.

## Log Type Resolution

The plugin resolves the log type for each event as follows:

1. If `log_type` is configured → all events use that type
2. If `log_type` is not configured → read the field specified by `log_type_field`
   from each event
3. If the field is missing or empty → use `fallback_log_type`

Events with different log types are sent in separate API calls.

## Timestamp Handling

- `log_entry_time_field`: Defaults to `@timestamp`. Use any event field
  containing a valid RFC 3339 timestamp.
- `collection_time_field`: Defaults to `[event][created]` (ECS convention).
  Falls back to the `logEntryTime` value if the field is missing, ensuring
  `collectionTime >= logEntryTime` as required by the API.

## Retry Strategy

| Status | Behavior |
|---|---|
| **200-299** | Success |
| **429** | Read `Retry-After` header (default 300s). Block and wait once, retry. If still 429, log and drop. |
| **400-499 (non-429)** | Log error and drop batch (fatal client error) |
| **500-599** | Exponential backoff (1s, 2s, 4s) up to `max_retries`, then log and drop |
| **Timeout / IO error** | Exponential backoff up to `max_retries`, then log and drop |

## Stats Collection

When `collect_stats => true`, the plugin prints statistics for every `output()` call:

```
[google_secops] Batch stats (257 events, 2 log type group(s)):
  OKTA: 2 API call(s), 150 events, 132.0 KB total
    [1/2] 100 ev, 65.0 KB, 200 (120ms)
    [2/2] 50 ev, 33.0 KB, 200 (85ms)
  WINEVTLOG_XML: 1 API call(s), 107 events, 67.0 KB total
    [1/1] 107 ev, 67.0 KB, 200 (95ms)
```

This helps tune `batch_size` to stay within the 4 MB uncompressed limit.

## API Limits

- Maximum uncompressed request body: **4 MB**
- Burst rate limits vary by instance. The plugin handles 429 responses with a
  5-minute cooling period.
- Monitor the SecOps Health Hub dashboard for ingestion errors.

## Development

### Project structure

```
logstash-output-google_secops/
├── build.gradle                    # Build configuration
├── VERSION                         # Plugin version
├── gradle.properties               # Set LOGSTASH_CORE_PATH here
├── src/main/java/chaos/caffeinandsarcasm/lsplugins/
│   ├── GoogleSecOps.java           # Main plugin class
│   ├── LogEntry.java               # Log entry model
│   └── client/
│       ├── SecOpsApiClient.java    # HTTP client with auth + retry
│       └── StatsCollector.java     # Per-batch statistics collector
└── README.md
```

### Prerequisites

Before building, create `gradle.properties` in the project root with the path to
a pre-built `logstash-core.jar`:

```properties
LOGSTASH_CORE_PATH=/path/to/logstash/logstash-core/build/libs
```

See the [Logstash plugin documentation](https://www.elastic.co/guide/en/logstash/current/output-java.html)
for instructions on building Logstash from source.

### Building

```bash
./gradlew gem
```

The `.gem` file is written to `build/`.

### Testing

```bash
./gradlew test
```

## License

Apache 2.0
