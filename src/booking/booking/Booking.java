package booking.booking;

import booking.discount.DiscountCode;
import booking.model.Show;
import booking.model.User;
import booking.pricing.PriceComponent;

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
    private final DiscountCode discountCode;
    private BookingStatus status = BookingStatus.HELD;
    private String chargeId;
    private long refundedPaise;

    Booking(String id, User user, Show show, List<String> seatIds, PriceComponent price, Instant holdExpiresAt, DiscountCode discountCode) {
        this.id = Objects.requireNonNull(id, "id");
        this.user = Objects.requireNonNull(user, "user");
        this.show = Objects.requireNonNull(show, "show");
        this.seatIds = List.copyOf(seatIds);
        this.price = Objects.requireNonNull(price, "price");
        this.holdExpiresAt = Objects.requireNonNull(holdExpiresAt, "holdExpiresAt");
        this.discountCode = discountCode;
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

    public Optional<DiscountCode> discountCode() {
        return Optional.ofNullable(discountCode);
    }

    public synchronized BookingStatus status() {
        return status;
    }

    public synchronized Optional<String> chargeId() {
        return Optional.ofNullable(chargeId);
    }

    public synchronized long refundedPaise() {
        return refundedPaise;
    }

    synchronized void requireCanTransitionTo(BookingStatus next) {
        if (!status.canTransitionTo(next)) throw new IllegalBookingStateException(id, status, next);
    }

    synchronized void markConfirmed(String chargeId) {
        transitionTo(BookingStatus.CONFIRMED);
        this.chargeId = Objects.requireNonNull(chargeId, "chargeId");
    }

    /** {@code chargeId} is recorded even on expiry so a failed refund leaves a trace of the money taken. */
    synchronized void markExpired(String chargeId) {
        transitionTo(BookingStatus.EXPIRED);
        this.chargeId = chargeId;
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
