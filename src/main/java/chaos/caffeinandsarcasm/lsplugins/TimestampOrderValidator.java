package chaos.caffeinandsarcasm.lsplugins;

import java.time.Instant;
import java.util.Objects;

final class TimestampOrderValidator {

    private TimestampOrderValidator() {
    }

    static boolean isValid(Instant logEntryTime, Instant collectionTime) {
        Objects.requireNonNull(logEntryTime, "logEntryTime");
        Objects.requireNonNull(collectionTime, "collectionTime");
        return !collectionTime.isBefore(logEntryTime);
    }

    static final class FailureSummary {
        private int total;

        void record() {
            total++;
        }

        boolean isEmpty() {
            return total == 0;
        }

        int total() {
            return total;
        }
    }
}
