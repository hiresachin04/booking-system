package booking.booking;

import booking.discount.DiscountCode;
import booking.discount.DiscountRegistry;
import booking.discount.DiscountRejectedException;
import booking.inventory.HoldExpiredException;
import booking.inventory.ShowInventory;
import booking.model.Seat;
import booking.model.Show;
import booking.model.User;
import booking.notify.BookingListener;
import booking.payment.PaymentGateway;
import booking.payment.PaymentResult;
import booking.pricing.PriceQuoteBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class BookingService {
    private final ShowRepository shows;
    private final BookingRepository bookings;
    private final DiscountRegistry discounts;
    private final PaymentGateway payments;
    private final RefundPolicy refundPolicy;
    private final BookingConfig config;
    private final Clock clock;
    private final List<BookingListener> listeners;

    public BookingService(ShowRepository shows, BookingRepository bookings, DiscountRegistry discounts, PaymentGateway payments,
                          RefundPolicy refundPolicy, BookingConfig config, Clock clock, List<BookingListener> listeners) {
        this.shows = Objects.requireNonNull(shows, "shows");
        this.bookings = Objects.requireNonNull(bookings, "bookings");
        this.discounts = Objects.requireNonNull(discounts, "discounts");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.refundPolicy = Objects.requireNonNull(refundPolicy, "refundPolicy");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listeners = List.copyOf(listeners);
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

            // Seats are secured; take the money. If that fails, give the seats back rather than keep them unpaid.
            try {
                payments.capture(authorizationId);
            } catch (RuntimeException e) {
                inventory.cancel(booking.id(), booking.seatIds());
                if (discount != null) discount.releaseUse();
                booking.markPaymentFailed();
                voidAuthorization(authorizationId, e);
                throw e;
            }
            booking.markConfirmed(authorizationId);
        }
        notifyListeners(l -> l.onConfirmed(booking));
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
            if (refund > 0) payments.refund(paymentId, refund);
            inventory.cancel(booking.id(), booking.seatIds());
            booking.markCancelled(refund);
        }
        notifyListeners(l -> l.onCancelled(booking));
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

    // A failed void rides along on the primary exception instead of replacing it. It isn't fatal: an authorization
    // that is never captured lapses at the bank, so the customer is not charged either way.
    private void voidAuthorization(String authorizationId, RuntimeException primary) {
        try {
            payments.voidAuthorization(authorizationId);
        } catch (RuntimeException voidFailure) {
            primary.addSuppressed(voidFailure);
        }
    }

    // Every listener runs even if an earlier one throws; the first failure propagates with the rest suppressed.
    private void notifyListeners(Consumer<BookingListener> event) {
        RuntimeException failure = null;
        for (BookingListener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
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
