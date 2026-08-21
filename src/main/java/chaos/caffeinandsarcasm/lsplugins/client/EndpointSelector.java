package chaos.caffeinandsarcasm.lsplugins.client;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

final class EndpointSelector {

    private static final long[] PROBE_DELAYS_NANOS = {
            Duration.ofHours(1).toNanos(),
            Duration.ofHours(24).toNanos(),
            Duration.ofDays(7).toNanos()
    };

    enum Endpoint {
        REGIONAL,
        GLOBAL
    }

    enum Verification {
        FULL,
        RESTRICTED
    }

    enum FallbackMode {
        GLOBAL_FULL,
        REGIONAL_RESTRICTED
    }

    private final String regionalBaseUrl;
    private final String globalBaseUrl;
    private final String regionalHostname;
    private final LongSupplier nanoTime;
    private final AtomicReference<State> state = new AtomicReference<>(State.regional());

    EndpointSelector(String region) {
        this(region, System::nanoTime);
    }

    EndpointSelector(String region, LongSupplier nanoTime) {
        this.regionalHostname = String.format("chronicle.%s.rep.googleapis.com", region);
        this.regionalBaseUrl = "https://" + regionalHostname + "/v1";
        this.globalBaseUrl = String.format("https://%s-chronicle.googleapis.com/v1", region);
        this.nanoTime = nanoTime;
    }

    Route selectRoute() {
        while (true) {
            State current = state.get();
            if (current.mode == Mode.REGIONAL) {
                return regionalFullRoute(false);
            }
            if (current.mode == Mode.PROBING) {
                return routeFor(current.fallbackMode, false);
            }
            if (current.mode == Mode.PERMANENT_FALLBACK) {
                return routeFor(current.fallbackMode, false);
            }
            if (nanoTime.getAsLong() - current.nextProbeNanos < 0) {
                return routeFor(current.fallbackMode, false);
            }
            if (state.compareAndSet(current, State.probing(current.probeDelayIndex, current.fallbackMode))) {
                return regionalFullRoute(true);
            }
        }
    }

    String regionalHostname() {
        return regionalHostname;
    }

    Route regionalFullRoute() {
        return regionalFullRoute(false);
    }

    Route globalFullRoute() {
        return new Route(globalBaseUrl, Endpoint.GLOBAL, Verification.FULL, false);
    }

    Route regionalRestrictedRoute() {
        return new Route(regionalBaseUrl, Endpoint.REGIONAL, Verification.RESTRICTED, false);
    }

    Route fallbackRouteForProbe() {
        State current = state.get();
        if (current.mode != Mode.PROBING) {
            return null;
        }
        return routeFor(current.fallbackMode, false);
    }

    boolean activateFallback(FallbackMode fallbackMode) {
        while (true) {
            State current = state.get();
            if (current.mode == Mode.FALLBACK && current.fallbackMode == fallbackMode) {
                return false;
            }
            State replacement = State.fallback(
                    0, deadlineAfter(PROBE_DELAYS_NANOS[0]), fallbackMode);
            if (state.compareAndSet(current, replacement)) {
                return true;
            }
        }
    }

    boolean preferredProbeSucceeded() {
        State current = state.get();
        return current.mode == Mode.PROBING && state.compareAndSet(current, State.regional());
    }

    boolean restoreRegional() {
        while (true) {
            State current = state.get();
            if (current.mode == Mode.REGIONAL) {
                return false;
            }
            if (state.compareAndSet(current, State.regional())) {
                return true;
            }
        }
    }

    ProbeFailure preferredProbeFailed(FallbackMode fallbackMode) {
        while (true) {
            State current = state.get();
            if (current.mode != Mode.PROBING) {
                return ProbeFailure.notApplied();
            }
            int nextIndex = current.probeDelayIndex + 1;
            State replacement;
            ProbeFailure result;
            if (nextIndex >= PROBE_DELAYS_NANOS.length) {
                replacement = State.permanentFallback(fallbackMode);
                result = ProbeFailure.permanent(fallbackMode);
            } else {
                Duration retryAfter = Duration.ofNanos(PROBE_DELAYS_NANOS[nextIndex]);
                replacement = State.fallback(
                        nextIndex, deadlineAfter(retryAfter.toNanos()), fallbackMode);
                result = ProbeFailure.retryAfter(retryAfter, fallbackMode);
            }
            if (state.compareAndSet(current, replacement)) {
                return result;
            }
        }
    }

