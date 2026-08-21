package chaos.caffeinandsarcasm.lsplugins;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class LabelsValidator {

    private static final ValidationResult NO_LABELS = new ValidationResult(null, null);

    private LabelsValidator() {
    }

    static ValidationResult validate(Object rawLabels) {
        if (rawLabels == null) {
            return NO_LABELS;
        }
        if (!(rawLabels instanceof Map)) {
            return ValidationResult.invalid(Failure.LABELS_NOT_MAP);
        }

        Map<?, ?> labels = (Map<?, ?>) rawLabels;
        if (labels.isEmpty()) {
            return NO_LABELS;
        }

        Map<String, LogLabel> validated = new LinkedHashMap<>(labels.size());
        for (Map.Entry<?, ?> entry : labels.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                return ValidationResult.invalid(Failure.LABEL_NAME_NOT_STRING);
            }
            if (!(entry.getValue() instanceof Map)) {
                return ValidationResult.invalid(Failure.LABEL_NOT_OBJECT);
            }

            Map<?, ?> rawLabel = (Map<?, ?>) entry.getValue();
            if (rawLabel.size() > 2) {
                return ValidationResult.invalid(Failure.UNKNOWN_LABEL_FIELD);
            }
            for (Object field : rawLabel.keySet()) {
                if (!(field instanceof String)
                        || (!"value".equals(field) && !"rbacEnabled".equals(field))) {
                    return ValidationResult.invalid(Failure.UNKNOWN_LABEL_FIELD);
                }
            }

            if (!rawLabel.containsKey("value")) {
                return ValidationResult.invalid(Failure.VALUE_MISSING);
            }
            Object value = rawLabel.get("value");
            if (!(value instanceof String)) {
                return ValidationResult.invalid(Failure.VALUE_NOT_STRING);
            }

            boolean rbacEnabled = false;
            if (rawLabel.containsKey("rbacEnabled")) {
                Object rawRbacEnabled = rawLabel.get("rbacEnabled");
                if (!(rawRbacEnabled instanceof Boolean)) {
                    return ValidationResult.invalid(Failure.RBAC_ENABLED_NOT_BOOLEAN);
                }
                rbacEnabled = (Boolean) rawRbacEnabled;
            }

            validated.put((String) entry.getKey(), new LogLabel((String) value, rbacEnabled));
        }
        return ValidationResult.valid(validated);
    }

    enum Failure {
        LABELS_NOT_MAP("labels_not_map"),
        LABEL_NAME_NOT_STRING("label_name_not_string"),
        LABEL_NOT_OBJECT("label_not_object"),
        UNKNOWN_LABEL_FIELD("unknown_label_field"),
        VALUE_MISSING("value_missing"),
        VALUE_NOT_STRING("value_not_string"),
        RBAC_ENABLED_NOT_BOOLEAN("rbac_enabled_not_boolean");

        private final String description;

        Failure(String description) {
            this.description = description;
        }
    }

    static final class ValidationResult {
        private final Map<String, LogLabel> labels;
        private final Failure failure;

        private ValidationResult(Map<String, LogLabel> labels, Failure failure) {
            this.labels = labels;
            this.failure = failure;
        }

        private static ValidationResult valid(Map<String, LogLabel> labels) {
            return new ValidationResult(labels, null);
        }

        private static ValidationResult invalid(Failure failure) {
            return new ValidationResult(null, failure);
        }

        boolean isValid() {
            return failure == null;
        }

        Map<String, LogLabel> labels() {
            return labels;
        }

        Failure failure() {
            return failure;
        }
    }

    static final class FailureSummary {
        private final EnumMap<Failure, Integer> counts = new EnumMap<>(Failure.class);
        private int total;

        void record(Failure failure) {
            counts.merge(failure, 1, Integer::sum);
            total++;
        }

        boolean isEmpty() {
            return total == 0;
        }

        int total() {
            return total;
        }

        String describe() {
            StringBuilder description = new StringBuilder();
            for (Failure failure : Failure.values()) {
                Integer count = counts.get(failure);
                if (count == null) {
                    continue;
                }
                if (description.length() > 0) {
                    description.append(", ");
                }
                description.append(failure.description).append('=').append(count);
            }
            return description.toString();
        }
    }
}
