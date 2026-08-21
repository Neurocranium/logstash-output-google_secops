package chaos.caffeinandsarcasm.lsplugins.client;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StatsSamplerTest {

    @Test
    public void acceptsInclusiveRateRangeAndDefaultsInvalidValuesToFullSampling() {
        new StatsSampler(true, 0.0);
        new StatsSampler(true, 0.25);
        new StatsSampler(true, 1.0);

        assertInvalidRateDefaultsToFullSampling(-0.01);
        assertInvalidRateDefaultsToFullSampling(1.01);
        assertInvalidRateDefaultsToFullSampling(Double.NaN);
        assertInvalidRateDefaultsToFullSampling(Double.NEGATIVE_INFINITY);
        assertInvalidRateDefaultsToFullSampling(Double.POSITIVE_INFINITY);
    }

    @Test
    public void usesStrictThresholdForIntermediateRates() {
        assertTrue(new StatsSampler(true, 0.25, () -> 0.249999).shouldCollect());
        assertFalse(new StatsSampler(true, 0.25, () -> 0.25).shouldCollect());
        assertFalse(new StatsSampler(true, 0.25, () -> 0.9).shouldCollect());
    }

    @Test
    public void disabledAndEndpointRatesDoNotDrawRandomValues() {
        AtomicInteger draws = new AtomicInteger();

        assertFalse(new StatsSampler(false, 0.5, () -> {
            draws.incrementAndGet();
            return 0.0;
        }).shouldCollect());
        assertFalse(new StatsSampler(true, 0.0, () -> {
            draws.incrementAndGet();
            return 0.0;
        }).shouldCollect());
        assertTrue(new StatsSampler(true, 1.0, () -> {
            draws.incrementAndGet();
            return 0.0;
        }).shouldCollect());

        assertEquals(0, draws.get());
    }

    @Test
    public void productionSamplerSupportsConcurrentCalls() throws Exception {
        StatsSampler sampler = new StatsSampler(true, 0.5);
        int workers = 8;
        int iterations = 10_000;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    int sampled = 0;
                    for (int j = 0; j < iterations; j++) {
                        if (sampler.shouldCollect()) {
                            sampled++;
                        }
                    }
                    return sampled;
                }));
            }
            start.countDown();

            int sampled = 0;
            for (Future<Integer> future : futures) {
                sampled += future.get(5, TimeUnit.SECONDS);
            }
            assertTrue(sampled >= 0 && sampled <= workers * iterations);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertInvalidRateDefaultsToFullSampling(double rate) {
        AtomicInteger draws = new AtomicInteger();
        List<String> warnings = new ArrayList<>();
        StatsSampler sampler = new StatsSampler(true, rate, () -> {
            draws.incrementAndGet();
            return 0.99;
        }, warnings::add);

        assertTrue(sampler.shouldCollect());
        assertEquals(0, draws.get());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Invalid stats_sample_rate " + rate));
        assertTrue(warnings.get(0).contains("using 1.0"));
    }
}