    private Route regionalFullRoute(boolean probe) {
        return new Route(regionalBaseUrl, Endpoint.REGIONAL, Verification.FULL, probe);
    }

    private Route routeFor(FallbackMode fallbackMode, boolean probe) {
        if (fallbackMode == FallbackMode.REGIONAL_RESTRICTED) {
            return new Route(regionalBaseUrl, Endpoint.REGIONAL, Verification.RESTRICTED, probe);
        }
        return new Route(globalBaseUrl, Endpoint.GLOBAL, Verification.FULL, probe);
    }

    private long deadlineAfter(long delayNanos) {
        return nanoTime.getAsLong() + delayNanos;
    }

    static final class Route {
        private final String baseUrl;
        private final Endpoint endpoint;
        private final Verification verification;
        private final boolean probe;

        private Route(String baseUrl, Endpoint endpoint, Verification verification, boolean probe) {
            this.baseUrl = baseUrl;
            this.endpoint = endpoint;
            this.verification = verification;
            this.probe = probe;
        }

        String baseUrl() {
            return baseUrl;
        }

        boolean isRegional() {
            return endpoint == Endpoint.REGIONAL;
        }

        boolean isGlobal() {
            return endpoint == Endpoint.GLOBAL;
        }

        boolean isFullVerification() {
            return verification == Verification.FULL;
        }

        boolean isRestrictedVerification() {
            return verification == Verification.RESTRICTED;
        }

        boolean isProbe() {
            return probe;
        }
    }

    static final class ProbeFailure {
        private final boolean applied;
        private final boolean permanent;
        private final Duration retryAfter;
        private final FallbackMode fallbackMode;

        private ProbeFailure(boolean applied, boolean permanent, Duration retryAfter,
                             FallbackMode fallbackMode) {
            this.applied = applied;
            this.permanent = permanent;
            this.retryAfter = retryAfter;
            this.fallbackMode = fallbackMode;
        }

        private static ProbeFailure retryAfter(Duration retryAfter, FallbackMode fallbackMode) {
            return new ProbeFailure(true, false, retryAfter, fallbackMode);
        }

        private static ProbeFailure permanent(FallbackMode fallbackMode) {
            return new ProbeFailure(true, true, null, fallbackMode);
        }

        private static ProbeFailure notApplied() {
            return new ProbeFailure(false, false, null, null);
        }

        boolean isApplied() {
            return applied;
        }

        boolean isPermanent() {
            return permanent;
        }

        Duration retryAfter() {
            return retryAfter;
        }

        FallbackMode fallbackMode() {
            return fallbackMode;
        }
    }

    private enum Mode {
        REGIONAL,
        FALLBACK,
        PROBING,
        PERMANENT_FALLBACK
    }

    private static final class State {
        private final Mode mode;
        private final int probeDelayIndex;
        private final long nextProbeNanos;
        private final FallbackMode fallbackMode;

        private State(Mode mode, int probeDelayIndex, long nextProbeNanos,
                      FallbackMode fallbackMode) {
            this.mode = mode;
            this.probeDelayIndex = probeDelayIndex;
            this.nextProbeNanos = nextProbeNanos;
            this.fallbackMode = fallbackMode;
        }

        private static State regional() {
            return new State(Mode.REGIONAL, 0, 0, null);
        }

        private static State fallback(int probeDelayIndex, long nextProbeNanos,
                                      FallbackMode fallbackMode) {
            return new State(Mode.FALLBACK, probeDelayIndex, nextProbeNanos, fallbackMode);
        }

        private static State probing(int probeDelayIndex, FallbackMode fallbackMode) {
            return new State(Mode.PROBING, probeDelayIndex, 0, fallbackMode);
        }

        private static State permanentFallback(FallbackMode fallbackMode) {
            return new State(Mode.PERMANENT_FALLBACK, 0, 0, fallbackMode);
        }
    }
}
