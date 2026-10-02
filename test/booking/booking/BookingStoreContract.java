package booking.booking;

import booking.discount.DiscountCode;
import booking.discount.DiscountStore;
import booking.model.Seat;
import booking.model.Show;
import booking.model.User;
import booking.payment.CheckoutSession;
import booking.pricing.Discount;
import booking.pricing.PriceComponent;
import booking.pricing.PriceQuoteBuilder;
import booking.retry.RetryPolicy;
import booking.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every BookingStore must guarantee: a booking comes back exactly as it was last updated, field for field. */
abstract class BookingStoreContract {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00.123456789Z");
    private static final RetryPolicy RETRY = new RetryPolicy(3, Duration.ofSeconds(30));

    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");
    private final Show show = Fixtures.show("sh", NOW.plus(Duration.ofHours(8)));
    private BookingStore bookings;
    private int nextId;

    /** A fresh store, plus the show and discount stores its rows may refer to. */
    protected abstract BookingStore newStore();

    protected abstract ShowStore shows();

    protected abstract DiscountStore discounts();

    @BeforeEach
    void fresh() {
        bookings = newStore();
        shows().add(show);
        discounts().add(new DiscountCode("FLAT50", new Discount.Flat(5_000), NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)), 0, 5));
    }

    private Booking booking(User user, String discountCode, String... seatIds) {
        List<Seat> seats = Arrays.stream(seatIds).map(id -> show.screen().seat(id).orElseThrow()).toList();
        PriceQuoteBuilder quote = new PriceQuoteBuilder(show, seats).weekendSurchargeBps(1_000);
        if (discountCode != null) quote.discount(new Discount.Flat(5_000));
        PriceComponent price = quote.build();
        String id = "booking-" + (++nextId);
        return new Booking(id, user, show, List.of(seatIds), price, NOW.plus(Duration.ofMinutes(10)), discountCode,
                new CheckoutSession("order_" + nextId, price.totalPaise(), "INR", "rzp_test_key"));
    }

    private static void assertSameBooking(Booking expected, Booking actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.user(), actual.user());
        assertEquals(expected.show(), actual.show());
        assertEquals(expected.seatIds(), actual.seatIds());
        assertEquals(expected.price().lines(), actual.price().lines());
        assertEquals(expected.price().totalPaise(), actual.price().totalPaise());
        assertEquals(expected.holdExpiresAt(), actual.holdExpiresAt());
        assertEquals(expected.discountCode(), actual.discountCode());
        assertEquals(expected.checkout(), actual.checkout());
        assertEquals(expected.state(), actual.state());
    }

    @Test
    void findReturnsANewBookingExactlyAsSaved() {
        Booking booking = booking(alice, null, "A1", "B1");
        bookings.save(booking);

        assertSameBooking(booking, bookings.find(booking.id()).orElseThrow());
    }

    @Test
    void keepsTheDiscountCodeAndThePriceBreakdownItProduced() {
        Booking booking = booking(alice, "FLAT50", "A1");
        bookings.save(booking);

        Booking found = bookings.find(booking.id()).orElseThrow();
        assertEquals(Optional.of("FLAT50"), found.discountCode());
        assertEquals(15_000, found.price().totalPaise());
    }

    @Test
    void updatePersistsEveryPartOfThePaymentRetryState() {
        Booking booking = booking(alice, null, "A1");
        bookings.save(booking);
        Booking pending = bookings.find(booking.id()).orElseThrow();
        pending.markPaymentPending("auth_1", NOW.plusNanos(1));
        pending.recordFailedCapture(NOW.plusNanos(1), false, RETRY);
        pending.recordFailedCapture(NOW.plusSeconds(30), false, RETRY);

        bookings.update(pending);

        Booking found = bookings.find(booking.id()).orElseThrow();
        assertEquals(new Booking.State(BookingStatus.PAYMENT_PENDING, "auth_1", NOW.plusNanos(1), 2,
                NOW.plusSeconds(30).plusSeconds(60), false, 0), found.state());
    }

    @Test
    void updatePersistsAStatusChangeAndTheRefund() {
        Booking booking = booking(alice, null, "A1");
        bookings.save(booking);
        Booking found = bookings.find(booking.id()).orElseThrow();
        found.markConfirmed("auth_1");
        bookings.update(found);
        Booking confirmed = bookings.find(booking.id()).orElseThrow();
        confirmed.markCancelled(10_000);
        bookings.update(confirmed);

        Booking cancelled = bookings.find(booking.id()).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertEquals(10_000, cancelled.refundedPaise());
        assertEquals(Optional.of("auth_1"), cancelled.paymentId());
    }

    @Test
    void unknownBookingIsAbsent() {
        assertTrue(bookings.find("nope").isEmpty());
    }

    @Test
    void rejectsSavingTheSameBookingTwice() {
        Booking booking = booking(alice, null, "A1");
        bookings.save(booking);
        assertThrows(IllegalStateException.class, () -> bookings.save(booking));
    }

    @Test
    void rejectsUpdatingABookingThatWasNeverSaved() {
        assertThrows(IllegalStateException.class, () -> bookings.update(booking(alice, null, "A1")));
    }

    @Test
    void findsBookingsByStatus() {
        Booking held = booking(alice, null, "A1");
        Booking confirmed = booking(alice, null, "A2");
        bookings.save(held);
        bookings.save(confirmed);
        Booking toConfirm = bookings.find(confirmed.id()).orElseThrow();
        toConfirm.markConfirmed("auth_2");
        bookings.update(toConfirm);

        assertEquals(List.of(held.id()), bookings.findByStatus(BookingStatus.HELD).stream().map(Booking::id).toList());
        assertEquals(List.of(confirmed.id()), bookings.findByStatus(BookingStatus.CONFIRMED).stream().map(Booking::id).toList());
        assertEquals(List.of(), bookings.findByStatus(BookingStatus.CANCELLED));
    }

    @Test
    void findsOnlyTheGivenUsersBookings() {
        Booking mine = booking(alice, null, "A1");
        bookings.save(mine);
        bookings.save(booking(bob, null, "A2"));

        assertEquals(List.of(mine.id()), bookings.findByUser(alice).stream().map(Booking::id).toList());
        assertEquals(List.of(), bookings.findByUser(new User("u1", "Someone else")));
    }
}
