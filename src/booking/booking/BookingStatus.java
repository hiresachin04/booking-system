package booking.booking;

public enum BookingStatus {
    HELD, PAYMENT_PENDING, PAYMENT_UNRESOLVED, CONFIRMED, RELEASED, EXPIRED, PAYMENT_FAILED, PAYMENT_REFUNDED, CANCELLED;

    public boolean canTransitionTo(BookingStatus next) {
        return switch (this) {
            case HELD -> next == CONFIRMED || next == RELEASED || next == EXPIRED || next == PAYMENT_PENDING;
            // EXPIRED: the hold ran out after the payment was checkpointed but before the seats could be booked.
            case PAYMENT_PENDING -> next == CONFIRMED || next == PAYMENT_FAILED || next == PAYMENT_UNRESOLVED || next == EXPIRED;
            // Seats already released for resale, so a capture can only end in a refund, never CONFIRMED.
            case PAYMENT_UNRESOLVED -> next == PAYMENT_FAILED || next == PAYMENT_REFUNDED;
            case CONFIRMED -> next == CANCELLED;
            case RELEASED, EXPIRED, PAYMENT_FAILED, PAYMENT_REFUNDED, CANCELLED -> false;
        };
    }
}
