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

    private final String regionalBaseUrl;
    private final String globalBaseUrl;
    private final LongSupplier nanoTime;
    private final AtomicReference<State> state = new AtomicReference<>(State.regional());

    EndpointSelector(String region) {
        this(region, System::nanoTime);
    }

    EndpointSelector(String region, LongSupplier nanoTime) {
        this.regionalBaseUrl = String.format("https://chronicle.%s.rep.googleapis.com/v1", region);
        this.globalBaseUrl = String.format("https://%s-chronicle.googleapis.com/v1", region);
        this.nanoTime = nanoTime;
    }

    Route selectRoute() {
        while (true) {
            State current = state.get();
            if (current.mode == Mode.REGIONAL) {
                return new Route(regionalBaseUrl, true, false);
            }
            if (current.mode == Mode.PROBING || current.mode == Mode.PERMANENT_GLOBAL) {
                return globalRoute();
            }
            if (nanoTime.getAsLong() - current.nextProbeNanos < 0) {
                return globalRoute();
            }
            if (state.compareAndSet(current, State.probing(current.probeDelayIndex))) {
                return new Route(regionalBaseUrl, true, true);
            }
        }
    }

    Route globalRoute() {
        return new Route(globalBaseUrl, false, false);
    }

    boolean activateGlobalAfterFallback() {
        State current = state.get();
        return current.mode == Mode.REGIONAL
                && state.compareAndSet(current, State.global(0, deadlineAfter(PROBE_DELAYS_NANOS[0])));
    }

    boolean preferredProbeSucceeded() {
        State current = state.get();
        return current.mode == Mode.PROBING && state.compareAndSet(current, State.regional());
    }

    ProbeFailure preferredProbeFailed() {
        while (true) {
            State current = state.get();
            if (current.mode != Mode.PROBING) {
                return ProbeFailure.notApplied();
            }
            int nextIndex = current.probeDelayIndex + 1;
            State replacement;
            ProbeFailure result;
            if (nextIndex >= PROBE_DELAYS_NANOS.length) {
                replacement = State.permanentGlobal();
                result = ProbeFailure.permanent();
            } else {
                Duration retryAfter = Duration.ofNanos(PROBE_DELAYS_NANOS[nextIndex]);
                replacement = State.global(nextIndex, deadlineAfter(retryAfter.toNanos()));
                result = ProbeFailure.retryAfter(retryAfter);
            }
            if (state.compareAndSet(current, replacement)) {
                return result;
            }
        }
    }

    private long deadlineAfter(long delayNanos) {
        return nanoTime.getAsLong() + delayNanos;
    }

    static final class Route {
        private final String baseUrl;
        private final boolean regional;
        private final boolean probe;

        private Route(String baseUrl, boolean regional, boolean probe) {
            this.baseUrl = baseUrl;
            this.regional = regional;
            this.probe = probe;
        }

        String baseUrl() {
            return baseUrl;
        }

        boolean isRegional() {
            return regional;
        }

        boolean isProbe() {
            return probe;
        }
    }

    static final class ProbeFailure {
        private final boolean applied;
        private final boolean permanent;
        private final Duration retryAfter;

        private ProbeFailure(boolean applied, boolean permanent, Duration retryAfter) {
            this.applied = applied;
            this.permanent = permanent;
            this.retryAfter = retryAfter;
        }

        private static ProbeFailure retryAfter(Duration retryAfter) {
            return new ProbeFailure(true, false, retryAfter);
        }

        private static ProbeFailure permanent() {
            return new ProbeFailure(true, true, null);
        }

        private static ProbeFailure notApplied() {
            return new ProbeFailure(false, false, null);
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
    }

    private enum Mode {
        REGIONAL,
        GLOBAL,
        PROBING,
        PERMANENT_GLOBAL
    }

    private static final class State {
        private final Mode mode;
        private final int probeDelayIndex;
        private final long nextProbeNanos;

        private State(Mode mode, int probeDelayIndex, long nextProbeNanos) {
            this.mode = mode;
            this.probeDelayIndex = probeDelayIndex;
            this.nextProbeNanos = nextProbeNanos;
        }

        private static State regional() {
            return new State(Mode.REGIONAL, 0, 0);
        }

        private static State global(int probeDelayIndex, long nextProbeNanos) {
            return new State(Mode.GLOBAL, probeDelayIndex, nextProbeNanos);
        }

        private static State probing(int probeDelayIndex) {
            return new State(Mode.PROBING, probeDelayIndex, 0);
        }

        private static State permanentGlobal() {
            return new State(Mode.PERMANENT_GLOBAL, 0, 0);
        }
    }
}
