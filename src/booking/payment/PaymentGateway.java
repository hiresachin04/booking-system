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

    /** Releases reserved funds early; an authorization that is never captured also lapses on its own. */
    void voidAuthorization(String authorizationId);

    void refund(String paymentId, long amountPaise);
}
