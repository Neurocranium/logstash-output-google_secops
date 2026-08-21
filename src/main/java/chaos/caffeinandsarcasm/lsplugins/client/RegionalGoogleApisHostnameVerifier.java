package chaos.caffeinandsarcasm.lsplugins.client;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class RegionalGoogleApisHostnameVerifier implements HostnameVerifier {

    private static final int DNS_SAN_TYPE = 2;
    private static final String GOOGLE_APIS_DOMAIN = "googleapis.com";

    private final String regionalHostname;

    RegionalGoogleApisHostnameVerifier(String regionalHostname) {
        this.regionalHostname = normalizeDnsName(regionalHostname);
    }

    @Override
    public boolean verify(String hostname, SSLSession session) {
        if (!isExpectedRegionalHostname(hostname)) {
            return false;
        }
        try {
            Certificate[] peerCertificates = session.getPeerCertificates();
            if (peerCertificates.length == 0 || !(peerCertificates[0] instanceof X509Certificate)) {
                return false;
            }
            return hasPermittedGoogleApisDnsSan(
                    ((X509Certificate) peerCertificates[0]).getSubjectAlternativeNames());
        } catch (SSLPeerUnverifiedException | CertificateParsingException e) {
            return false;
        }
    }

    boolean isExpectedRegionalHostname(String hostname) {
        return regionalHostname.equals(normalizeDnsName(hostname));
    }

    static boolean hasPermittedGoogleApisDnsSan(Collection<List<?>> subjectAlternativeNames) {
        if (subjectAlternativeNames == null) {
            return false;
        }
        for (List<?> san : subjectAlternativeNames) {
            if (san == null || san.size() < 2 || !(san.get(0) instanceof Integer)
                    || ((Integer) san.get(0)) != DNS_SAN_TYPE || !(san.get(1) instanceof String)) {
                continue;
            }
            if (isPermittedGoogleApisDnsName((String) san.get(1))) {
                return true;
            }
        }
        return false;
    }

    static boolean isPermittedGoogleApisDnsName(String value) {
        String normalized = normalizeDnsName(value);
        if (normalized.isEmpty()) {
            return false;
        }

        int wildcard = normalized.indexOf('*');
        if (wildcard >= 0) {
            if (!normalized.startsWith("*.") || normalized.indexOf('*', 1) >= 0) {
                return false;
            }
            normalized = normalized.substring(2);
        }

        if (!isValidDnsName(normalized)) {
            return false;
        }
        return normalized.equals(GOOGLE_APIS_DOMAIN)
                || normalized.endsWith("." + GOOGLE_APIS_DOMAIN);
    }

    private static String normalizeDnsName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isValidDnsName(String value) {
        if (value.isEmpty() || value.length() > 253) {
            return false;
        }
        String[] labels = value.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || !isAsciiLetterOrDigit(label.charAt(0))
                    || !isAsciiLetterOrDigit(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int i = 1; i < label.length() - 1; i++) {
                char character = label.charAt(i);
                if (!isAsciiLetterOrDigit(character) && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char value) {
        return value >= 'a' && value <= 'z' || value >= '0' && value <= '9';
    }
}
