package chaos.caffeinandsarcasm.lsplugins;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class TimestampNormalizerTest {

    @Test
    public void normalizesUtcTimestampsAtSupportedPrecisions() {
        assertEquals("2026-08-13T10:15:30Z", TimestampNormalizer.normalize("2026-08-13T10:15:30Z"));
        assertEquals("2026-08-13T10:15:30.123Z", TimestampNormalizer.normalize("2026-08-13T10:15:30.123Z"));
        assertEquals("2026-08-13T10:15:30.123456Z", TimestampNormalizer.normalize("2026-08-13T10:15:30.123456Z"));
        assertEquals("2026-08-13T10:15:30.123456789Z",
                TimestampNormalizer.normalize("2026-08-13T10:15:30.123456789Z"));
    }

    @Test
    public void normalizesPositiveAndNegativeOffsetsToUtc() {
        assertEquals("2026-08-13T08:15:30Z", TimestampNormalizer.normalize("2026-08-13T10:15:30+02:00"));
        assertEquals("2026-08-13T15:45:30Z", TimestampNormalizer.normalize("2026-08-13T10:15:30-05:30"));
    }

    @Test
    public void normalizesInstantAndPreservesMissingValue() {
        assertEquals("2026-08-13T10:15:30.123456789Z",
                TimestampNormalizer.normalize(Instant.parse("2026-08-13T10:15:30.123456789Z")));
        assertNull(TimestampNormalizer.normalize(null));
    }

    @Test
    public void acceptsProtobufTimestampRangeBoundaries() {
        assertEquals("0001-01-01T00:00:00Z", TimestampNormalizer.normalize("0001-01-01T00:00:00Z"));
        assertEquals("9999-12-31T23:59:59.999999999Z",
                TimestampNormalizer.normalize("9999-12-31T23:59:59.999999999Z"));
    }

    @Test
    public void rejectsMalformedTimestampStrings() {
        assertInvalid("");
        assertInvalid("2026-08-13T10:15:30");
        assertInvalid("2026-08-13");
        assertInvalid("2026-02-30T10:15:30Z");
        assertInvalid("2026-08-13T10:15:30+25:00");
        assertInvalid(" 2026-08-13T10:15:30Z");
        assertInvalid("2026-08-13T10:15:30Z ");
        assertInvalid("2026-08-13T10:15:30.1234567890Z");
    }

    @Test
    public void rejectsValuesOutsideProtobufTimestampRange() {
        assertInvalid(Instant.parse("0000-12-31T23:59:59.999999999Z"));
        assertInvalid(Instant.parse("+10000-01-01T00:00:00Z"));
        assertInvalid("0001-01-01T00:00:00+01:00");
        assertInvalid("9999-12-31T23:59:59-01:00");
    }

    private static void assertInvalid(Object value) {
        try {
            TimestampNormalizer.normalize(value);
            fail("Expected timestamp to be rejected: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
