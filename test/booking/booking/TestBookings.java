package booking.booking;

import booking.model.Seat;
import booking.model.Show;
import booking.model.User;
import booking.payment.CheckoutSession;
import booking.pricing.PriceComponent;
import booking.pricing.PriceQuoteBuilder;
import booking.support.Fixtures;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Ready-made bookings for tests outside this package, which can't call Booking's package-private constructor. */
public final class TestBookings {
    private static final AtomicInteger IDS = new AtomicInteger();

    private TestBookings() {}

    /** A CONFIRMED booking for seat A1 of a fresh show, not saved anywhere. */
    public static Booking confirmed(Clock clock) {
        Booking booking = held(Fixtures.show("show-" + IDS.incrementAndGet(), clock.instant().plus(Duration.ofHours(8))), clock.instant());
        booking.markConfirmed("auth_" + booking.id());
        return booking;
    }

    /** Adds a fresh show, saves a CONFIRMED booking for it, and returns the booking. */
    public static Booking saveConfirmed(ShowStore shows, BookingStore bookings, Clock clock) {
        Booking booking = confirmed(clock);
        shows.add(booking.show());
        bookings.save(booking);
        return booking;
    }

    /** Adds a fresh show, saves a HELD booking for it, and returns the booking. */
    static Booking saveHeld(ShowStore shows, BookingStore bookings, Instant now) {
        Booking booking = held(Fixtures.show("show-" + IDS.incrementAndGet(), now.plus(Duration.ofHours(8))), now);
        shows.add(booking.show());
        bookings.save(booking);
        return booking;
    }

    private static Booking held(Show show, Instant now) {
        Seat seat = show.screen().seat("A1").orElseThrow();
        PriceComponent price = new PriceQuoteBuilder(show, List.of(seat)).build();
        String id = "booking-" + IDS.incrementAndGet();
        return new Booking(id, new User("u1", "Alice"), show, List.of("A1"), price, now.plus(Duration.ofMinutes(10)), null,
                new CheckoutSession("order_" + id, price.totalPaise(), "INR", "rzp_test_key"));
    }
}
