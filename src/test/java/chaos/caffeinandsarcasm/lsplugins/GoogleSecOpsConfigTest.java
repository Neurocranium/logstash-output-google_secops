package chaos.caffeinandsarcasm.lsplugins;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GoogleSecOpsConfigTest {

    @Test
    public void exposesStatsSampleRateWithFullSamplingDefault() {
        assertEquals("stats_sample_rate", GoogleSecOps.STATS_SAMPLE_RATE_CONFIG.name());
        assertEquals(Double.class, GoogleSecOps.STATS_SAMPLE_RATE_CONFIG.type());
        assertEquals(1.0, GoogleSecOps.STATS_SAMPLE_RATE_CONFIG.defaultValue(), 0.0);
    }
}
