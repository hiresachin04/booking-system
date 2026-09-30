package booking.inventory;

public final class HoldExpiredException extends RuntimeException {
    public HoldExpiredException(String bookingId) {
        super("hold for booking " + bookingId + " is no longer valid");
    }
}
