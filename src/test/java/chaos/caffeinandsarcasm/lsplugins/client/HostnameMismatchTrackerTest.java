package chaos.caffeinandsarcasm.lsplugins.client;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostnameMismatchTrackerTest {

    @Test
    public void doesNotClassifyFailuresBeforeHostnameVerificationAsMismatches() {
        HostnameMismatchTracker tracker = new HostnameMismatchTracker();

        assertFalse(tracker.consumeMismatchFor("chronicle.eu.rep.googleapis.com"));
    }

    @Test
    public void recordsAndConsumesOnlyTheCurrentThreadsMismatch() {
        HostnameMismatchTracker tracker = new HostnameMismatchTracker();
        tracker.verify("chronicle.eu.rep.googleapis.com", null);

        assertFalse(tracker.consumeMismatchFor("eu-chronicle.googleapis.com"));
        tracker.verify("chronicle.eu.rep.googleapis.com", null);
        assertTrue(tracker.consumeMismatchFor("chronicle.eu.rep.googleapis.com"));
        assertFalse(tracker.consumeMismatchFor("chronicle.eu.rep.googleapis.com"));
    }

    @Test
    public void isolatesConcurrentWorkers() throws Exception {
        HostnameMismatchTracker tracker = new HostnameMismatchTracker();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> regional = executor.submit(() -> {
                tracker.verify("chronicle.eu.rep.googleapis.com", null);
                ready.countDown();
                start.await();
                return tracker.consumeMismatchFor("chronicle.eu.rep.googleapis.com");
            });
            Future<Boolean> global = executor.submit(() -> {
                tracker.verify("eu-chronicle.googleapis.com", null);
                ready.countDown();
                start.await();
                return tracker.consumeMismatchFor("eu-chronicle.googleapis.com");
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(regional.get(5, TimeUnit.SECONDS));
            assertTrue(global.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
