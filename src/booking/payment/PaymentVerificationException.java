package booking.payment;

/** The browser's checkout result is forged, tampered with, or for a different order or amount. */
public final class PaymentVerificationException extends RuntimeException {
    public PaymentVerificationException(String message) {
        super(message);
    }
}
