package booking.booking;

import java.util.function.Supplier;

/** Serialises every change to one booking, e.g. so a double-clicked confirm can't reach the gateway twice. */
public interface BookingLocks {
    <T> T withLock(String bookingId, Supplier<T> action);
}
