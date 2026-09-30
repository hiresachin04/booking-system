package booking.booking;

/** The seats are kept for the customer while the payment outcome is unknown; reconciliation will settle it. */
public final class PaymentPendingException extends RuntimeException {
    public PaymentPendingException(String bookingId, Throwable cause) {
        super("payment for booking " + bookingId + " is pending: outcome unknown", cause);
    }
}
