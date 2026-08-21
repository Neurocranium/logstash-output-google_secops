package chaos.caffeinandsarcasm.lsplugins.client;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

final class HostnameMismatchTracker implements HostnameVerifier {

    private final ThreadLocal<String> mismatchedHostname = new ThreadLocal<>();

    void clear() {
        mismatchedHostname.remove();
    }

    boolean consumeMismatchFor(String hostname) {
        String mismatch = mismatchedHostname.get();
        mismatchedHostname.remove();
        return mismatch != null && mismatch.equalsIgnoreCase(hostname);
    }

    @Override
    public boolean verify(String hostname, SSLSession session) {
        mismatchedHostname.set(hostname);
        return false;
    }
}
