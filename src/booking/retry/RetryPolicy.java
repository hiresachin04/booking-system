package booking.retry;

import java.time.Duration;
import java.util.Objects;

/** Exponential backoff: the n-th retry waits {@code initialBackoff * 2^(n-1)}. */
public record RetryPolicy(int maxAttempts, Duration initialBackoff) {
    // 2^20 × any sane initial backoff is already months; capping the shift keeps multipliedBy from overflowing.
    private static final int MAX_DOUBLINGS = 20;

    public RetryPolicy {
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
        if (initialBackoff.isNegative() || initialBackoff.isZero()) throw new IllegalArgumentException("initialBackoff must be positive");
    }

    public Duration backoffAfter(int failedAttempts) {
        return initialBackoff.multipliedBy(1L << Math.min(failedAttempts - 1, MAX_DOUBLINGS));
    }
}
