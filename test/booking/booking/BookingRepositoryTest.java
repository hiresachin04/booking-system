package booking.booking;

import booking.discount.DiscountStore;
import booking.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The in-memory store against the BookingStore contract, plus what only it promises: one shared instance. */
class BookingRepositoryTest extends BookingStoreContract {
    private final InMemoryStorage storage = new InMemoryStorage(new MutableClock(Instant.parse("2026-10-01T06:30:00Z")));

    @Override
    protected BookingStore newStore() {
        return storage.bookings();
    }

    @Override
    protected ShowStore shows() {
        return storage.shows();
    }

    @Override
    protected DiscountStore discounts() {
        return storage.discounts();
    }

    @Test
    void handsOutTheOneStoredInstance() {
        BookingStoreContractSupport.saveSample(storage.bookings(), storage.shows());
        BookingRepository bookings = storage.bookings();
        Booking first = bookings.findByStatus(BookingStatus.HELD).getFirst();

        assertSame(first, bookings.find(first.id()).orElseThrow());
    }

    @Test
    void rejectsUpdatingACopyOfAStoredBooking() {
        Booking stored = BookingStoreContractSupport.saveSample(storage.bookings(), storage.shows());
        Booking copy = Booking.restore(stored.id(), stored.user(), stored.show(), stored.seatIds(), stored.price(),
                stored.holdExpiresAt(), null, stored.checkout(), stored.state());

        assertThrows(IllegalStateException.class, () -> storage.bookings().update(copy));
    }
}
