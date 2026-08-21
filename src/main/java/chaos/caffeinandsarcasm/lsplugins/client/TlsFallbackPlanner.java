package chaos.caffeinandsarcasm.lsplugins.client;

final class TlsFallbackPlanner {

    enum Activation {
        NONE,
        REGIONAL_FULL,
        GLOBAL_FULL,
        REGIONAL_RESTRICTED
    }

    private final EndpointSelector endpointSelector;
    private final boolean restrictedRegionalFallbackEnabled;
    private boolean regionalFullHostnameMismatch;
    private boolean globalFullHostnameMismatch;
    private boolean regionalReturned404;

    TlsFallbackPlanner(EndpointSelector endpointSelector,
                       boolean restrictedRegionalFallbackEnabled) {
        this.endpointSelector = endpointSelector;
        this.restrictedRegionalFallbackEnabled = restrictedRegionalFallbackEnabled;
    }

    void recordRegionalHttp404() {
        regionalReturned404 = true;
    }

    Decision afterHostnameMismatch(EndpointSelector.Route failedRoute) {
        if (!failedRoute.isFullVerification()) {
            return Decision.terminal();
        }
        if (failedRoute.isRegional()) {
            regionalFullHostnameMismatch = true;
            if (globalFullHostnameMismatch) {
                return restrictedOrTerminal();
            }
            return Decision.route(endpointSelector.globalFullRoute(), Activation.GLOBAL_FULL);
        }
        if (failedRoute.isGlobal()) {
            globalFullHostnameMismatch = true;
            if (regionalReturned404) {
                return Decision.terminal();
            }
            if (regionalFullHostnameMismatch) {
                return restrictedOrTerminal();
            }
            return Decision.route(endpointSelector.regionalFullRoute(), Activation.REGIONAL_FULL);
        }
        return Decision.terminal();
    }

    private Decision restrictedOrTerminal() {
        if (!restrictedRegionalFallbackEnabled) {
            return Decision.terminal();
        }
        return Decision.route(
                endpointSelector.regionalRestrictedRoute(), Activation.REGIONAL_RESTRICTED);
    }

    static final class Decision {
        private final EndpointSelector.Route route;
        private final Activation activation;

        private Decision(EndpointSelector.Route route, Activation activation) {
            this.route = route;
            this.activation = activation;
        }

        private static Decision terminal() {
            return new Decision(null, Activation.NONE);
        }

        private static Decision route(EndpointSelector.Route route, Activation activation) {
            return new Decision(route, activation);
        }

        boolean isTerminal() {
            return route == null;
        }

        EndpointSelector.Route route() {
            return route;
        }

        Activation activation() {
            return activation;
        }
    }
}
