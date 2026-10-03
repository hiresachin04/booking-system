package booking.booking;

import booking.discount.DiscountCode;
import booking.discount.DiscountStore;
import booking.discount.DiscountRejectedException;
import booking.inventory.HoldExpiredException;
import booking.inventory.SeatStatus;
import booking.inventory.SeatsUnavailableException;
import booking.inventory.SeatInventory;
import booking.model.User;
import booking.notify.BookingEvent;
import booking.notify.BookingListener;
import booking.notify.DispatchReport;
import booking.notify.Outbox;
import booking.retry.RetryPolicy;
import booking.payment.CheckoutResult;
import booking.payment.PaymentRejectedException;
import booking.payment.PaymentResult;
import booking.payment.PaymentStatus;
import booking.payment.PaymentVerificationException;
import booking.pricing.Discount;
import booking.support.FakePaymentGateway;
import booking.support.Fixtures;
import booking.support.MutableClock;
import booking.support.Race;
import booking.support.SimulatedCrash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BookingService's behaviour, run against each Storage by a subclass. Bookings are always re-read with
 * {@link #latest} rather than trusted from an earlier call: a database store hands out a fresh copy on every read.
 */
abstract class BookingServiceContract {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    // Thursday 20:00 in Kolkata, 8h after NOW: the 50% refund tier.
    private static final Instant WEEKDAY_SHOW = NOW.plus(Duration.ofHours(8));
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_SEATS = 10;
    private static final Duration MAX_PENDING = Duration.ofMinutes(15);
    private static final RetryPolicy CAPTURE_RETRY = new RetryPolicy(3, Duration.ofSeconds(30));

    protected final MutableClock clock = new MutableClock(NOW);
    private Storage storage;
    private ShowStore shows;
    private BookingStore bookings;
    private DiscountStore discounts;
    private final FakePaymentGateway payments = new FakePaymentGateway();
    private final RefundPolicy refundPolicy = new TieredRefundPolicy(List.of(
            new TieredRefundPolicy.Tier(Duration.ofHours(24), 10_000),
            new TieredRefundPolicy.Tier(Duration.ofHours(4), 5_000)));
    private final List<String> events = new ArrayList<>();
    private SeatInventory inventory;

    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");

    private final BookingListener recorder = new BookingListener() {
        @Override
        public void onConfirmed(BookingEvent event) {
            events.add("confirmed " + event.booking().id());
        }

        @Override
        public void onCancelled(BookingEvent event) {
            events.add("cancelled " + event.booking().id());
        }

        @Override
        public void onPaymentFailed(BookingEvent event) {
            events.add("payment failed " + event.booking().id());
        }

        @Override
        public void onPaymentRefunded(BookingEvent event) {
            events.add("refunded " + event.booking().id());
        }
    };

    private Outbox outbox;
    private BookingService service;

    /** A fresh, empty storage on {@code clock}. */
    protected abstract Storage newStorage();

    @BeforeEach
    void freshStorage() {
        storage = newStorage();
        shows = storage.shows();
        bookings = storage.bookings();
        discounts = storage.discounts();
        inventory = shows.add(Fixtures.show("sh", WEEKDAY_SHOW));
        outbox = outboxFor(recorder);
        service = service(outbox);
    }

    private Outbox outboxFor(BookingListener... listeners) {
        return storage.outbox(List.of(listeners), new RetryPolicy(3, Duration.ofSeconds(30)), Duration.ofDays(30), clock);
    }

    private BookingService service(Outbox outbox) {
        return new BookingService(storage, payments, refundPolicy,
                new BookingConfig(TTL, MAX_SEATS, 1_000, MAX_PENDING, CAPTURE_RETRY), clock, outbox);
    }

    /** The booking as the store has it now. */
    private Booking latest(Booking booking) {
        return bookings.find(booking.id()).orElseThrow();
    }

    private int used(DiscountCode code) {
        return discounts.find(code.code()).orElseThrow().used();
    }

    /** Pays in the "browser" and hands the signed result to confirm, as the frontend would. */
    private Booking confirm(User user, Booking booking) {
        return service.confirm(user, booking.id(), payments.simulateCheckout(booking.checkout()));
    }

    private List<String> deliveredEvents() {
        outbox.dispatchDue();
        return events;
    }

    private DiscountCode code(String code, Discount discount, long minOrderPaise, int usageLimit) {
        DiscountCode dc = new DiscountCode(code, discount, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)), minOrderPaise, usageLimit);
        discounts.add(dc);
        return dc;
    }

    // --- hold & confirm ---

    @Test
    void confirmCapturesTheQuotedAmountAndBooksTheSeats() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1", "B1"));
        assertEquals(55_000, booking.price().totalPaise());

        confirm(alice, booking);

        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(List.of(new FakePaymentGateway.Capture("auth_1", "u1", 55_000)), payments.captures());
        assertEquals(Optional.of("auth_1"), latest(booking).paymentId());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertEquals(SeatStatus.BOOKED, inventory.status("B1"));
        assertEquals(List.of("confirmed " + booking.id()), deliveredEvents());
    }

    @Test
    void rejectsSecondUserHoldingASeatAlreadyHeld() {
        service.holdSeats(alice, "sh", List.of("A1"));
        assertThrows(SeatsUnavailableException.class, () -> service.holdSeats(bob, "sh", List.of("A1", "A2")));
        assertEquals(List.of(), bookings.findByUser(bob));
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A2"));
    }

    @Test
    void acceptsExactlyTheMaximumSeatCount() {
        List<String> ten = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "B1", "B2");
        assertEquals(10, service.holdSeats(alice, "sh", ten).seatIds().size());
    }

    @Test
    void rejectsMoreThanTheMaximumSeatCount() {
        List<String> eleven = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "B1", "B2", "B3");
        assertThrows(IllegalArgumentException.class, () -> service.holdSeats(alice, "sh", eleven));
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
    }

    @Test
    void rejectsHoldOnceTheShowHasStarted() {
        clock.set(WEEKDAY_SHOW);
        assertThrows(ShowAlreadyStartedException.class, () -> service.holdSeats(alice, "sh", List.of("A1")));
    }

    @Test
    void rejectsHoldOnUnknownShow() {
        assertThrows(NoSuchElementException.class, () -> service.holdSeats(alice, "nope", List.of("A1")));
    }

    @Test
    void declinedPaymentKeepsTheBookingHeldSoTheCustomerCanRetry() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.declineNext("insufficient funds");

        assertThrows(PaymentDeclinedException.class, () -> confirm(alice, booking));
        assertEquals(BookingStatus.HELD, latest(booking).status());
        assertThrows(SeatsUnavailableException.class, () -> service.holdSeats(bob, "sh", List.of("A1")));
        assertEquals(List.of(), deliveredEvents());

        confirm(alice, booking);
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(1, payments.captures().size());
    }

    @Test
    void neverCapturesWhenTheHoldExpiresWhilePaymentIsInFlight() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.duringVerify(() -> clock.advance(TTL));

        assertThrows(HoldExpiredException.class, () -> confirm(alice, booking));

        assertEquals(BookingStatus.EXPIRED, latest(booking).status());
        assertEquals(List.of(), payments.captures());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        assertEquals(List.of(), deliveredEvents());
    }

    @Test
    void neverCapturesWhenSomeoneElseTookTheSeatWhilePaymentWasInFlight() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.duringVerify(() -> {
            clock.advance(TTL);
            service.holdSeats(bob, "sh", List.of("A1"));
        });

        assertThrows(HoldExpiredException.class, () -> confirm(alice, booking));

        assertEquals(List.of(), payments.captures());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(SeatStatus.HELD, inventory.status("A1"));
    }

    @Test
    void failedVoidAfterExpiryIsReportedButStillLeavesTheCustomerUncharged() {
        RuntimeException voidFailure = new RuntimeException("gateway timeout");
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.duringVerify(() -> clock.advance(TTL));
        payments.failNextVoid(voidFailure);

        HoldExpiredException e = assertThrows(HoldExpiredException.class, () -> confirm(alice, booking));

        assertEquals(List.of(voidFailure), List.of(e.getSuppressed()));
        assertEquals(BookingStatus.EXPIRED, latest(booking).status());
        assertEquals(List.of(), payments.captures());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
    }

    @Test
    void failedCaptureKeepsTheSeatsAndRetriesExactlyWhenTheBackoffEnds() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.failCaptures(1);

        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));
        assertEquals(BookingStatus.PAYMENT_PENDING, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));

        clock.advance(CAPTURE_RETRY.initialBackoff().minusNanos(1));
        service.reconcilePendingPayments();
        assertEquals(1, payments.captureAttempts());

        clock.advance(Duration.ofNanos(1));
        service.reconcilePendingPayments();
        assertEquals(2, payments.captureAttempts());
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(1, payments.captures().size());
        assertEquals(List.of("confirmed " + booking.id()), deliveredEvents());
    }

    @Test
    void secondRetryWaitsTwiceAsLongAsTheFirst() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.failCaptures(2);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));
        clock.advance(Duration.ofSeconds(30));
        service.reconcilePendingPayments();

        clock.advance(Duration.ofSeconds(60).minusNanos(1));
        service.reconcilePendingPayments();
        assertEquals(2, payments.captureAttempts());

        clock.advance(Duration.ofNanos(1));
        service.reconcilePendingPayments();
        assertEquals(3, payments.captureAttempts());
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
    }

    @Test
    void givesUpAfterThreeAttemptsThenReleasesTheSeatsAndVoids() {
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.failCaptures(3);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));
        clock.advance(Duration.ofSeconds(30));
        assertEquals(List.of(), service.reconcilePendingPayments().seatsReleasedBookingIds());
        clock.advance(Duration.ofSeconds(60));

        ReconciliationReport lastAttempt = service.reconcilePendingPayments();

        assertEquals(3, payments.captureAttempts());
        assertEquals(List.of(booking.id()), lastAttempt.seatsReleasedBookingIds());
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, latest(booking).status());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        service.holdSeats(bob, "sh", List.of("A2"));

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.PAYMENT_FAILED, latest(booking).status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(3, payments.captureAttempts());
        assertEquals(0, used(dc));
        assertEquals(List.of("payment failed " + booking.id()), deliveredEvents());
    }

    @Test
    void rejectedCaptureStopsRetryingAndReleasesTheSeatsAtOnce() {
        PaymentRejectedException rejected = new PaymentRejectedException("BAD_REQUEST_ERROR");
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.failNextCapture(rejected);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> confirm(alice, booking));

        assertSame(rejected, thrown);
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, latest(booking).status());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));

        clock.advance(Duration.ofHours(1));
        service.reconcilePendingPayments();
        assertEquals(1, payments.captureAttempts());
        assertEquals(BookingStatus.PAYMENT_FAILED, latest(booking).status());
        assertEquals(0, used(dc));
    }

    @Test
    void rejectionThatMeansAlreadyCapturedStillConfirms() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new PaymentRejectedException("payment has already been captured"));

        confirm(alice, booking);

        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void retriedCaptureRejectedAsAlreadyCapturedStillConfirms() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.failCaptures(1);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));
        payments.loseNextCaptureResponse(new PaymentRejectedException("payment has already been captured"));
        clock.advance(Duration.ofSeconds(30));

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void failedVoidAfterReleasingTheSeatsIsRetriedOnTheNextRun() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.failNextCapture(new PaymentRejectedException("BAD_REQUEST_ERROR"));
        assertThrows(PaymentRejectedException.class, () -> confirm(alice, booking));
        payments.failNextVoid(new RuntimeException("void timed out"));

        ReconciliationReport failed = service.reconcilePendingPayments();
        assertEquals(1, failed.failures().size());
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, latest(booking).status());
        assertEquals(PaymentStatus.AUTHORIZED, payments.state("auth_1"));

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.PAYMENT_FAILED, latest(booking).status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
    }

    @Test
    void confirmsWhenCaptureWentThroughButItsResponseWasLost() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));

        confirm(alice, booking);

        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertEquals(1, payments.captures().size());
        assertEquals(List.of("confirmed " + booking.id()), deliveredEvents());
    }

    @Test
    void keepsTheSeatsWhenTheCaptureOutcomeCannotBeLearned() {
        RuntimeException timeout = new RuntimeException("read timeout");
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(timeout);
        payments.failStatusLookups(1);

        PaymentPendingException e = assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));

        assertSame(timeout, e.getCause());
        assertEquals(BookingStatus.PAYMENT_PENDING, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertThrows(SeatsUnavailableException.class, () -> service.holdSeats(bob, "sh", List.of("A1")));
        assertEquals(List.of(), deliveredEvents());
    }

    @Test
    void reconciliationConfirmsAPendingBookingTheProcessorCaptured() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(1);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertEquals(List.of("confirmed " + booking.id()), deliveredEvents());
    }

    @Test
    void reconciliationKeepsRetryingWhileTheProcessorIsUnreachable() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(2);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));

        ReconciliationReport firstRun = service.reconcilePendingPayments();
        assertEquals(List.of(booking.id()), firstRun.failures().stream().map(ReconciliationReport.Failure::bookingId).toList());
        assertEquals("processor unreachable", firstRun.failures().getFirst().error().getMessage());
        assertEquals(BookingStatus.PAYMENT_PENDING, latest(booking).status());

        ReconciliationReport secondRun = service.reconcilePendingPayments();
        assertEquals(List.of(booking.id()), secondRun.settledBookingIds());
        assertEquals(List.of(), secondRun.failures());
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
    }

    @Test
    void reconciliationSettlesOtherBookingsWhenOneCannotBeSettled() {
        Booking stuck = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(1);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, stuck));
        Booking settleable = service.holdSeats(bob, "sh", List.of("A2"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(1);
        assertThrows(PaymentPendingException.class, () -> confirm(bob, settleable));

        // One lookup fails during this run; whichever booking hits it stays pending, the other settles.
        payments.failStatusLookups(1);
        ReconciliationReport report = service.reconcilePendingPayments();
        assertEquals(1, report.failures().size());
        assertEquals(1, report.settledBookingIds().size());
        assertEquals(1, bookings.findByStatus(BookingStatus.PAYMENT_PENDING).size());
        assertEquals(1, bookings.findByStatus(BookingStatus.CONFIRMED).size());
    }

    /** Leaves the booking PAYMENT_PENDING with the processor unreachable for {@code lookupFailures} status checks. */
    private Booking pendingBooking(User user, String showId, String seat, int lookupFailures, boolean processorCaptured) {
        Booking booking = service.holdSeats(user, showId, List.of(seat));
        if (processorCaptured) payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        else payments.failNextCapture(new RuntimeException("connection reset"));
        payments.failStatusLookups(lookupFailures);
        assertThrows(PaymentPendingException.class, () -> confirm(user, booking));
        return booking;
    }

    @Test
    void keepsPendingSeatsBlockedUntilTheDeadline() {
        Booking booking = pendingBooking(alice, "sh", "A1", 100, true);
        clock.advance(MAX_PENDING.minusNanos(1));

        ReconciliationReport report = service.reconcilePendingPayments();

        assertEquals(List.of(), report.seatsReleasedBookingIds());
        assertEquals(BookingStatus.PAYMENT_PENDING, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void releasesPendingSeatsForResaleAtTheDeadline() {
        Booking booking = pendingBooking(alice, "sh", "A1", 100, true);
        clock.advance(MAX_PENDING);

        ReconciliationReport report = service.reconcilePendingPayments();

        assertEquals(List.of(booking.id()), report.seatsReleasedBookingIds());
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, latest(booking).status());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        service.holdSeats(bob, "sh", List.of("A1"));
    }

    @Test
    void releasesPendingSeatsAtShowStartWhenThatComesBeforeTheDeadline() {
        Instant soon = NOW.plus(Duration.ofMinutes(12));
        shows.add(Fixtures.show("soon", soon));
        Booking booking = pendingBooking(alice, "soon", "A1", 100, true);
        clock.set(soon);

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, latest(booking).status());
    }

    @Test
    void refundsInFullWhenAnUnresolvedPaymentTurnsOutCaptured() {
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(2);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));
        clock.advance(MAX_PENDING);
        service.reconcilePendingPayments();
        service.holdSeats(bob, "sh", List.of("A1"));

        ReconciliationReport report = service.reconcilePendingPayments();

        assertEquals(List.of(booking.id()), report.settledBookingIds());
        assertEquals(BookingStatus.PAYMENT_REFUNDED, latest(booking).status());
        assertEquals(List.of(new FakePaymentGateway.Refund("auth_1", 15_000)), payments.refunds());
        assertEquals(15_000, latest(booking).refundedPaise());
        assertEquals(0, used(dc));
        assertEquals(SeatStatus.HELD, inventory.status("A1"));
        assertEquals(List.of("refunded " + booking.id()), deliveredEvents());
    }

    @Test
    void voidsAnUnresolvedPaymentThatWasNeverCapturedWithoutTouchingResoldSeats() {
        Booking booking = pendingBooking(alice, "sh", "A1", 2, false);
        clock.advance(MAX_PENDING);
        service.reconcilePendingPayments();
        Booking resold = service.holdSeats(bob, "sh", List.of("A1"));

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.PAYMENT_FAILED, latest(booking).status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(List.of(), payments.refunds());
        confirm(bob, resold);
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void retriesAFailedAutoRefundOnTheNextRun() {
        Booking booking = pendingBooking(alice, "sh", "A1", 2, true);
        clock.advance(MAX_PENDING);
        service.reconcilePendingPayments();
        payments.failNextRefund(new RuntimeException("refund api down"));

        ReconciliationReport failed = service.reconcilePendingPayments();
        assertEquals(1, failed.failures().size());
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, latest(booking).status());

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.PAYMENT_REFUNDED, latest(booking).status());
        assertEquals(1, payments.refunds().size());
    }

    @Test
    void retryingARefundWhoseResponseWasLostDoesNotRefundTwice() {
        Booking booking = pendingBooking(alice, "sh", "A1", 2, true);
        clock.advance(MAX_PENDING);
        service.reconcilePendingPayments();
        payments.loseNextRefundResponse(new RuntimeException("read timeout"));

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, latest(booking).status());
        service.reconcilePendingPayments();

        assertEquals(BookingStatus.PAYMENT_REFUNDED, latest(booking).status());
        assertEquals(1, payments.refunds().size());
    }

    // --- payments the browser never reported (tab closed, phone slept) ---

    /** The customer pays in Checkout, but the page never gets to call confirm. */
    private CheckoutResult payAndCloseTheTab(Booking booking) {
        return payments.simulateCheckout(booking.checkout());
    }

    @Test
    void aPaymentTheBrowserNeverReportedIsConfirmedByReconciliation() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payAndCloseTheTab(booking);
        clock.advance(BookingService.BROWSER_CONFIRM_GRACE);

        ReconciliationReport report = service.reconcilePendingPayments();

        assertEquals(List.of(booking.id()), report.confirmedWithoutBrowserBookingIds());
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertEquals(1, payments.captures().size());
        assertEquals(List.of("confirmed " + booking.id()), deliveredEvents());
    }

    @Test
    void leavesTheBrowserItsGracePeriodBeforeAskingTheProcessor() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payAndCloseTheTab(booking);
        clock.advance(BookingService.BROWSER_CONFIRM_GRACE.minusNanos(1));

        service.reconcilePendingPayments();
        assertEquals(0, payments.paidLookups());
        assertEquals(BookingStatus.HELD, latest(booking).status());

        clock.advance(Duration.ofNanos(1));
        service.reconcilePendingPayments();
        assertEquals(1, payments.paidLookups());
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
    }

    @Test
    void aPaymentMadeAfterTheHoldRanOutIsReleasedAndTheCustomerTold() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payAndCloseTheTab(booking);
        clock.advance(TTL);

        ReconciliationReport report = service.reconcilePendingPayments();

        assertEquals(List.of(booking.id()), report.expiredHoldBookingIds());
        assertEquals(BookingStatus.EXPIRED, latest(booking).status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(List.of(), payments.captures());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        assertEquals(List.of("payment failed " + booking.id()), deliveredEvents());
    }

    @Test
    void neverConfirmsAPaymentFoundAfterTheShowStartedEvenIfTheHoldIsStillLive() {
        Instant startsSoon = NOW.plus(Duration.ofMinutes(5));
        shows.add(Fixtures.show("soon", startsSoon));
        Booking booking = service.holdSeats(alice, "soon", List.of("A1"));
        payAndCloseTheTab(booking);
        clock.set(startsSoon.plusSeconds(30));

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.EXPIRED, latest(booking).status());
        assertEquals(List.of(), payments.captures());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
    }

    @Test
    void anAbandonedHoldIsExpiredOnceAndNeverLookedUpAgain() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        clock.advance(TTL);

        assertEquals(List.of(booking.id()), service.reconcilePendingPayments().expiredHoldBookingIds());
        service.reconcilePendingPayments();

        assertEquals(BookingStatus.EXPIRED, latest(booking).status());
        assertEquals(1, payments.paidLookups());
        assertEquals(List.of(), deliveredEvents());
    }

    @Test
    void theBrowsersLateConfirmAfterReconciliationConfirmedReturnsTheBooking() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        CheckoutResult paid = payAndCloseTheTab(booking);
        clock.advance(BookingService.BROWSER_CONFIRM_GRACE);
        service.reconcilePendingPayments();

        Booking confirmed = service.confirm(alice, booking.id(), paid);

        assertEquals(BookingStatus.CONFIRMED, confirmed.status());
        assertEquals(1, payments.captureAttempts());
    }

    @Test
    void aConfirmForAHoldReconciliationAlreadyExpiredReleasesThePayment() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        clock.advance(TTL);
        service.reconcilePendingPayments();
        CheckoutResult paidLate = payments.simulateCheckout(booking.checkout());

        assertThrows(HoldExpiredException.class, () -> service.confirm(alice, booking.id(), paidLate));

        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(List.of(), payments.captures());
    }

    @Test
    void anUnreachableProcessorLeavesTheHoldForTheNextRun() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payAndCloseTheTab(booking);
        clock.advance(BookingService.BROWSER_CONFIRM_GRACE);
        payments.failPaidLookups(1);

        ReconciliationReport failed = service.reconcilePendingPayments();
        assertEquals(List.of(booking.id()), failed.failures().stream().map(ReconciliationReport.Failure::bookingId).toList());
        assertEquals(BookingStatus.HELD, latest(booking).status());

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
    }

    @Test
    void aPaymentFoundAfterItsDiscountRanOutIsReleasedOnceNotRetriedForever() {
        DiscountCode dc = code("LAST", new Discount.Flat(5_000), 0, 1);
        Booking unreported = service.holdSeats(alice, "sh", List.of("A1"), "LAST");
        Booking other = service.holdSeats(bob, "sh", List.of("A2"), "LAST");
        payAndCloseTheTab(unreported);
        confirm(bob, other);
        clock.advance(BookingService.BROWSER_CONFIRM_GRACE);

        service.reconcilePendingPayments();
        service.reconcilePendingPayments();

        assertEquals(BookingStatus.EXPIRED, latest(unreported).status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(1, used(dc));
        assertEquals(1, payments.paidLookups());
        assertEquals(List.of("confirmed " + other.id(), "payment failed " + unreported.id()), deliveredEvents());
    }

    // --- crash recovery: the process dies part-way through confirm ---

    @Test
    void crashBeforeTheCaptureReachedTheProcessorIsFinishedByReconciliation() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.crashDuringNextCapture(false);

        assertThrows(SimulatedCrash.class, () -> confirm(alice, booking));

        assertEquals(BookingStatus.PAYMENT_PENDING, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertEquals(List.of(), payments.captures());

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(1, payments.captures().size());
        assertEquals(List.of("confirmed " + booking.id()), deliveredEvents());
    }

    @Test
    void crashAfterTheProcessorCapturedIsConfirmedWithoutCapturingAgain() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.crashDuringNextCapture(true);

        assertThrows(SimulatedCrash.class, () -> confirm(alice, booking));
        assertEquals(BookingStatus.PAYMENT_PENDING, latest(booking).status());

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertEquals(1, payments.captures().size());
        assertEquals(1, payments.captureAttempts(), "reconciliation read CAPTURED and must not call capture again");
    }

    /** What a confirm leaves behind if it dies right after its checkpoint, before booking any seats. */
    private Booking checkpointedButSeatsNotBooked(Booking held) {
        CheckoutResult paid = payments.simulateCheckout(held.checkout());
        String authorization = ((PaymentResult.Approved) payments.verifyCheckout(held.checkout(), paid)).authorizationId();
        Booking stored = latest(held);
        stored.markPaymentPending(authorization, clock.instant());
        bookings.update(stored);
        return stored;
    }

    @Test
    void crashBeforeTheSeatsWereBookedIsFinishedWhileTheHoldIsStillLive() {
        Booking booking = checkpointedButSeatsNotBooked(service.holdSeats(alice, "sh", List.of("A1")));

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertEquals(1, payments.captures().size());
    }

    @Test
    void crashBeforeTheSeatsWereBookedNeverCapturesOnceTheHoldWentToSomeoneElse() {
        Booking booking = checkpointedButSeatsNotBooked(service.holdSeats(alice, "sh", List.of("A1")));
        clock.advance(TTL);
        Booking bobs = service.holdSeats(bob, "sh", List.of("A1"));

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, latest(booking).status());
        assertEquals(List.of(), payments.captures());
        assertEquals(SeatStatus.HELD, inventory.status("A1"));

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.PAYMENT_FAILED, latest(booking).status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        confirm(bob, bobs);
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void rejectsCancellingABookingWhosePaymentIsPending() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(1);
        assertThrows(PaymentPendingException.class, () -> confirm(alice, booking));

        assertThrows(IllegalBookingStateException.class, () -> service.cancel(alice, booking.id()));
        assertEquals(List.of(), payments.refunds());
    }

    @Test
    void customerWhoPaidAfterTheHoldRanOutHasTheAuthorizationVoided() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        CheckoutResult paidLate = payments.simulateCheckout(booking.checkout());
        clock.advance(TTL);

        assertThrows(HoldExpiredException.class, () -> service.confirm(alice, booking.id(), paidLate));

        assertEquals(BookingStatus.EXPIRED, latest(booking).status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(List.of(), payments.captures());
    }

    @Test
    void forgedResultAfterTheHoldRanOutVoidsNothingAndIsReported() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        CheckoutResult paid = payments.simulateCheckout(booking.checkout());
        clock.advance(TTL);

        HoldExpiredException e = assertThrows(HoldExpiredException.class,
                () -> service.confirm(alice, booking.id(), new CheckoutResult(paid.orderId(), paid.paymentId(), "forged")));

        assertInstanceOf(PaymentVerificationException.class, e.getSuppressed()[0]);
        assertEquals(PaymentStatus.AUTHORIZED, payments.state("auth_1"));
    }

    // --- checkout ---

    @Test
    void holdStartsACheckoutForExactlyTheQuotedAmount() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1", "B1"));

        assertEquals(55_000, booking.checkout().amountPaise());
        assertEquals("INR", booking.checkout().currency());
    }

    @Test
    void failureToStartPaymentGivesTheSeatsBack() {
        RuntimeException down = new RuntimeException("orders api down");
        payments.failNextStartPayment(down);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.holdSeats(alice, "sh", List.of("A1")));

        assertSame(down, thrown);
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        assertEquals(List.of(), bookings.findByUser(alice));
    }

    @Test
    void rejectsAForgedSignatureAndKeepsTheHoldForARetry() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        CheckoutResult paid = payments.simulateCheckout(booking.checkout());

        assertThrows(PaymentVerificationException.class,
                () -> service.confirm(alice, booking.id(), new CheckoutResult(paid.orderId(), paid.paymentId(), "0".repeat(64))));
        assertEquals(BookingStatus.HELD, latest(booking).status());
        assertEquals(List.of(), payments.captures());

        service.confirm(alice, booking.id(), paid);
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
    }

    @Test
    void rejectsAPaymentMadeForAnotherBookingsOrder() {
        Booking expensive = service.holdSeats(alice, "sh", List.of("B1", "B2"));
        Booking cheap = service.holdSeats(alice, "sh", List.of("A1"));
        CheckoutResult paidForCheap = payments.simulateCheckout(cheap.checkout());

        assertThrows(PaymentVerificationException.class, () -> service.confirm(alice, expensive.id(), paidForCheap));
        assertEquals(BookingStatus.HELD, latest(expensive).status());
        assertEquals(SeatStatus.HELD, inventory.status("B1"));
    }

    @Test
    void rejectedVerificationGivesTheDiscountUseBack() {
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        CheckoutResult paid = payments.simulateCheckout(booking.checkout());

        assertThrows(PaymentVerificationException.class,
                () -> service.confirm(alice, booking.id(), new CheckoutResult(paid.orderId(), paid.paymentId(), "forged")));
        assertEquals(0, used(dc));
    }

    @Test
    void repeatingConfirmWithTheSamePaymentReturnsTheBookingWithoutCapturingAgain() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        confirm(alice, booking);

        Booking again = confirm(alice, booking);

        assertEquals(BookingStatus.CONFIRMED, again.status());
        assertEquals(1, payments.captureAttempts());
    }

    @Test
    void aSecondDifferentPaymentForAConfirmedBookingIsRejectedAndReleased() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        confirm(alice, booking);
        CheckoutResult secondPayment = payments.simulateAnotherPayment(booking.checkout());

        assertThrows(IllegalBookingStateException.class, () -> service.confirm(alice, booking.id(), secondPayment));

        assertEquals(PaymentStatus.RELEASED, payments.state(secondPayment.paymentId()));
        assertEquals(1, payments.captures().size());
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
    }

    @Test
    void secondConfirmArrivingMidPaymentWaitsAndThenReturnsTheConfirmedBooking() throws Exception {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        // Paid up front: the fake's simulateCheckout is synchronized, so calling it from the second thread would
        // park that thread inside the fake rather than on the booking lock this test is about.
        CheckoutResult paid = payments.simulateCheckout(booking.checkout());
        AtomicReference<Object> secondOutcome = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                secondOutcome.set(service.confirm(alice, booking.id(), paid).status());
            } catch (Throwable t) {
                secondOutcome.set(t);
            }
        });
        // Start the second confirm while the first is inside verifyCheckout(), and wait until it is parked on a monitor.
        payments.duringVerify(() -> {
            second.start();
            awaitWaitingForBookingLock(second);
        });

        service.confirm(alice, booking.id(), paid);
        second.join(5_000);

        // Without the booking lock the second confirm runs alongside the first, finds the booking still HELD, and
        // trips over the transitions the first one made; with it, it waits and sees a finished CONFIRMED booking.
        assertEquals(BookingStatus.CONFIRMED, secondOutcome.get());
        assertEquals(1, payments.captureAttempts());
        assertEquals(1, payments.captures().size());
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
    }

    /**
     * Returns once {@code thread} is waiting to take a booking lock. In memory that lock is a Java monitor, so the
     * thread shows as BLOCKED; a storage whose lock lives elsewhere overrides this.
     */
    protected void awaitWaitingForBookingLock(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (thread.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) throw new AssertionError("thread never blocked: " + thread.getState());
            Thread.onSpinWait();
        }
    }

    @Test
    void concurrentUsersRacingForOneSeatProduceOneBooking() {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < 20; i++) users.add(new User("racer" + i, "Racer " + i));
        List<Boolean> outcomes = Race.run(users.size(), i -> {
            try {
                Booking b = service.holdSeats(users.get(i), "sh", List.of("B1"));
                confirm(users.get(i), b);
                return true;
            } catch (SeatsUnavailableException e) {
                return false;
            }
        });
        assertEquals(1, Race.winners(outcomes));
        assertEquals(1, payments.captures().size());
    }

    @Test
    void hidesAnotherUsersBookingAsMissing() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        assertThrows(NoSuchElementException.class, () -> confirm(bob, booking));
        assertThrows(NoSuchElementException.class, () -> service.release(bob, booking.id()));
        assertEquals(BookingStatus.HELD, latest(booking).status());
    }

    @Test
    void releaseFreesTheSeatsForOthers() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        service.release(alice, booking.id());

        assertEquals(BookingStatus.RELEASED, latest(booking).status());
        service.holdSeats(bob, "sh", List.of("A1"));
        assertThrows(IllegalBookingStateException.class, () -> confirm(alice, booking));
    }

    // --- discounts ---

    @Test
    void appliesDiscountCodeToTheQuoteAndConsumesAUseOnConfirm() {
        DiscountCode dc = code("FLAT50", new Discount.Flat(5_000), 0, 5);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "FLAT50");
        assertEquals(15_000, booking.price().totalPaise());
        assertEquals(0, used(dc));

        confirm(alice, booking);
        assertEquals(1, used(dc));
        assertEquals(15_000, payments.captures().getFirst().amountPaise());
    }

    @Test
    void rejectsUnknownDiscountCode() {
        assertThrows(DiscountRejectedException.class, () -> service.holdSeats(alice, "sh", List.of("A1"), "NOPE"));
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
    }

    @Test
    void rejectsExpiredDiscountCodeAtHold() {
        discounts.add(new DiscountCode("OLD", new Discount.Flat(5_000), NOW.minus(Duration.ofDays(2)), NOW, 0, 5));
        assertThrows(DiscountRejectedException.class, () -> service.holdSeats(alice, "sh", List.of("A1"), "OLD"));
    }

    @Test
    void rejectsDiscountCodeWhenOrderIsOnePaiseBelowMinimum() {
        code("MIN", new Discount.Flat(5_000), 20_001, 5);
        assertThrows(DiscountRejectedException.class, () -> service.holdSeats(alice, "sh", List.of("A1"), "MIN"));
    }

    @Test
    void acceptsDiscountCodeWhenOrderExactlyMeetsMinimum() {
        code("MIN", new Discount.Flat(5_000), 20_000, 5);
        assertEquals(15_000, service.holdSeats(alice, "sh", List.of("A1"), "MIN").price().totalPaise());
    }

    @Test
    void onlyOneOfTwoConfirmsGetsTheLastDiscountUse() {
        DiscountCode dc = code("LAST", new Discount.Flat(5_000), 0, 1);
        Booking a = service.holdSeats(alice, "sh", List.of("A1"), "LAST");
        Booking b = service.holdSeats(bob, "sh", List.of("A2"), "LAST");
        List<Booking> both = List.of(a, b);
        List<User> owners = List.of(alice, bob);

        List<Boolean> outcomes = Race.run(2, i -> {
            try {
                confirm(owners.get(i), both.get(i));
                return true;
            } catch (DiscountRejectedException e) {
                return false;
            }
        });

        assertEquals(1, Race.winners(outcomes));
        assertEquals(1, used(dc));
        assertEquals(1, payments.captures().size());
        Booking loser = outcomes.get(0) ? b : a;
        assertEquals(BookingStatus.HELD, latest(loser).status());
    }

    @Test
    void declinedPaymentGivesTheDiscountUseBack() {
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.declineNext("card expired");

        assertThrows(PaymentDeclinedException.class, () -> confirm(alice, booking));
        assertEquals(0, used(dc));
    }

    @Test
    void expiryDuringPaymentGivesTheDiscountUseBack() {
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.duringVerify(() -> clock.advance(TTL));

        assertThrows(HoldExpiredException.class, () -> confirm(alice, booking));
        assertEquals(0, used(dc));
    }

    // --- cancel & refund ---

    @Test
    void cancelRefundsPerPolicyAndFreesTheSeats() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        confirm(alice, booking);

        service.cancel(alice, booking.id());

        assertEquals(BookingStatus.CANCELLED, latest(booking).status());
        assertEquals(10_000, latest(booking).refundedPaise());
        assertEquals(List.of(new FakePaymentGateway.Refund("auth_1", 10_000)), payments.refunds());
        service.holdSeats(bob, "sh", List.of("A1"));
        assertEquals(List.of("confirmed " + booking.id(), "cancelled " + booking.id()), deliveredEvents());
    }

    @Test
    void cancelWithExactlyFourHoursNoticeStillGetsTheHalfRefund() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        confirm(alice, booking);
        clock.set(WEEKDAY_SHOW.minus(Duration.ofHours(4)));
        assertEquals(10_000, service.cancel(alice, booking.id()).refundedPaise());
    }

    @Test
    void cancelJustUnderFourHoursBeforeShowRefundsNothing() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        confirm(alice, booking);
        clock.set(WEEKDAY_SHOW.minus(Duration.ofHours(4)).plusNanos(1));

        service.cancel(alice, booking.id());
        assertEquals(0, latest(booking).refundedPaise());
        assertEquals(List.of(), payments.refunds());
    }

    @Test
    void rejectsCancelOnceTheShowHasStarted() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        confirm(alice, booking);
        clock.set(WEEKDAY_SHOW);

        assertThrows(ShowAlreadyStartedException.class, () -> service.cancel(alice, booking.id()));
        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
    }

    @Test
    void rejectsCancelOfABookingThatWasNeverConfirmed() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        assertThrows(IllegalBookingStateException.class, () -> service.cancel(alice, booking.id()));
        assertEquals(SeatStatus.HELD, inventory.status("A1"));
    }

    @Test
    void rejectsCancellingTwice() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        confirm(alice, booking);
        service.cancel(alice, booking.id());
        assertThrows(IllegalBookingStateException.class, () -> service.cancel(alice, booking.id()));
        assertEquals(1, payments.refunds().size());
    }

    // --- notifications ---

    @Test
    void publishesTheEventWithoutDeliveringItInsideConfirm() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        confirm(alice, booking);

        assertEquals(List.of(), events);
        assertEquals(List.of("confirmed " + booking.id()), deliveredEvents());
    }

    @Test
    void failingListenerNoLongerMakesAConfirmedBookingLookFailed() {
        RuntimeException boom = new RuntimeException("mailer down");
        BookingListener broken = new BookingListener() {
            @Override
            public void onConfirmed(BookingEvent event) {
                throw boom;
            }
        };
        Outbox brokenOutbox = outboxFor(broken, recorder);
        BookingService withBrokenListener = service(brokenOutbox);
        Booking booking = withBrokenListener.holdSeats(alice, "sh", List.of("A1"));

        withBrokenListener.confirm(alice, booking.id(), payments.simulateCheckout(booking.checkout()));
        brokenOutbox.dispatchDue();

        assertEquals(BookingStatus.CONFIRMED, latest(booking).status());
        assertEquals(List.of("confirmed " + booking.id()), events);
        assertEquals(new DispatchReport(0, 0, 0, 0), brokenOutbox.dispatchDue());
    }
}
