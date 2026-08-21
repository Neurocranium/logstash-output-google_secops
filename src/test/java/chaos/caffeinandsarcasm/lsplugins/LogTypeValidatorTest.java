package chaos.caffeinandsarcasm.lsplugins;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LogTypeValidatorTest {

    @Test
    public void acceptsTheConfiguredAlphabetWithoutALengthLimit() {
        assertTrue(LogTypeValidator.isValid("A"));
        assertTrue(LogTypeValidator.isValid("FORTINET_FIREWALL_2"));
        assertTrue(LogTypeValidator.isValid("123"));
        assertTrue(LogTypeValidator.isValid("_"));
        assertTrue(LogTypeValidator.isValid("A".repeat(10_000)));
    }

    @Test
    public void rejectsEmptyLowercaseUnicodeDelimitersAndControls() {
        List<String> invalid = List.of(
                "", "fortinet", "TYPE-name", "TYPE.NAME", "TYPE/NAME", "TYPE\\NAME",
                "TYPE%2FNAME", "TYPE?query", "TYPE#fragment", "TYPE NAME", "TÜPE",
                "TYPE\rNAME", "TYPE\nNAME", "TYPE\tNAME");

        assertFalse(LogTypeValidator.isValid(null));
        for (String value : invalid) {
            assertFalse(value, LogTypeValidator.isValid(value));
        }
    }

    @Test
    public void validatesConfiguredAndFallbackValues() {
        LogTypeValidator.requireValidConfiguration("log_type", "", true);
        LogTypeValidator.requireValidConfiguration("log_type", "CUSTOM_TYPE", true);
        LogTypeValidator.requireValidConfiguration("fallback_log_type", "CATCH_ALL", false);

        assertInvalidConfiguration("log_type", "custom_type", true);
        assertInvalidConfiguration("fallback_log_type", "", false);
        assertInvalidConfiguration("fallback_log_type", "BAD\nTYPE", false);
    }

    @Test
    public void preservesStaticDynamicAndFallbackResolution() {
        assertEquals("STATIC_TYPE", LogTypeValidator.resolve("STATIC_TYPE", "DYNAMIC_TYPE", "CATCH_ALL"));
        assertEquals("DYNAMIC_TYPE", LogTypeValidator.resolve("", "DYNAMIC_TYPE", "CATCH_ALL"));
        assertEquals("123", LogTypeValidator.resolve("", 123, "CATCH_ALL"));
        assertEquals("CATCH_ALL", LogTypeValidator.resolve("", null, "CATCH_ALL"));
        assertEquals("CATCH_ALL", LogTypeValidator.resolve("", "", "CATCH_ALL"));
        assertNull(LogTypeValidator.resolve("", "BAD/TYPE", "CATCH_ALL"));
    }

    @Test
    public void rejectsOnlyInvalidDynamicValuesAndAggregatesTheirCount() {
        List<Object> dynamicValues = List.of("VALID_ONE", "BAD/TYPE", "VALID_TWO", "BAD\nTYPE");
        LogTypeValidator.FailureSummary failures = new LogTypeValidator.FailureSummary();
        int accepted = 0;

        for (Object dynamicValue : dynamicValues) {
            if (LogTypeValidator.resolve("", dynamicValue, "CATCH_ALL") == null) {
                failures.record();
            } else {
                accepted++;
            }
        }

        assertEquals(2, accepted);
        assertFalse(failures.isEmpty());
        assertEquals(2, failures.total());
    }

    private static void assertInvalidConfiguration(String setting, String value, boolean allowEmpty) {
        try {
            LogTypeValidator.requireValidConfiguration(setting, value, allowEmpty);
            fail("Expected invalid configuration for " + setting);
        } catch (IllegalArgumentException expected) {
            assertEquals("'" + setting
                    + "' must contain only ASCII uppercase letters, digits, and underscores",
                    expected.getMessage());
        }
    }
}
