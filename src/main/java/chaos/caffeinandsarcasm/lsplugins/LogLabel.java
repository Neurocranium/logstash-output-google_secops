package chaos.caffeinandsarcasm.lsplugins;

import java.util.Objects;

public final class LogLabel {

    private final String value;
    private final boolean rbacEnabled;

    public LogLabel(String value, boolean rbacEnabled) {
        this.value = Objects.requireNonNull(value, "value");
        this.rbacEnabled = rbacEnabled;
    }

    public String getValue() {
        return value;
    }

    public boolean isRbacEnabled() {
        return rbacEnabled;
    }
}
