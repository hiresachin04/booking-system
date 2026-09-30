package booking.payment;

import java.util.Objects;

/** What the customer's browser needs to pay (Razorpay Checkout's options). Nothing is reserved until they do. */
public record CheckoutSession(String orderId, long amountPaise, String currency, String publicKeyId) {
    public CheckoutSession {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(publicKeyId, "publicKeyId");
        if (amountPaise <= 0) throw new IllegalArgumentException("amountPaise must be positive");
    }
}
