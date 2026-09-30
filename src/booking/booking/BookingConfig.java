package booking.booking;

import booking.retry.RetryPolicy;

import java.time.Duration;
import java.util.Objects;

/**
 * {@code maxPaymentPending}: the longest seats stay blocked while a capture outcome is unknown.
 * {@code captureRetry}: how often a failed capture is attempted before the seats go back on sale anyway.
 */
public record BookingConfig(Duration holdTtl, int maxSeatsPerBooking, int weekendSurchargeBps, Duration maxPaymentPending,
                            RetryPolicy captureRetry) {
    /** Three attempts in total: the original and two retries, 30s then 60s later. */
    public static final RetryPolicy DEFAULT_CAPTURE_RETRY = new RetryPolicy(3, Duration.ofSeconds(30));

    public BookingConfig {
        Objects.requireNonNull(holdTtl, "holdTtl");
        if (holdTtl.isNegative() || holdTtl.isZero()) throw new IllegalArgumentException("holdTtl must be positive");
        if (maxSeatsPerBooking <= 0) throw new IllegalArgumentException("maxSeatsPerBooking must be positive");
        if (weekendSurchargeBps < 0) throw new IllegalArgumentException("weekendSurchargeBps must not be negative");
        Objects.requireNonNull(maxPaymentPending, "maxPaymentPending");
        if (maxPaymentPending.isNegative() || maxPaymentPending.isZero()) throw new IllegalArgumentException("maxPaymentPending must be positive");
        Objects.requireNonNull(captureRetry, "captureRetry");
    }
}
