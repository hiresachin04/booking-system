package booking.app;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A periodic task that survives its own failures. An exception escaping a ScheduledExecutorService task silently
 * cancels every later run, so a single outage would stop the job for good. Here a failed run is recorded
 * ({@link #lastFailure()}, {@link #consecutiveFailures()}) and the next scheduled run is the retry.
 */
public final class ScheduledJob<T> implements Runnable {
    private final String name;
    private final Supplier<T> task;
    private int runs;
    private int consecutiveFailures;
    private T lastResult;
    private RuntimeException lastFailure;

    public ScheduledJob(String name, Supplier<T> task) {
        this.name = Objects.requireNonNull(name, "name");
        this.task = Objects.requireNonNull(task, "task");
    }

    @Override
    public void run() {
        T result;
        try {
            result = task.get();
        } catch (RuntimeException e) {
            synchronized (this) {
                runs++;
                consecutiveFailures++;
                lastFailure = e;
            }
            return;
        }
        synchronized (this) {
            runs++;
            consecutiveFailures = 0;
            lastResult = result;
        }
    }

    public String name() {
        return name;
    }

    public synchronized int runs() {
        return runs;
    }

    /** Non-zero means the job is currently failing; resets on the next successful run. */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized Optional<T> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    /** Kept after later successes, so the most recent failure can still be inspected. */
    public synchronized Optional<RuntimeException> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }
}
