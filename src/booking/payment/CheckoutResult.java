package booking.payment;

import java.util.Objects;

/** What the browser hands back after paying. Untrusted until {@link PaymentGateway#verifyCheckout} checks it. */
public record CheckoutResult(String orderId, String paymentId, String signature) {
    public CheckoutResult {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(signature, "signature");
    }
}
