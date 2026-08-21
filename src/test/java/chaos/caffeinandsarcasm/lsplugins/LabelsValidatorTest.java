package chaos.caffeinandsarcasm.lsplugins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LabelsValidatorTest {

    @Test
    public void acceptsDocumentedShapeAndDefaultsRbacToFalse() {
        Map<String, Object> rawLabels = new LinkedHashMap<>();
        rawLabels.put("environment", Map.of("value", "production", "rbacEnabled", true));
        rawLabels.put("source", Map.of("value", "logstash"));

        LabelsValidator.ValidationResult result = LabelsValidator.validate(rawLabels);

        assertTrue(result.isValid());
        assertEquals("production", result.labels().get("environment").getValue());
        assertTrue(result.labels().get("environment").isRbacEnabled());
        assertFalse(result.labels().get("source").isRbacEnabled());

        JsonObject json = JsonParser.parseString(new com.google.gson.Gson().toJson(result.labels()))
                .getAsJsonObject();
        assertEquals("production", json.getAsJsonObject("environment").get("value").getAsString());
        assertTrue(json.getAsJsonObject("environment").get("rbacEnabled").getAsBoolean());
        assertFalse(json.getAsJsonObject("source").get("rbacEnabled").getAsBoolean());
    }

    @Test
    public void treatsMissingNullAndEmptyLabelsAsValidAndAbsent() {
        LabelsValidator.ValidationResult missing = LabelsValidator.validate(null);
        LabelsValidator.ValidationResult empty = LabelsValidator.validate(Map.of());

        assertTrue(missing.isValid());
        assertNull(missing.labels());
        assertTrue(empty.isValid());
        assertNull(empty.labels());
    }

    @Test
    public void rejectsEveryUnsupportedShapeWithoutTraversingNestedValues() {
        assertFailure("not a map", LabelsValidator.Failure.LABELS_NOT_MAP);
        assertFailure(Map.of(7, Map.of("value", "x")), LabelsValidator.Failure.LABEL_NAME_NOT_STRING);
        assertFailure(Map.of("name", "value"), LabelsValidator.Failure.LABEL_NOT_OBJECT);
        assertFailure(Map.of("name", Map.of("rbacEnabled", true)), LabelsValidator.Failure.VALUE_MISSING);
        assertFailure(Map.of("name", Map.of("value", 7)), LabelsValidator.Failure.VALUE_NOT_STRING);
        assertFailure(Map.of("name", Map.of("value", "x", "rbacEnabled", "true")),
                LabelsValidator.Failure.RBAC_ENABLED_NOT_BOOLEAN);
        assertFailure(Map.of("name", Map.of("value", "x", "extra", true)),
                LabelsValidator.Failure.UNKNOWN_LABEL_FIELD);

        Map<String, Object> deeplyNested = new LinkedHashMap<>();
        Map<String, Object> cursor = deeplyNested;
        for (int i = 0; i < 10_000; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put("child", child);
            cursor = child;
        }
        assertFailure(Map.of("name", Map.of("value", deeplyNested)),
                LabelsValidator.Failure.VALUE_NOT_STRING);
    }

    @Test
    public void acceptsLargeFlatMapsWithoutAnArbitraryLabelCountLimit() {
        Map<String, Object> rawLabels = new LinkedHashMap<>();
        for (int i = 0; i < 10_000; i++) {
            rawLabels.put("label-" + i, Map.of("value", "value-" + i));
        }

        LabelsValidator.ValidationResult result = LabelsValidator.validate(rawLabels);

        assertTrue(result.isValid());
        assertEquals(10_000, result.labels().size());
    }

    @Test
    public void aggregatesFailureReasonsForOneBatchWarning() {
        LabelsValidator.FailureSummary summary = new LabelsValidator.FailureSummary();
        summary.record(LabelsValidator.Failure.LABELS_NOT_MAP);
        summary.record(LabelsValidator.Failure.VALUE_NOT_STRING);
        summary.record(LabelsValidator.Failure.LABELS_NOT_MAP);

        assertFalse(summary.isEmpty());
        assertEquals(3, summary.total());
        assertEquals("labels_not_map=2, value_not_string=1", summary.describe());
    }

    private static void assertFailure(Object rawLabels, LabelsValidator.Failure expected) {
        LabelsValidator.ValidationResult result = LabelsValidator.validate(rawLabels);
        assertFalse(result.isValid());
        assertEquals(expected, result.failure());
        assertNull(result.labels());
    }
}
