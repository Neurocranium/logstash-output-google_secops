package chaos.caffeinandsarcasm.lsplugins.client;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegionalGoogleApisHostnameVerifierTest {

    @Test
    public void limitsRestrictedVerificationToTheExactRegionalHostname() {
        RegionalGoogleApisHostnameVerifier verifier = new RegionalGoogleApisHostnameVerifier(
                "chronicle.eu.rep.googleapis.com");

        assertTrue(verifier.isExpectedRegionalHostname("chronicle.eu.rep.googleapis.com"));
        assertTrue(verifier.isExpectedRegionalHostname("CHRONICLE.EU.REP.GOOGLEAPIS.COM."));
        assertFalse(verifier.isExpectedRegionalHostname("eu-chronicle.googleapis.com"));
        assertFalse(verifier.isExpectedRegionalHostname("chronicle.us.rep.googleapis.com"));
        assertFalse(verifier.isExpectedRegionalHostname("example.com"));
    }

    @Test
    public void acceptsDnsNamesInGoogleApisNamespace() {
        assertTrue(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName("googleapis.com"));
        assertTrue(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName("service.googleapis.com"));
        assertTrue(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName(
                "chronicle.us.rep.googleapis.com"));
        assertTrue(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName("*.googleapis.com"));
        assertTrue(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName("*.rep.googleapis.com."));
    }

    @Test
    public void rejectsUnrelatedLookalikeAndMalformedDnsNames() {
        assertFalse(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName("example.com"));
        assertFalse(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName("evilgoogleapis.com"));
        assertFalse(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName(
                "googleapis.com.example.net"));
        assertFalse(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName("*.*.googleapis.com"));
        assertFalse(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName("foo_bar.googleapis.com"));
        assertFalse(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName(" googleapis.com"));
        assertFalse(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName("googleapis.com.."));
        assertFalse(RegionalGoogleApisHostnameVerifier.isPermittedGoogleApisDnsName(""));
    }

    @Test
    public void acceptsOnlyDnsSubjectAlternativeNames() {
        List<List<?>> mixedSans = Arrays.asList(
                Arrays.asList(7, "192.0.2.1"),
                Arrays.asList(2, "chronicle.eu.rep.googleapis.com"));
        assertTrue(RegionalGoogleApisHostnameVerifier.hasPermittedGoogleApisDnsSan(mixedSans));

        assertFalse(RegionalGoogleApisHostnameVerifier.hasPermittedGoogleApisDnsSan(
                Collections.singletonList(Arrays.asList(7, "192.0.2.1"))));
        assertFalse(RegionalGoogleApisHostnameVerifier.hasPermittedGoogleApisDnsSan(
                Collections.singletonList(Arrays.asList(2, "example.com"))));
        assertFalse(RegionalGoogleApisHostnameVerifier.hasPermittedGoogleApisDnsSan(null));
    }
}
