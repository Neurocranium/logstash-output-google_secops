package chaos.caffeinandsarcasm.lsplugins;

final class LogTypeValidator {

    private LogTypeValidator() {
    }

    static boolean isValid(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < 'A' || character > 'Z')
                    && (character < '0' || character > '9')
                    && character != '_') {
                return false;
            }
        }
        return true;
    }

    static void requireValidConfiguration(String setting, String value, boolean allowEmpty) {
        if (allowEmpty && (value == null || value.isEmpty())) {
            return;
        }
        if (!isValid(value)) {
            throw new IllegalArgumentException("'" + setting
                    + "' must contain only ASCII uppercase letters, digits, and underscores");
        }
    }

    static String resolve(String configuredLogType, Object dynamicValue, String fallbackLogType) {
        if (configuredLogType != null && !configuredLogType.isEmpty()) {
            return isValid(configuredLogType) ? configuredLogType : null;
        }
        if (dynamicValue != null) {
            String value = dynamicValue.toString();
            if (!value.isEmpty()) {
                return isValid(value) ? value : null;
            }
        }
        return isValid(fallbackLogType) ? fallbackLogType : null;
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
