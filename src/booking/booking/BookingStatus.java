package booking.booking;

public enum BookingStatus {
    HELD, CONFIRMED, RELEASED, EXPIRED, CANCELLED;

    public boolean canTransitionTo(BookingStatus next) {
        return switch (this) {
            case HELD -> next == CONFIRMED || next == RELEASED || next == EXPIRED;
            case CONFIRMED -> next == CANCELLED;
            case RELEASED, EXPIRED, CANCELLED -> false;
        };
    }
}
