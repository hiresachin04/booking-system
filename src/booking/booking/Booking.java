package booking.booking;

import booking.model.Show;
import booking.payment.CheckoutSession;
import booking.model.User;
import booking.pricing.PriceComponent;
import booking.retry.RetryPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Mutable state is guarded by this object's monitor; {@link BookingService} holds it across compound steps. */
public final class Booking {
    private final String id;
    private final User user;
    private final Show show;
    private final List<String> seatIds;
    private final PriceComponent price;
    private final Instant holdExpiresAt;
    private final String discountCode;
    private final CheckoutSession checkout;
    private BookingStatus status = BookingStatus.HELD;
    private String paymentId;
    private Instant paymentPendingSince;
    private int captureAttempts;
    private Instant nextCaptureAt;
    private boolean captureGivenUp;
    private long refundedPaise;

    Booking(String id, User user, Show show, List<String> seatIds, PriceComponent price, Instant holdExpiresAt, String discountCode,
            CheckoutSession checkout) {
        this.id = Objects.requireNonNull(id, "id");
        this.user = Objects.requireNonNull(user, "user");
        this.show = Objects.requireNonNull(show, "show");
        this.seatIds = List.copyOf(seatIds);
        this.price = Objects.requireNonNull(price, "price");
        this.holdExpiresAt = Objects.requireNonNull(holdExpiresAt, "holdExpiresAt");
        this.discountCode = discountCode;
        this.checkout = Objects.requireNonNull(checkout, "checkout");
    }

    /** The mutable part of a booking, so a store can save it and later {@link #restore} it exactly. */
    record State(BookingStatus status, String paymentId, Instant paymentPendingSince, int captureAttempts, Instant nextCaptureAt,
                 boolean captureGivenUp, long refundedPaise) {
        State {
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * Rebuilds a saved booking. It sets the state directly rather than replaying transitions: the saved state already
     * passed the transition rules when it was reached.
     */
    static Booking restore(String id, User user, Show show, List<String> seatIds, PriceComponent price, Instant holdExpiresAt,
                           String discountCode, CheckoutSession checkout, State state) {
        Booking booking = new Booking(id, user, show, seatIds, price, holdExpiresAt, discountCode, checkout);
        booking.status = state.status();
        booking.paymentId = state.paymentId();
        booking.paymentPendingSince = state.paymentPendingSince();
        booking.captureAttempts = state.captureAttempts();
        booking.nextCaptureAt = state.nextCaptureAt();
        booking.captureGivenUp = state.captureGivenUp();
        booking.refundedPaise = state.refundedPaise();
        return booking;
    }

    synchronized State state() {
        return new State(status, paymentId, paymentPendingSince, captureAttempts, nextCaptureAt, captureGivenUp, refundedPaise);
    }

    public String id() {
        return id;
    }

    public User user() {
        return user;
    }

    public Show show() {
        return show;
    }

    public List<String> seatIds() {
        return seatIds;
    }

    public PriceComponent price() {
        return price;
    }

    public Instant holdExpiresAt() {
        return holdExpiresAt;
    }

    /** Handed to the frontend so the customer can pay for exactly this booking's order. */
    public CheckoutSession checkout() {
        return checkout;
    }

    /** The code applied to {@link #price()}; its uses are counted by the DiscountStore, not here. */
    public Optional<String> discountCode() {
        return Optional.ofNullable(discountCode);
    }

    public synchronized BookingStatus status() {
        return status;
    }

    /** The authorization, recorded once capture has been attempted so an unknown outcome can be looked up. */
    public synchronized Optional<String> paymentId() {
        return Optional.ofNullable(paymentId);
    }

    public synchronized long refundedPaise() {
        return refundedPaise;
    }

    synchronized void requireCanTransitionTo(BookingStatus next) {
        if (!status.canTransitionTo(next)) throw new IllegalBookingStateException(id, status, next);
    }

    synchronized void markConfirmed(String paymentId) {
        transitionTo(BookingStatus.CONFIRMED);
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
    }

    /** Capture is due from {@code since}: if the caller dies before trying it, reconciliation tries it straight away. */
    synchronized void markPaymentPending(String authorizationId, Instant since) {
        transitionTo(BookingStatus.PAYMENT_PENDING);
        this.paymentId = Objects.requireNonNull(authorizationId, "authorizationId");
        this.paymentPendingSince = Objects.requireNonNull(since, "since");
        this.nextCaptureAt = since;
    }

    synchronized Instant paymentPendingSince() {
        if (paymentPendingSince == null) throw new IllegalStateException("booking " + id + " never went pending");
        return paymentPendingSince;
    }

    /** A permanent rejection, or running out of attempts, stops the retries; otherwise the next one is scheduled. */
    synchronized void recordFailedCapture(Instant now, boolean permanent, RetryPolicy retry) {
        captureAttempts++;
        if (permanent || captureAttempts >= retry.maxAttempts()) captureGivenUp = true;
        else nextCaptureAt = now.plus(retry.backoffAfter(captureAttempts));
    }

    synchronized boolean isCaptureDue(Instant now) {
        return !captureGivenUp && nextCaptureAt != null && !now.isBefore(nextCaptureAt);
    }

    synchronized boolean captureGivenUp() {
        return captureGivenUp;
    }

    synchronized void markPaymentUnresolved() {
        transitionTo(BookingStatus.PAYMENT_UNRESOLVED);
    }

    synchronized void markPaymentRefunded(long refundedPaise) {
        transitionTo(BookingStatus.PAYMENT_REFUNDED);
        this.refundedPaise = refundedPaise;
    }

    synchronized void markExpired() {
        transitionTo(BookingStatus.EXPIRED);
    }

    synchronized void markPaymentFailed() {
        transitionTo(BookingStatus.PAYMENT_FAILED);
    }

    synchronized void markReleased() {
        transitionTo(BookingStatus.RELEASED);
    }

    synchronized void markCancelled(long refundedPaise) {
        transitionTo(BookingStatus.CANCELLED);
        this.refundedPaise = refundedPaise;
    }

    private void transitionTo(BookingStatus next) {
        requireCanTransitionTo(next);
        status = next;
    }
}
