package chaos.caffeinandsarcasm.lsplugins;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

final class TimestampNormalizer {

    private static final Pattern RFC_3339_TIMESTAMP = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                    + "(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$");
    private static final Instant MIN_PROTOBUF_TIMESTAMP = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_PROTOBUF_TIMESTAMP = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private TimestampNormalizer() {
    }

    static String normalize(Object value) {
        if (value == null) {
            return null;
        }

        Instant instant;
        if (value instanceof Instant) {
            instant = (Instant) value;
        } else {
            String text = value.toString();
            if (!RFC_3339_TIMESTAMP.matcher(text).matches()) {
                throw new IllegalArgumentException("timestamp must be RFC 3339 with a Z or +/-HH:MM timezone");
            }
            try {
                instant = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("timestamp contains an invalid date, time, or timezone offset", e);
            }
        }

        if (instant.isBefore(MIN_PROTOBUF_TIMESTAMP) || instant.isAfter(MAX_PROTOBUF_TIMESTAMP)) {
            throw new IllegalArgumentException("timestamp is outside the protobuf Timestamp range");
        }
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
