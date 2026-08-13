package chaos.caffeinandsarcasm.lsplugins.client;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EndpointSelectorTest {

    private static final String REGIONAL = "https://chronicle.europe-west3.rep.googleapis.com/v1";
    private static final String GLOBAL = "https://europe-west3-chronicle.googleapis.com/v1";

    @Test
    public void startsWithPreferredRegionalEndpoint() {
        EndpointSelector selector = new EndpointSelector("europe-west3", () -> 0L);

        EndpointSelector.Route route = selector.selectRoute();

        assertEquals(REGIONAL, route.baseUrl());
        assertTrue(route.isRegional());
        assertFalse(route.isProbe());
    }

    @Test
    public void progressesCooldownsThenUsesGlobalPermanently() {
        AtomicLong now = new AtomicLong();
        EndpointSelector selector = new EndpointSelector("europe-west3", now::get);
        assertTrue(selector.activateGlobalAfterFallback());
        assertEquals(GLOBAL, selector.selectRoute().baseUrl());

        now.addAndGet(Duration.ofHours(1).toNanos());
        assertTrue(selector.selectRoute().isProbe());
        EndpointSelector.ProbeFailure first = selector.preferredProbeFailed();
        assertEquals(Duration.ofHours(24), first.retryAfter());

        now.addAndGet(Duration.ofHours(24).toNanos());
        assertTrue(selector.selectRoute().isProbe());
        EndpointSelector.ProbeFailure second = selector.preferredProbeFailed();
        assertEquals(Duration.ofDays(7), second.retryAfter());

        now.addAndGet(Duration.ofDays(7).toNanos());
        assertTrue(selector.selectRoute().isProbe());
        assertTrue(selector.preferredProbeFailed().isPermanent());

        now.addAndGet(Duration.ofDays(365).toNanos());
        EndpointSelector.Route permanent = selector.selectRoute();
        assertEquals(GLOBAL, permanent.baseUrl());
        assertFalse(permanent.isProbe());
    }

    @Test
    public void successfulProbeRestoresAndResetsRegionalEndpoint() {
        AtomicLong now = new AtomicLong();
        EndpointSelector selector = new EndpointSelector("europe-west3", now::get);
        selector.activateGlobalAfterFallback();
        now.addAndGet(Duration.ofHours(1).toNanos());

        assertTrue(selector.selectRoute().isProbe());
        assertTrue(selector.preferredProbeSucceeded());
        assertEquals(REGIONAL, selector.selectRoute().baseUrl());

        assertTrue(selector.activateGlobalAfterFallback());
        now.addAndGet(Duration.ofHours(1).toNanos());
        assertTrue(selector.selectRoute().isProbe());
    }

    @Test
    public void onlyOneConcurrentWorkerAcquiresProbe() throws Exception {
        AtomicLong now = new AtomicLong();
        EndpointSelector selector = new EndpointSelector("europe-west3", now::get);
        selector.activateGlobalAfterFallback();
        now.addAndGet(Duration.ofHours(1).toNanos());

        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EndpointSelector.Route>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return selector.selectRoute();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int probes = 0;
            for (Future<EndpointSelector.Route> future : futures) {
                if (future.get(5, TimeUnit.SECONDS).isProbe()) {
                    probes++;
                }
            }
            assertEquals(1, probes);
        } finally {
            executor.shutdownNow();
        }
    }
}
