package booking.booking;

import booking.discount.DiscountRegistry;

import java.time.Clock;

/**
 * Everything in this JVM's memory: lost on restart, and correct for a single instance only (the locks are Java
 * locks). Accessors return the concrete classes so tests and demo seeding can use them directly.
 */
public final class InMemoryStorage implements Storage {
    private final ShowRepository shows;
    private final BookingRepository bookings = new BookingRepository();
    private final DiscountRegistry discounts = new DiscountRegistry();
    private final InMemoryBookingLocks locks = new InMemoryBookingLocks(bookings);

    public InMemoryStorage(Clock clock) {
        this.shows = new ShowRepository(clock);
    }

    @Override
    public ShowRepository shows() {
        return shows;
    }

    @Override
    public BookingRepository bookings() {
        return bookings;
    }

    @Override
    public DiscountRegistry discounts() {
        return discounts;
    }

    @Override
    public InMemoryBookingLocks locks() {
        return locks;
    }
}
