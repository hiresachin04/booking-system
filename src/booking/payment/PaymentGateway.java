package booking.payment;

import java.util.Optional;

/**
 * Two-phase payment: funds are reserved first and only taken once the seats are secured, so losing the seats
 * during payment never leaves the customer charged. Idempotency keys are at least 10 characters of letters, digits,
 * {@code -} and {@code _}, the strictest rule among supported processors (Razorpay's).
 */
public interface PaymentGateway {
    /**
     * Creates the order the customer pays in their browser; nothing is reserved yet. {@code reference} is ours
     * (the booking id) and is recorded on the order.
     */
    CheckoutSession startPayment(String reference, String userId, long amountPaise);

    /**
     * Checks the browser's result against the processor's own record, never trusting the browser for status or
     * amount. Returns the authorization id if the payment for this exact order and amount is authorized, or
     * {@link PaymentResult.Declined} if that payment failed. Throws {@link PaymentVerificationException} if the
     * result is forged or belongs to another order or amount. Verifying the same result twice gives the same answer.
     */
    PaymentResult verifyCheckout(CheckoutSession session, CheckoutResult result);

    /**
     * Asks the processor whether this order has been paid, without the browser's result: for when the customer paid
     * but the browser never reported it (tab closed, phone slept). Returns the authorization id of a payment for this
     * exact order and amount that is authorized or captured, the earliest if there are several; empty if none.
     */
    Optional<String> findPaidAuthorization(CheckoutSession session);

    /**
     * Takes the reserved funds. Capturing the same authorization twice must not charge twice; the second call may
     * succeed or throw {@link PaymentRejectedException}. A refusal that retrying can't fix is thrown as
     * {@link PaymentRejectedException}; any other exception means the outcome is unknown.
     */
    void capture(String authorizationId, long amountPaise);

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
