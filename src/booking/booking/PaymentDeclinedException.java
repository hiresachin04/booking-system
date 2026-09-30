package booking.booking;

public final class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String bookingId, String reason) {
        super("payment for booking " + bookingId + " declined: " + reason);
    }
}
