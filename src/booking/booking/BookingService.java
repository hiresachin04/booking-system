package booking.booking;

import booking.discount.DiscountCode;
import booking.discount.DiscountRegistry;
import booking.discount.DiscountRejectedException;
import booking.inventory.HoldExpiredException;
import booking.inventory.ShowInventory;
import booking.model.Seat;
import booking.model.Show;
import booking.model.User;
import booking.notify.BookingEventType;
import booking.notify.NotificationOutbox;
import booking.payment.PaymentGateway;
import booking.payment.PaymentResult;
import booking.payment.PaymentStatus;
import booking.pricing.PriceQuoteBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class BookingService {
    private final ShowRepository shows;
    private final BookingRepository bookings;
    private final DiscountRegistry discounts;
    private final PaymentGateway payments;
    private final RefundPolicy refundPolicy;
    private final BookingConfig config;
    private final Clock clock;
    private final NotificationOutbox outbox;

    public BookingService(ShowRepository shows, BookingRepository bookings, DiscountRegistry discounts, PaymentGateway payments,
                          RefundPolicy refundPolicy, BookingConfig config, Clock clock, NotificationOutbox outbox) {
        this.shows = Objects.requireNonNull(shows, "shows");
        this.bookings = Objects.requireNonNull(bookings, "bookings");
        this.discounts = Objects.requireNonNull(discounts, "discounts");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.refundPolicy = Objects.requireNonNull(refundPolicy, "refundPolicy");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    public Booking holdSeats(User user, String showId, List<String> seatIds) {
        return hold(user, showId, seatIds, null);
    }

    public Booking holdSeats(User user, String showId, List<String> seatIds, String discountCode) {
        return hold(user, showId, seatIds, Objects.requireNonNull(discountCode, "discountCode"));
    }

    private Booking hold(User user, String showId, List<String> seatIds, String code) {
        Objects.requireNonNull(user, "user");
        if (seatIds.size() > config.maxSeatsPerBooking()) {
            throw new IllegalArgumentException("at most " + config.maxSeatsPerBooking() + " seats per booking, got " + seatIds.size());
        }
        ShowInventory inventory = inventoryOf(showId);
        Show show = inventory.show();
        Instant now = clock.instant();
        requireNotStarted(show, now);

        List<Seat> seats = seatIds.stream()
                .map(id -> show.screen().seat(id).orElseThrow(() -> new IllegalArgumentException("seat " + id + " is not in show " + showId)))
                .toList();
        PriceQuoteBuilder quote = new PriceQuoteBuilder(show, seats).weekendSurchargeBps(config.weekendSurchargeBps());
        DiscountCode discount = null;
        if (code != null) {
            discount = discounts.find(code).orElseThrow(() -> new DiscountRejectedException(code, "unknown code"));
            discount.checkApplicable(quote.build().totalPaise(), now);
            quote.discount(discount.discount());
        }

        Booking booking = new Booking(UUID.randomUUID().toString(), user, show, seatIds, quote.build(), now.plus(config.holdTtl()), discount);
        inventory.hold(booking.id(), booking.seatIds(), booking.holdExpiresAt());
        bookings.save(booking);
        return booking;
    }

    public Booking confirm(User user, String bookingId) {
        Booking booking = ownedBy(user, bookingId);
        ShowInventory inventory = inventoryOf(booking.show().id());
        synchronized (booking) {
            booking.requireCanTransitionTo(BookingStatus.CONFIRMED);
            Instant now = clock.instant();
            requireNotStarted(booking.show(), now);
            if (!now.isBefore(booking.holdExpiresAt())) {
                inventory.release(booking.id(), booking.seatIds());
                booking.markExpired();
                throw new HoldExpiredException(booking.id());
            }

            DiscountCode discount = booking.discountCode().orElse(null);
            if (discount != null && !discount.tryConsume(now)) {
                throw new DiscountRejectedException(discount.code(), "no longer valid or usage limit reached");
            }
            String authorizationId;
            try {
                authorizationId = authorize(booking);
            } catch (RuntimeException e) {
                if (discount != null) discount.releaseUse();
                throw e;
            }

            // Authorization ran outside the show lock, so the hold may have lapsed and the seats gone to someone else.
            // Only funds were reserved, so losing the seats costs the customer nothing.
            try {
                inventory.confirm(booking.id(), booking.seatIds());
            } catch (HoldExpiredException e) {
                if (discount != null) discount.releaseUse();
                booking.markExpired();
                voidAuthorization(authorizationId, e);
                throw e;
            }

            // Seats are secured; take the money. A failed capture call doesn't prove the money wasn't taken (the
            // response may have timed out after the processor captured), so the seats stay ours until it says so.
            try {
                payments.capture(authorizationId);
            } catch (RuntimeException captureFailure) {
                booking.markPaymentPending(authorizationId, clock.instant());
                try {
                    settlePayment(booking, inventory);
                } catch (RuntimeException settleFailure) {
                    captureFailure.addSuppressed(settleFailure);
                    throw new PaymentPendingException(booking.id(), captureFailure);
                }
                if (booking.status() == BookingStatus.PAYMENT_FAILED) throw captureFailure;
            }
            if (booking.status() != BookingStatus.CONFIRMED) booking.markConfirmed(authorizationId);
            // Recorded in the same step as the state change; delivery happens later and can't fail this call.
            outbox.publish(BookingEventType.CONFIRMED, booking);
        }
        return booking;
    }

    public Booking release(User user, String bookingId) {
        Booking booking = ownedBy(user, bookingId);
        ShowInventory inventory = inventoryOf(booking.show().id());
        synchronized (booking) {
            booking.requireCanTransitionTo(BookingStatus.RELEASED);
            inventory.release(booking.id(), booking.seatIds());
            booking.markReleased();
        }
        return booking;
    }

    public Booking cancel(User user, String bookingId) {
        Booking booking = ownedBy(user, bookingId);
        ShowInventory inventory = inventoryOf(booking.show().id());
        synchronized (booking) {
            booking.requireCanTransitionTo(BookingStatus.CANCELLED);
            Instant now = clock.instant();
            requireNotStarted(booking.show(), now);
            long refund = refundPolicy.refundPaise(booking.price().totalPaise(), Duration.between(now, booking.show().startsAt()));
            String paymentId = booking.paymentId().orElseThrow(() -> new IllegalStateException("confirmed booking " + bookingId + " has no payment"));
            // Refund first: if the gateway fails, the booking and its seats are untouched and the cancel can be retried.
            if (refund > 0) payments.refund(booking.id() + ":cancel", paymentId, refund);
            inventory.cancel(booking.id(), booking.seatIds());
            booking.markCancelled(refund);
            outbox.publish(BookingEventType.CANCELLED, booking);
        }
        return booking;
    }

    private String authorize(Booking booking) {
        // Booking id as idempotency key: a retried confirm for the same booking can't reserve funds twice.
        PaymentResult result = payments.authorize(booking.id(), booking.user().id(), booking.price().totalPaise());
        return switch (result) {
            case PaymentResult.Approved approved -> approved.authorizationId();
            case PaymentResult.Declined declined -> throw new PaymentDeclinedException(booking.id(), declined.reason());
        };
    }

    /**
     * Retries every booking whose capture outcome is still unknown. Meant to run on a schedule, so it reports
     * failures instead of throwing them: an exception escaping a scheduled task cancels every later run.
     * A PAYMENT_PENDING booking still unsettled at its deadline (see {@link BookingConfig#maxPaymentPending()},
     * or show start if sooner) gives its seats back for resale and becomes PAYMENT_UNRESOLVED; its payment keeps
     * being reconciled, and is refunded in full if it turns out to have been captured.
     */
    public ReconciliationReport reconcilePendingPayments() {
        List<Booking> open = new ArrayList<>(bookings.findByStatus(BookingStatus.PAYMENT_PENDING));
        open.addAll(bookings.findByStatus(BookingStatus.PAYMENT_UNRESOLVED));
        List<String> settled = new ArrayList<>();
        List<String> seatsReleased = new ArrayList<>();
        List<ReconciliationReport.Failure> failures = new ArrayList<>();
        for (Booking booking : open) {
            ShowInventory inventory = inventoryOf(booking.show().id());
            synchronized (booking) {
                BookingStatus before = booking.status();
                if (before != BookingStatus.PAYMENT_PENDING && before != BookingStatus.PAYMENT_UNRESOLVED) continue;
                try {
                    settlePayment(booking, inventory);
                    // The customer was told the payment was pending, so every outcome reached here is news to them.
                    switch (booking.status()) {
                        case CONFIRMED -> outbox.publish(BookingEventType.CONFIRMED, booking);
                        case PAYMENT_FAILED -> outbox.publish(BookingEventType.PAYMENT_FAILED, booking);
                        case PAYMENT_REFUNDED -> outbox.publish(BookingEventType.PAYMENT_REFUNDED, booking);
                        default -> throw new IllegalStateException("settled booking " + booking.id() + " left in " + booking.status());
                    }
                    settled.add(booking.id());
                } catch (RuntimeException e) {
                    failures.add(new ReconciliationReport.Failure(booking.id(), e));
                }
                if (booking.status() == BookingStatus.PAYMENT_PENDING && !clock.instant().isBefore(pendingDeadline(booking))) {
                    inventory.cancel(booking.id(), booking.seatIds());
                    booking.markPaymentUnresolved();
                    seatsReleased.add(booking.id());
                }
            }
        }
        return new ReconciliationReport(settled, seatsReleased, failures);
    }

    private Instant pendingDeadline(Booking booking) {
        Instant timeout = booking.paymentPendingSince().plus(config.maxPaymentPending());
        Instant showStart = booking.show().startsAt();
        return timeout.isBefore(showStart) ? timeout : showStart;
    }

    /**
     * Lets the processor's record decide a booking whose capture outcome was unknown. While the booking still
     * holds its seats (PAYMENT_PENDING), they are freed only once the processor says the money can no longer be
     * taken. If it can't be reached, or a void or refund fails, this throws and the booking is left as it was.
     */
    private void settlePayment(Booking booking, ShowInventory inventory) {
        String authorizationId = booking.paymentId().orElseThrow();
        boolean holdsSeats = booking.status() == BookingStatus.PAYMENT_PENDING;
        PaymentStatus status = payments.status(authorizationId);
        if (status == PaymentStatus.AUTHORIZED) {
            payments.voidAuthorization(authorizationId);
            status = PaymentStatus.VOIDED;
        }
        switch (status) {
            case CAPTURED -> {
                if (holdsSeats) {
                    booking.markConfirmed(authorizationId);
                } else {
                    long paid = booking.price().totalPaise();
                    payments.refund(booking.id() + ":unresolved", authorizationId, paid);
                    booking.discountCode().ifPresent(DiscountCode::releaseUse);
                    booking.markPaymentRefunded(paid);
                }
            }
            case VOIDED -> {
                if (holdsSeats) inventory.cancel(booking.id(), booking.seatIds());
                booking.discountCode().ifPresent(DiscountCode::releaseUse);
                booking.markPaymentFailed();
            }
            case AUTHORIZED -> throw new IllegalStateException("unreachable: authorization was just voided");
        }
    }

    // A failed void rides along on the primary exception instead of replacing it. It isn't fatal: an authorization
    // that is never captured lapses at the bank, so the customer is not charged either way.
    private void voidAuthorization(String authorizationId, RuntimeException primary) {
        try {
            payments.voidAuthorization(authorizationId);
        } catch (RuntimeException voidFailure) {
            primary.addSuppressed(voidFailure);
        }
    }

    // Someone else's booking reads as missing rather than forbidden, so booking ids can't be probed.
    private Booking ownedBy(User user, String bookingId) {
        return bookings.find(bookingId)
                .filter(b -> b.user().equals(user))
                .orElseThrow(() -> new NoSuchElementException("no booking " + bookingId));
    }

    private ShowInventory inventoryOf(String showId) {
        return shows.find(showId).orElseThrow(() -> new NoSuchElementException("no show " + showId));
    }

    private static void requireNotStarted(Show show, Instant now) {
        if (!now.isBefore(show.startsAt())) throw new ShowAlreadyStartedException(show.id());
    }
}
