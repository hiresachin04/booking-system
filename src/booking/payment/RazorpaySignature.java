package booking.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Razorpay's checkout signature: hex HMAC-SHA256 of {@code order_id|payment_id}, keyed with the API key secret. */
public final class RazorpaySignature {
    private RazorpaySignature() {}

    public static String sign(String keySecret, String orderId, String paymentId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    // Constant-time comparison, so response timing doesn't reveal how much of a forged signature was right.
    public static boolean matches(String keySecret, CheckoutResult result) {
        byte[] expected = sign(keySecret, result.orderId(), result.paymentId()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, result.signature().getBytes(StandardCharsets.UTF_8));
    }
}
