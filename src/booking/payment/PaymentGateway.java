package booking.payment;

/**
 * Two-phase payment: funds are reserved first and only taken once the seats are secured, so losing the seats
 * during payment never leaves the customer charged.
 */
public interface PaymentGateway {
    /** Reserves funds without taking them. Same {@code idempotencyKey} twice must not reserve twice. */
    PaymentResult authorize(String idempotencyKey, String userId, long amountPaise);

    /** Takes the reserved funds. Capturing the same authorization twice must not charge twice. */
    void capture(String authorizationId);

    /**
     * Releases reserved funds early; an authorization that is never captured also lapses on its own. Once voided,
     * the processor rejects any capture of it, including one still in flight.
     */
    void voidAuthorization(String authorizationId);

    /** The processor's own record: the source of truth when a call's outcome is unknown, e.g. it timed out. */
    PaymentStatus status(String authorizationId);

    /** Same {@code idempotencyKey} twice must not refund twice, so a refund whose response was lost can be retried. */
    void refund(String idempotencyKey, String paymentId, long amountPaise);
}
