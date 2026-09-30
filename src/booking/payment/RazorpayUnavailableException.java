package booking.payment;

/**
 * A Razorpay call whose outcome is unknown: timed out, failed in transit, or answered 409/429/5xx. The request may
 * or may not have taken effect, so callers check status or retry rather than assume either way.
 */
public final class RazorpayUnavailableException extends RuntimeException {
    public RazorpayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
