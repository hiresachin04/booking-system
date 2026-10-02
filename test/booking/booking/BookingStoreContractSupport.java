package booking.booking;

import booking.model.Seat;
import booking.model.Show;
import booking.model.User;
import booking.payment.CheckoutSession;
import booking.pricing.PriceComponent;
import booking.pricing.PriceQuoteBuilder;
import booking.support.Fixtures;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class BookingStoreContractSupport {
    private BookingStoreContractSupport() {}

    /** Saves one HELD booking for a fresh show and returns it. */
    static Booking saveSample(BookingStore bookings, ShowStore shows) {
        Instant now = Instant.parse("2026-10-01T06:30:00Z");
        Show show = Fixtures.show("sample", now.plus(Duration.ofHours(8)));
        shows.add(show);
        Seat seat = show.screen().seat("A1").orElseThrow();
        PriceComponent price = new PriceQuoteBuilder(show, List.of(seat)).build();
        Booking booking = new Booking("sample-1", new User("u1", "Alice"), show, List.of("A1"), price, now.plus(Duration.ofMinutes(10)),
                null, new CheckoutSession("order_s", price.totalPaise(), "INR", "rzp_test_key"));
        bookings.save(booking);
        return booking;
    }
}
