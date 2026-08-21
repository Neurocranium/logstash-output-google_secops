package chaos.caffeinandsarcasm.lsplugins;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimestampOrderValidatorTest {

    @Test
    public void acceptsEqualAndLaterCollectionTimes() {
        Instant logEntryTime = TimestampNormalizer.toInstant("2026-08-13T10:15:30.123456789Z");

        assertTrue(TimestampOrderValidator.isValid(logEntryTime, logEntryTime));
        assertTrue(TimestampOrderValidator.isValid(logEntryTime,
                TimestampNormalizer.toInstant("2026-08-13T10:15:30.123456790Z")));
    }

    @Test
    public void comparesInstantsRatherThanTimestampText() {
        Instant logEntryTime = TimestampNormalizer.toInstant("2026-08-13T10:15:30+02:00");
        Instant equalCollectionTime = TimestampNormalizer.toInstant("2026-08-13T08:15:30Z");
        Instant earlierCollectionTime = TimestampNormalizer.toInstant("2026-08-13T08:15:29.999999999Z");

        assertTrue(TimestampOrderValidator.isValid(logEntryTime, equalCollectionTime));
        assertFalse(TimestampOrderValidator.isValid(logEntryTime, earlierCollectionTime));
    }

    @Test
    public void handlesProtobufTimestampRangeBoundaries() {
        Instant minimum = TimestampNormalizer.toInstant("0001-01-01T00:00:00Z");
        Instant maximum = TimestampNormalizer.toInstant("9999-12-31T23:59:59.999999999Z");

        assertTrue(TimestampOrderValidator.isValid(minimum, maximum));
        assertFalse(TimestampOrderValidator.isValid(maximum, minimum));
    }

    @Test
    public void rejectsOnlyReversedPairsAndCountsOneBatchWarning() {
        Instant base = Instant.parse("2026-08-13T10:15:30Z");
        List<Instant> collectionTimes = List.of(base, base.minusNanos(1), base.plusNanos(1), base.minusSeconds(1));
        TimestampOrderValidator.FailureSummary failures = new TimestampOrderValidator.FailureSummary();
        int accepted = 0;

        for (Instant collectionTime : collectionTimes) {
            if (TimestampOrderValidator.isValid(base, collectionTime)) {
                accepted++;
            } else {
                failures.record();
            }
        }

        assertEquals(2, accepted);
        assertFalse(failures.isEmpty());
        assertEquals(2, failures.total());
    }
}
