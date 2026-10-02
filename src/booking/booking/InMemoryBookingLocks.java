package booking.booking;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;

/** Locks on the Booking object itself, which BookingRepository hands out as the one shared instance. */
public final class InMemoryBookingLocks implements BookingLocks {
    private final BookingRepository bookings;

    public InMemoryBookingLocks(BookingRepository bookings) {
        this.bookings = Objects.requireNonNull(bookings, "bookings");
    }

    @Override
    public <T> T withLock(String bookingId, Supplier<T> action) {
        Booking booking = bookings.find(bookingId).orElseThrow(() -> new NoSuchElementException("no booking " + bookingId));
        synchronized (booking) {
            return action.get();
        }
    }
}
