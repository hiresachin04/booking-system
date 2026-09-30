package booking.payment;

public interface PaymentGateway {
    /** Same {@code idempotencyKey} twice must not charge twice. */
    PaymentResult charge(String idempotencyKey, String userId, long amountPaise);

    void refund(String chargeId, long amountPaise);
}
