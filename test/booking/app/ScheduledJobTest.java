package booking.app;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledJobTest {
    @Test
    void recordsAFailedRunInsteadOfThrowing() {
        RuntimeException down = new RuntimeException("processor unreachable");
        ScheduledJob<String> job = new ScheduledJob<>("j", () -> {
            throw down;
        });

        job.run();

        assertEquals(1, job.runs());
        assertEquals(1, job.consecutiveFailures());
        assertSame(down, job.lastFailure().orElseThrow());
        assertEquals(Optional.empty(), job.lastResult());
    }

    @Test
    void successResetsTheFailureStreakButKeepsTheLastFailureForInspection() {
        AtomicInteger calls = new AtomicInteger();
        ScheduledJob<Integer> job = new ScheduledJob<>("j", () -> {
            if (calls.incrementAndGet() <= 2) throw new RuntimeException("attempt " + calls.get());
            return calls.get();
        });

        job.run();
        job.run();
        assertEquals(2, job.consecutiveFailures());
        job.run();

        assertEquals(3, job.runs());
        assertEquals(0, job.consecutiveFailures());
        assertEquals(Optional.of(3), job.lastResult());
        assertEquals("attempt 2", job.lastFailure().orElseThrow().getMessage());
    }

    @Test
    void keepsGettingScheduledAfterEveryRunThrows() throws Exception {
        ScheduledJob<Void> job = new ScheduledJob<>("always-failing", () -> {
            throw new RuntimeException("still down");
        });
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.scheduleWithFixedDelay(job, 0, 1, TimeUnit.MILLISECONDS);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (job.runs() < 5 && System.nanoTime() < deadline) Thread.sleep(1);
        } finally {
            scheduler.shutdownNow();
        }
        assertTrue(job.runs() >= 5, "job stopped being scheduled after " + job.runs() + " run(s)");
    }
}
