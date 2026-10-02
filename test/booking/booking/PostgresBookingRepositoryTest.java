package booking.booking;

import booking.discount.DiscountStore;
import booking.discount.PostgresDiscountRegistry;
import booking.support.MutableClock;
import booking.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotSame;

/** The PostgreSQL store against the same contract, in a throwaway schema. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class PostgresBookingRepositoryTest extends BookingStoreContract {
    private final PostgresTestDatabase test = PostgresTestDatabase.create();
    private final PostgresShowRepository shows = new PostgresShowRepository(test.database(), new MutableClock(Instant.parse("2026-10-01T06:30:00Z")));
    private final PostgresDiscountRegistry discounts = new PostgresDiscountRegistry(test.database());
    private final PostgresBookingRepository bookings = new PostgresBookingRepository(test.database());

    @AfterEach
    void drop() {
        test.close();
    }

    @Override
    protected BookingStore newStore() {
        return bookings;
    }

    @Override
    protected ShowStore shows() {
        return shows;
    }

    @Override
    protected DiscountStore discounts() {
        return discounts;
    }

    @Test
    void handsOutAFreshCopyOnEveryFind() {
        Booking stored = TestBookings.saveHeld(shows, bookings, Instant.parse("2026-10-01T06:30:00Z"));

        assertNotSame(bookings.find(stored.id()).orElseThrow(), bookings.find(stored.id()).orElseThrow());
    }
}
