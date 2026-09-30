package booking.booking;

public enum BookingStatus {
    HELD, PAYMENT_PENDING, CONFIRMED, RELEASED, EXPIRED, PAYMENT_FAILED, CANCELLED;

    public boolean canTransitionTo(BookingStatus next) {
        return switch (this) {
            case HELD -> next == CONFIRMED || next == RELEASED || next == EXPIRED || next == PAYMENT_PENDING;
            case PAYMENT_PENDING -> next == CONFIRMED || next == PAYMENT_FAILED;
            case CONFIRMED -> next == CANCELLED;
            case RELEASED, EXPIRED, PAYMENT_FAILED, CANCELLED -> false;
        };
    }
}
