package booking.payment;

/**
 * Two-phase payment: funds are reserved first and only taken once the seats are secured, so losing the seats
 * during payment never leaves the customer charged.
 */
public interface PaymentGateway {
    /** Reserves funds without taking them. Same {@code idempotencyKey} twice must not reserve twice. */
    PaymentResult authorize(String idempotencyKey, String userId, long amountPaise);

    /**
     * Takes the reserved funds. Capturing the same authorization twice must not charge twice; the second call may
     * succeed or throw {@link PaymentRejectedException}. A refusal that retrying can't fix is thrown as
     * {@link PaymentRejectedException}; any other exception means the outcome is unknown.
     */
    void capture(String authorizationId);

    /**
     * Cancels the authorization now if the processor supports that, returning {@code true}; once voided, any capture
     * of it is rejected, including one still in flight. Returns {@code false} for processors that can't void
     * (Razorpay): the authorization then stays open until the processor's own expiry refunds it.
     */
    boolean tryVoid(String authorizationId);

    /** The processor's own record: the source of truth when a call's outcome is unknown, e.g. it timed out. */
    PaymentStatus status(String authorizationId);

    /** Same {@code idempotencyKey} twice must not refund twice, so a refund whose response was lost can be retried. */
    void refund(String idempotencyKey, String paymentId, long amountPaise);
}
