package booking.booking;

import booking.discount.DiscountCode;
import booking.discount.DiscountRegistry;
import booking.inventory.HoldExpiredException;
import booking.inventory.SeatStatus;
import booking.inventory.ShowInventory;
import booking.model.User;
import booking.notify.BookingEvent;
import booking.notify.BookingListener;
import booking.notify.NotificationOutbox;
import booking.payment.PaymentStatus;
import booking.pricing.Discount;
import booking.retry.RetryPolicy;
import booking.support.FakePaymentGateway;
import booking.support.Fixtures;
import booking.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The booking flows that differ on Razorpay: it has no void, so an authorization we won't capture stays open (the
 * customer's money held) until Razorpay's own expiry refunds it.
 */
class RazorpayBookingFlowTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration MAX_PENDING = Duration.ofMinutes(15);
    private static final Duration RAZORPAY_EXPIRY = Duration.ofMinutes(12);

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryStorage storage = new InMemoryStorage(clock);
    private final ShowRepository shows = storage.shows();
    private final DiscountRegistry discounts = storage.discounts();
    private final FakePaymentGateway razorpay = FakePaymentGateway.razorpay(clock, RAZORPAY_EXPIRY);
    private final List<String> events = new ArrayList<>();
    private final NotificationOutbox outbox = new NotificationOutbox(List.of(new BookingListener() {
        @Override
        public void onConfirmed(BookingEvent event) {
            events.add("confirmed");
        }

        @Override
        public void onPaymentFailed(BookingEvent event) {
            events.add("payment failed");
        }

        @Override
        public void onPaymentRefunded(BookingEvent event) {
            events.add("refunded");
        }
    }), new RetryPolicy(3, Duration.ofSeconds(30)), Duration.ofDays(1), clock);
    private final BookingService service = new BookingService(storage, razorpay,
            new TieredRefundPolicy(List.of()), new BookingConfig(TTL, 10, 0, MAX_PENDING, BookingConfig.DEFAULT_CAPTURE_RETRY),
            clock, outbox);
    private final ShowInventory inventory = shows.add(Fixtures.show("sh", NOW.plus(Duration.ofHours(8))));
    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");

    private Booking confirm(User user, Booking booking) {
        return service.confirm(user, booking.id(), razorpay.simulateCheckout(booking.checkout()));
    }

    private List<String> deliveredEvents() {
        outbox.dispatchDue();
        return events;
    }

    @Test
    void holdExpiringDuringPaymentLeavesTheAuthorizationForRazorpayToRefund() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        razorpay.duringVerify(() -> clock.advance(TTL));

        HoldExpiredException e = assertThrows(HoldExpiredException.class, () -> confirm(alice, booking));

        assertEquals(0, e.getSuppressed().length);
        assertEquals(BookingStatus.EXPIRED, booking.status());
        assertEquals(PaymentStatus.AUTHORIZED, razorpay.state("auth_1"));
        clock.set(NOW.plus(RAZORPAY_EXPIRY));
        assertEquals(PaymentStatus.RELEASED, razorpay.state("auth_1"));
        assertEquals(List.of(), razorpay.captures());
    }

    @Test
    void unknownCaptureIsRetriedIntoAConfirmedBooking() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        razorpay.failCaptures(1);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));

        clock.advance(Duration.ofSeconds(30));
        service.reconcilePendingPayments();

        assertEquals(BookingStatus.CONFIRMED, booking.status());
        assertEquals(1, razorpay.captures().size());
        assertEquals(List.of("confirmed"), deliveredEvents());
    }

    @Test
    void afterGivingUpTheSeatsAreSoldButTheBookingWaitsForRazorpaysExpiry() {
        DiscountCode dc = new DiscountCode("ONE", new Discount.Flat(5_000), NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)), 0, 1);
        discounts.add(dc);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        razorpay.failCaptures(3);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));
        clock.advance(Duration.ofSeconds(30));
        service.reconcilePendingPayments();
        clock.advance(Duration.ofSeconds(60));
        service.reconcilePendingPayments();
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        service.holdSeats(bob, "sh", List.of("A1"));

        ReconciliationReport waiting = service.reconcilePendingPayments();

        assertEquals(List.of(booking.id()), waiting.awaitingProcessorExpiryBookingIds());
        assertEquals(List.of(), waiting.failures());
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, booking.status());
        assertEquals(PaymentStatus.AUTHORIZED, razorpay.state("auth_1"));
        assertEquals(1, dc.used());

        clock.set(NOW.plus(RAZORPAY_EXPIRY));
        ReconciliationReport expired = service.reconcilePendingPayments();

        assertEquals(List.of(booking.id()), expired.settledBookingIds());
        assertEquals(BookingStatus.PAYMENT_FAILED, booking.status());
        assertEquals(List.of(), razorpay.captures());
        assertEquals(0, dc.used());
        assertEquals(List.of("payment failed"), deliveredEvents());
    }

    @Test
    void captureThatLandedAfterTheSeatsWereReleasedIsRefundedInFull() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        razorpay.loseNextCaptureResponse(new RuntimeException("read timeout"));
        razorpay.failStatusLookups(2);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));
        clock.advance(MAX_PENDING);
        assertEquals(List.of(booking.id()), service.reconcilePendingPayments().seatsReleasedBookingIds());

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.PAYMENT_REFUNDED, booking.status());
        assertEquals(List.of(new FakePaymentGateway.Refund("auth_1", 20_000)), razorpay.refunds());
        assertEquals(List.of("refunded"), deliveredEvents());
    }

    @Test
    void authorizationExpiringWhileSeatsAreHeldFailsTheBookingAndFreesTheSeats() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        razorpay.failCaptures(1);
        razorpay.failStatusLookups(2);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));
        clock.advance(Duration.ofSeconds(30));
        service.reconcilePendingPayments();

        clock.set(NOW.plus(RAZORPAY_EXPIRY));
        service.reconcilePendingPayments();

        assertEquals(BookingStatus.PAYMENT_FAILED, booking.status());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        assertEquals(1, razorpay.captureAttempts());
    }
}
