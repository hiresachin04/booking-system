package booking.booking;

import java.time.Duration;
import java.util.Objects;

/** {@code maxPaymentPending}: how long seats stay blocked while a capture outcome is unknown before going back on sale. */
public record BookingConfig(Duration holdTtl, int maxSeatsPerBooking, int weekendSurchargeBps, Duration maxPaymentPending) {
    public BookingConfig {
        Objects.requireNonNull(holdTtl, "holdTtl");
        if (holdTtl.isNegative() || holdTtl.isZero()) throw new IllegalArgumentException("holdTtl must be positive");
        if (maxSeatsPerBooking <= 0) throw new IllegalArgumentException("maxSeatsPerBooking must be positive");
        if (weekendSurchargeBps < 0) throw new IllegalArgumentException("weekendSurchargeBps must not be negative");
        Objects.requireNonNull(maxPaymentPending, "maxPaymentPending");
        if (maxPaymentPending.isNegative() || maxPaymentPending.isZero()) throw new IllegalArgumentException("maxPaymentPending must be positive");
    }
}
