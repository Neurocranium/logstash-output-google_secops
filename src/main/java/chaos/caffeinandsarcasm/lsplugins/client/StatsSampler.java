package chaos.caffeinandsarcasm.lsplugins.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

public final class StatsSampler {

    private static final Logger LOGGER = LogManager.getLogger(StatsSampler.class);

    private final boolean enabled;
    private final double sampleRate;
    private final DoubleSupplier randomSource;

    public StatsSampler(boolean enabled, double sampleRate) {
        this(enabled, sampleRate, () -> ThreadLocalRandom.current().nextDouble(), message -> LOGGER.warn(message));
    }

    StatsSampler(boolean enabled, double sampleRate, DoubleSupplier randomSource) {
        this(enabled, sampleRate, randomSource, message -> LOGGER.warn(message));
    }

    StatsSampler(boolean enabled, double sampleRate, DoubleSupplier randomSource, Consumer<String> warningSink) {
        this.randomSource = Objects.requireNonNull(randomSource, "randomSource");
        Consumer<String> warnings = Objects.requireNonNull(warningSink, "warningSink");
        double effectiveRate = sampleRate;
        if (!Double.isFinite(sampleRate) || sampleRate < 0.0 || sampleRate > 1.0) {
            warnings.accept("Invalid stats_sample_rate " + sampleRate
                    + ". Expected a finite value between 0.0 and 1.0 inclusive; using 1.0.");
            effectiveRate = 1.0;
        }
        this.enabled = enabled;
        this.sampleRate = effectiveRate;
    }

    public boolean shouldCollect() {
        if (!enabled || sampleRate == 0.0) {
            return false;
        }
        if (sampleRate == 1.0) {
            return true;
        }
        return randomSource.getAsDouble() < sampleRate;
    }
}
