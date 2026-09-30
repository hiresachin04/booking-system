package booking.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Race {
    public interface Attempt {
        /** Returns whether this racer won; throwing anything fails the test. */
        boolean run(int racer);
    }

    private Race() {}

    /** Releases all racers from one latch so the attempts actually overlap instead of running in submit order. */
    public static List<Boolean> run(int racers, Attempt attempt) {
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(racers);
        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            for (int i = 0; i < racers; i++) {
                int racer = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return attempt.run(racer);
                }));
            }
            ready.await();
            go.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while racing", e);
        }
        List<Boolean> outcomes = new ArrayList<>(racers);
        for (Future<Boolean> f : futures) {
            try {
                outcomes.add(f.get());
            } catch (Exception e) {
                throw new AssertionError("racer failed unexpectedly", e);
            }
        }
        return outcomes;
    }

    public static long winners(List<Boolean> outcomes) {
        return outcomes.stream().filter(won -> won).count();
    }
}
