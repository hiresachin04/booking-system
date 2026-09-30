package booking.payment;

/**
 * The processor definitively refused the request (e.g. Razorpay's BAD_REQUEST_ERROR), so retrying it won't help.
 * Any other exception from a gateway call means the outcome is unknown, such as a timeout or server error.
 */
public class PaymentRejectedException extends RuntimeException {
    public PaymentRejectedException(String message) {
        super(message);
    }
}
