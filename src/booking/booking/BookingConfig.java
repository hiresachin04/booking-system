package booking.booking;

import java.time.Duration;
import java.util.Objects;

public record BookingConfig(Duration holdTtl, int maxSeatsPerBooking, int weekendSurchargeBps) {
    public BookingConfig {
        Objects.requireNonNull(holdTtl, "holdTtl");
        if (holdTtl.isNegative() || holdTtl.isZero()) throw new IllegalArgumentException("holdTtl must be positive");
        if (maxSeatsPerBooking <= 0) throw new IllegalArgumentException("maxSeatsPerBooking must be positive");
        if (weekendSurchargeBps < 0) throw new IllegalArgumentException("weekendSurchargeBps must not be negative");
    }
}
