package booking.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against Razorpay's real API in test mode. Runs only when RAZORPAY_KEY_ID is a test key (rzp_test_…), so a live
 * key in the environment can never make the suite touch real money. The payment itself happens in a browser
 * (Checkout's mock bank page), so this covers what the server can do alone: auth, orders, verification.
 */
@EnabledIfEnvironmentVariable(named = "RAZORPAY_KEY_ID", matches = "rzp_test_.+")
class RazorpayLiveTest {
    private final HttpClient http = HttpClient.newHttpClient();
    private final RazorpayPaymentGateway.Config config = RazorpayPaymentGateway.Config.fromEnvironment(System.getenv());
    private final RazorpayPaymentGateway gateway = new RazorpayPaymentGateway(http, config);

    @AfterEach
    void close() {
        http.close();
    }

    private static String reference() {
        return "test-" + UUID.randomUUID().toString().substring(0, 30);
    }

    @Test
    void createsAManualCaptureOrderInTestMode() {
        CheckoutSession session = gateway.startPayment(reference(), "live-test", 100);

        assertTrue(session.orderId().startsWith("order_"), session.orderId());
        assertEquals(100, session.amountPaise());
        assertEquals("INR", session.currency());
    }

    @Test
    void rejectsAForgedResultWithoutAskingRazorpay() {
        CheckoutSession session = gateway.startPayment(reference(), "live-test", 100);

        assertThrows(PaymentVerificationException.class,
                () -> gateway.verifyCheckout(session, new CheckoutResult(session.orderId(), "pay_forged", "0".repeat(64))));
    }

    @Test
    void rejectsACorrectlySignedIdThatRazorpayHasNeverSeen() {
        CheckoutSession session = gateway.startPayment(reference(), "live-test", 100);
        String paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        CheckoutResult madeUp = new CheckoutResult(session.orderId(), paymentId,
                RazorpaySignature.sign(config.keySecret(), session.orderId(), paymentId));

        assertThrows(PaymentVerificationException.class, () -> gateway.verifyCheckout(session, madeUp));
    }
}
