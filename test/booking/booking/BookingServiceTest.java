package booking.booking;

import booking.discount.DiscountCode;
import booking.discount.DiscountRegistry;
import booking.discount.DiscountRejectedException;
import booking.inventory.HoldExpiredException;
import booking.inventory.SeatStatus;
import booking.inventory.SeatsUnavailableException;
import booking.inventory.ShowInventory;
import booking.model.User;
import booking.notify.BookingEvent;
import booking.notify.BookingListener;
import booking.notify.DeliveryStatus;
import booking.notify.NotificationOutbox;
import booking.notify.OutboxEntry;
import booking.retry.RetryPolicy;
import booking.payment.PaymentRejectedException;
import booking.payment.PaymentStatus;
import booking.pricing.Discount;
import booking.support.FakePaymentGateway;
import booking.support.Fixtures;
import booking.support.MutableClock;
import booking.support.Race;
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

class BookingServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    // Thursday 20:00 in Kolkata, 8h after NOW: the 50% refund tier.
    private static final Instant WEEKDAY_SHOW = NOW.plus(Duration.ofHours(8));
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_SEATS = 10;
    private static final Duration MAX_PENDING = Duration.ofMinutes(15);
    private static final RetryPolicy CAPTURE_RETRY = new RetryPolicy(3, Duration.ofSeconds(30));

    private final MutableClock clock = new MutableClock(NOW);
    private final ShowRepository shows = new ShowRepository(clock);
    private final BookingRepository bookings = new BookingRepository();
    private final DiscountRegistry discounts = new DiscountRegistry();
    private final FakePaymentGateway payments = new FakePaymentGateway();
    private final RefundPolicy refundPolicy = new TieredRefundPolicy(List.of(
            new TieredRefundPolicy.Tier(Duration.ofHours(24), 10_000),
            new TieredRefundPolicy.Tier(Duration.ofHours(4), 5_000)));
    private final List<String> events = new ArrayList<>();
    private final ShowInventory inventory = shows.add(Fixtures.show("sh", WEEKDAY_SHOW));

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

    private BookingService service(NotificationOutbox outbox) {
        return new BookingService(shows, bookings, discounts, payments, refundPolicy,
                new BookingConfig(TTL, MAX_SEATS, 1_000, MAX_PENDING, CAPTURE_RETRY), clock, outbox);
    }

    private final NotificationOutbox outbox = new NotificationOutbox(List.of(recorder), new RetryPolicy(3, Duration.ofSeconds(30)), Duration.ofDays(30), clock);
    private final BookingService service = service(outbox);

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

        service.confirm(alice, booking.id());

        assertEquals(BookingStatus.CONFIRMED, booking.status());
        assertEquals(List.of(new FakePaymentGateway.Capture("auth_1", "u1", 55_000)), payments.captures());
        assertEquals(Optional.of("auth_1"), booking.paymentId());
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

        assertThrows(PaymentDeclinedException.class, () -> service.confirm(alice, booking.id()));
        assertEquals(BookingStatus.HELD, booking.status());
        assertThrows(SeatsUnavailableException.class, () -> service.holdSeats(bob, "sh", List.of("A1")));
        assertEquals(List.of(), deliveredEvents());

        service.confirm(alice, booking.id());
        assertEquals(BookingStatus.CONFIRMED, booking.status());
        assertEquals(1, payments.captures().size());
    }

    @Test
    void neverCapturesWhenTheHoldExpiresWhilePaymentIsInFlight() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.duringAuthorize(() -> clock.advance(TTL));

        assertThrows(HoldExpiredException.class, () -> service.confirm(alice, booking.id()));

        assertEquals(BookingStatus.EXPIRED, booking.status());
        assertEquals(List.of(), payments.captures());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        assertEquals(List.of(), deliveredEvents());
    }

    @Test
    void neverCapturesWhenSomeoneElseTookTheSeatWhilePaymentWasInFlight() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.duringAuthorize(() -> {
            clock.advance(TTL);
            service.holdSeats(bob, "sh", List.of("A1"));
        });

        assertThrows(HoldExpiredException.class, () -> service.confirm(alice, booking.id()));

        assertEquals(List.of(), payments.captures());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(SeatStatus.HELD, inventory.status("A1"));
    }

    @Test
    void failedVoidAfterExpiryIsReportedButStillLeavesTheCustomerUncharged() {
        RuntimeException voidFailure = new RuntimeException("gateway timeout");
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.duringAuthorize(() -> clock.advance(TTL));
        payments.failNextVoid(voidFailure);

        HoldExpiredException e = assertThrows(HoldExpiredException.class, () -> service.confirm(alice, booking.id()));

        assertEquals(List.of(voidFailure), List.of(e.getSuppressed()));
        assertEquals(BookingStatus.EXPIRED, booking.status());
        assertEquals(List.of(), payments.captures());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
    }

    @Test
    void failedCaptureKeepsTheSeatsAndRetriesExactlyWhenTheBackoffEnds() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.failCaptures(1);

        assertThrows(PaymentPendingException.class, () -> service.confirm(alice, booking.id()));
        assertEquals(BookingStatus.PAYMENT_PENDING, booking.status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));

        clock.advance(CAPTURE_RETRY.initialBackoff().minusNanos(1));
        service.reconcilePendingPayments();
        assertEquals(1, payments.captureAttempts());

        clock.advance(Duration.ofNanos(1));
        service.reconcilePendingPayments();
        assertEquals(2, payments.captureAttempts());
        assertEquals(BookingStatus.CONFIRMED, booking.status());
        assertEquals(1, payments.captures().size());
        assertEquals(List.of("confirmed " + booking.id()), deliveredEvents());
    }

    @Test
    void secondRetryWaitsTwiceAsLongAsTheFirst() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.failCaptures(2);
        assertThrows(PaymentPendingException.class, () -> service.confirm(alice, booking.id()));
        clock.advance(Duration.ofSeconds(30));
        service.reconcilePendingPayments();

        clock.advance(Duration.ofSeconds(60).minusNanos(1));
        service.reconcilePendingPayments();
        assertEquals(2, payments.captureAttempts());

        clock.advance(Duration.ofNanos(1));
        service.reconcilePendingPayments();
        assertEquals(3, payments.captureAttempts());
        assertEquals(BookingStatus.CONFIRMED, booking.status());
    }

    @Test
    void givesUpAfterThreeAttemptsThenReleasesTheSeatsAndVoids() {
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.failCaptures(3);
        assertThrows(PaymentPendingException.class, () -> service.confirm(alice, booking.id()));
        clock.advance(Duration.ofSeconds(30));
        assertEquals(List.of(), service.reconcilePendingPayments().seatsReleasedBookingIds());
        clock.advance(Duration.ofSeconds(60));

        ReconciliationReport lastAttempt = service.reconcilePendingPayments();

        assertEquals(3, payments.captureAttempts());
        assertEquals(List.of(booking.id()), lastAttempt.seatsReleasedBookingIds());
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, booking.status());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        service.holdSeats(bob, "sh", List.of("A2"));

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.PAYMENT_FAILED, booking.status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(3, payments.captureAttempts());
        assertEquals(0, dc.used());
        assertEquals(List.of("payment failed " + booking.id()), deliveredEvents());
    }

    @Test
    void rejectedCaptureStopsRetryingAndReleasesTheSeatsAtOnce() {
        PaymentRejectedException rejected = new PaymentRejectedException("BAD_REQUEST_ERROR");
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.failNextCapture(rejected);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.confirm(alice, booking.id()));

        assertSame(rejected, thrown);
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, booking.status());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));

        clock.advance(Duration.ofHours(1));
        service.reconcilePendingPayments();
        assertEquals(1, payments.captureAttempts());
        assertEquals(BookingStatus.PAYMENT_FAILED, booking.status());
        assertEquals(0, dc.used());
    }

    @Test
    void rejectionThatMeansAlreadyCapturedStillConfirms() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new PaymentRejectedException("payment has already been captured"));

        service.confirm(alice, booking.id());

        assertEquals(BookingStatus.CONFIRMED, booking.status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void retriedCaptureRejectedAsAlreadyCapturedStillConfirms() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.failCaptures(1);
        assertThrows(PaymentPendingException.class, () -> service.confirm(alice, booking.id()));
        payments.loseNextCaptureResponse(new PaymentRejectedException("payment has already been captured"));
        clock.advance(Duration.ofSeconds(30));

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.CONFIRMED, booking.status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void failedVoidAfterReleasingTheSeatsIsRetriedOnTheNextRun() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.failNextCapture(new PaymentRejectedException("BAD_REQUEST_ERROR"));
        assertThrows(PaymentRejectedException.class, () -> service.confirm(alice, booking.id()));
        payments.failNextVoid(new RuntimeException("void timed out"));

        ReconciliationReport failed = service.reconcilePendingPayments();
        assertEquals(1, failed.failures().size());
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, booking.status());
        assertEquals(PaymentStatus.AUTHORIZED, payments.state("auth_1"));

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.PAYMENT_FAILED, booking.status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
    }

    @Test
    void confirmsWhenCaptureWentThroughButItsResponseWasLost() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));

        service.confirm(alice, booking.id());

        assertEquals(BookingStatus.CONFIRMED, booking.status());
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

        PaymentPendingException e = assertThrows(PaymentPendingException.class, () -> service.confirm(alice, booking.id()));

        assertSame(timeout, e.getCause());
        assertEquals(BookingStatus.PAYMENT_PENDING, booking.status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertThrows(SeatsUnavailableException.class, () -> service.holdSeats(bob, "sh", List.of("A1")));
        assertEquals(List.of(), deliveredEvents());
    }

    @Test
    void reconciliationConfirmsAPendingBookingTheProcessorCaptured() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(1);
        assertThrows(PaymentPendingException.class, () -> service.confirm(alice, booking.id()));

        service.reconcilePendingPayments();

        assertEquals(BookingStatus.CONFIRMED, booking.status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
        assertEquals(List.of("confirmed " + booking.id()), deliveredEvents());
    }

    @Test
    void reconciliationKeepsRetryingWhileTheProcessorIsUnreachable() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(2);
        assertThrows(PaymentPendingException.class, () -> service.confirm(alice, booking.id()));

        ReconciliationReport firstRun = service.reconcilePendingPayments();
        assertEquals(List.of(booking.id()), firstRun.failures().stream().map(ReconciliationReport.Failure::bookingId).toList());
        assertEquals("processor unreachable", firstRun.failures().getFirst().error().getMessage());
        assertEquals(BookingStatus.PAYMENT_PENDING, booking.status());

        ReconciliationReport secondRun = service.reconcilePendingPayments();
        assertEquals(List.of(booking.id()), secondRun.settledBookingIds());
        assertEquals(List.of(), secondRun.failures());
        assertEquals(BookingStatus.CONFIRMED, booking.status());
    }

    @Test
    void reconciliationSettlesOtherBookingsWhenOneCannotBeSettled() {
        Booking stuck = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(1);
        assertThrows(PaymentPendingException.class, () -> service.confirm(alice, stuck.id()));
        Booking settleable = service.holdSeats(bob, "sh", List.of("A2"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(1);
        assertThrows(PaymentPendingException.class, () -> service.confirm(bob, settleable.id()));

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
        assertThrows(PaymentPendingException.class, () -> service.confirm(user, booking.id()));
        return booking;
    }

    @Test
    void keepsPendingSeatsBlockedUntilTheDeadline() {
        Booking booking = pendingBooking(alice, "sh", "A1", 100, true);
        clock.advance(MAX_PENDING.minusNanos(1));

        ReconciliationReport report = service.reconcilePendingPayments();

        assertEquals(List.of(), report.seatsReleasedBookingIds());
        assertEquals(BookingStatus.PAYMENT_PENDING, booking.status());
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void releasesPendingSeatsForResaleAtTheDeadline() {
        Booking booking = pendingBooking(alice, "sh", "A1", 100, true);
        clock.advance(MAX_PENDING);

        ReconciliationReport report = service.reconcilePendingPayments();

        assertEquals(List.of(booking.id()), report.seatsReleasedBookingIds());
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, booking.status());
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

        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, booking.status());
    }

    @Test
    void refundsInFullWhenAnUnresolvedPaymentTurnsOutCaptured() {
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(2);
        assertThrows(PaymentPendingException.class, () -> service.confirm(alice, booking.id()));
        clock.advance(MAX_PENDING);
        service.reconcilePendingPayments();
        service.holdSeats(bob, "sh", List.of("A1"));

        ReconciliationReport report = service.reconcilePendingPayments();

        assertEquals(List.of(booking.id()), report.settledBookingIds());
        assertEquals(BookingStatus.PAYMENT_REFUNDED, booking.status());
        assertEquals(List.of(new FakePaymentGateway.Refund("auth_1", 15_000)), payments.refunds());
        assertEquals(15_000, booking.refundedPaise());
        assertEquals(0, dc.used());
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

        assertEquals(BookingStatus.PAYMENT_FAILED, booking.status());
        assertEquals(PaymentStatus.RELEASED, payments.state("auth_1"));
        assertEquals(List.of(), payments.refunds());
        service.confirm(bob, resold.id());
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
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, booking.status());

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.PAYMENT_REFUNDED, booking.status());
        assertEquals(1, payments.refunds().size());
    }

    @Test
    void retryingARefundWhoseResponseWasLostDoesNotRefundTwice() {
        Booking booking = pendingBooking(alice, "sh", "A1", 2, true);
        clock.advance(MAX_PENDING);
        service.reconcilePendingPayments();
        payments.loseNextRefundResponse(new RuntimeException("read timeout"));

        service.reconcilePendingPayments();
        assertEquals(BookingStatus.PAYMENT_UNRESOLVED, booking.status());
        service.reconcilePendingPayments();

        assertEquals(BookingStatus.PAYMENT_REFUNDED, booking.status());
        assertEquals(1, payments.refunds().size());
    }

    @Test
    void rejectsCancellingABookingWhosePaymentIsPending() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(1);
        assertThrows(PaymentPendingException.class, () -> service.confirm(alice, booking.id()));

        assertThrows(IllegalBookingStateException.class, () -> service.cancel(alice, booking.id()));
        assertEquals(List.of(), payments.refunds());
    }

    @Test
    void rejectsConfirmAfterExpiryWithoutContactingTheGateway() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        clock.advance(TTL);

        assertThrows(HoldExpiredException.class, () -> service.confirm(alice, booking.id()));
        assertEquals(BookingStatus.EXPIRED, booking.status());
        assertEquals(0, payments.authorizeAttempts());
    }

    @Test
    void rejectsConfirmingTwice() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        service.confirm(alice, booking.id());

        assertThrows(IllegalBookingStateException.class, () -> service.confirm(alice, booking.id()));
        assertEquals(1, payments.captures().size());
    }

    @Test
    void secondConfirmArrivingMidPaymentWaitsAndIsRejected() throws Exception {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        AtomicReference<Throwable> secondOutcome = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                service.confirm(alice, booking.id());
            } catch (Throwable t) {
                secondOutcome.set(t);
            }
        });
        // Start the second confirm while the first is inside authorize(), and wait until it is parked on a monitor.
        payments.duringAuthorize(() -> {
            second.start();
            awaitBlocked(second);
        });

        service.confirm(alice, booking.id());
        second.join(5_000);

        assertInstanceOf(IllegalBookingStateException.class, secondOutcome.get());
        // Idempotency would hide a second authorization; the booking lock must stop the request reaching the gateway.
        assertEquals(1, payments.authorizeAttempts());
        assertEquals(1, payments.captures().size());
        assertEquals(BookingStatus.CONFIRMED, booking.status());
    }

    private static void awaitBlocked(Thread thread) {
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
                service.confirm(users.get(i), b.id());
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
        assertThrows(NoSuchElementException.class, () -> service.confirm(bob, booking.id()));
        assertThrows(NoSuchElementException.class, () -> service.release(bob, booking.id()));
        assertEquals(BookingStatus.HELD, booking.status());
    }

    @Test
    void releaseFreesTheSeatsForOthers() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        service.release(alice, booking.id());

        assertEquals(BookingStatus.RELEASED, booking.status());
        service.holdSeats(bob, "sh", List.of("A1"));
        assertThrows(IllegalBookingStateException.class, () -> service.confirm(alice, booking.id()));
    }

    // --- discounts ---

    @Test
    void appliesDiscountCodeToTheQuoteAndConsumesAUseOnConfirm() {
        DiscountCode dc = code("FLAT50", new Discount.Flat(5_000), 0, 5);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "FLAT50");
        assertEquals(15_000, booking.price().totalPaise());
        assertEquals(0, dc.used());

        service.confirm(alice, booking.id());
        assertEquals(1, dc.used());
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
                service.confirm(owners.get(i), both.get(i).id());
                return true;
            } catch (DiscountRejectedException e) {
                return false;
            }
        });

        assertEquals(1, Race.winners(outcomes));
        assertEquals(1, dc.used());
        assertEquals(1, payments.captures().size());
        Booking loser = outcomes.get(0) ? b : a;
        assertEquals(BookingStatus.HELD, loser.status());
    }

    @Test
    void declinedPaymentGivesTheDiscountUseBack() {
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.declineNext("card expired");

        assertThrows(PaymentDeclinedException.class, () -> service.confirm(alice, booking.id()));
        assertEquals(0, dc.used());
    }

    @Test
    void expiryDuringPaymentGivesTheDiscountUseBack() {
        DiscountCode dc = code("ONE", new Discount.Flat(5_000), 0, 1);
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"), "ONE");
        payments.duringAuthorize(() -> clock.advance(TTL));

        assertThrows(HoldExpiredException.class, () -> service.confirm(alice, booking.id()));
        assertEquals(0, dc.used());
    }

    // --- cancel & refund ---

    @Test
    void cancelRefundsPerPolicyAndFreesTheSeats() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        service.confirm(alice, booking.id());

        service.cancel(alice, booking.id());

        assertEquals(BookingStatus.CANCELLED, booking.status());
        assertEquals(10_000, booking.refundedPaise());
        assertEquals(List.of(new FakePaymentGateway.Refund("auth_1", 10_000)), payments.refunds());
        service.holdSeats(bob, "sh", List.of("A1"));
        assertEquals(List.of("confirmed " + booking.id(), "cancelled " + booking.id()), deliveredEvents());
    }

    @Test
    void cancelWithExactlyFourHoursNoticeStillGetsTheHalfRefund() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        service.confirm(alice, booking.id());
        clock.set(WEEKDAY_SHOW.minus(Duration.ofHours(4)));
        assertEquals(10_000, service.cancel(alice, booking.id()).refundedPaise());
    }

    @Test
    void cancelJustUnderFourHoursBeforeShowRefundsNothing() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        service.confirm(alice, booking.id());
        clock.set(WEEKDAY_SHOW.minus(Duration.ofHours(4)).plusNanos(1));

        service.cancel(alice, booking.id());
        assertEquals(0, booking.refundedPaise());
        assertEquals(List.of(), payments.refunds());
    }

    @Test
    void rejectsCancelOnceTheShowHasStarted() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        service.confirm(alice, booking.id());
        clock.set(WEEKDAY_SHOW);

        assertThrows(ShowAlreadyStartedException.class, () -> service.cancel(alice, booking.id()));
        assertEquals(BookingStatus.CONFIRMED, booking.status());
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
        service.confirm(alice, booking.id());
        service.cancel(alice, booking.id());
        assertThrows(IllegalBookingStateException.class, () -> service.cancel(alice, booking.id()));
        assertEquals(1, payments.refunds().size());
    }

    // --- notifications ---

    @Test
    void publishesTheEventWithoutDeliveringItInsideConfirm() {
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        service.confirm(alice, booking.id());

        assertEquals(List.of(), events);
        assertEquals(1, outbox.entries(DeliveryStatus.PENDING).size());
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
        NotificationOutbox brokenOutbox = new NotificationOutbox(List.of(broken, recorder), new RetryPolicy(3, Duration.ofSeconds(30)), Duration.ofDays(30), clock);
        BookingService withBrokenListener = service(brokenOutbox);
        Booking booking = withBrokenListener.holdSeats(alice, "sh", List.of("A1"));

        withBrokenListener.confirm(alice, booking.id());
        brokenOutbox.dispatchDue();

        assertEquals(BookingStatus.CONFIRMED, booking.status());
        assertEquals(List.of("confirmed " + booking.id()), events);
        OutboxEntry retrying = brokenOutbox.entries(DeliveryStatus.PENDING).getFirst();
        assertEquals(Optional.of(boom), retrying.lastError());
    }
}
