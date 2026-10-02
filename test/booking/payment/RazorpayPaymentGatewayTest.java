package booking.payment;

import booking.booking.Booking;
import booking.booking.BookingConfig;
import booking.booking.BookingService;
import booking.booking.BookingStatus;
import booking.booking.InMemoryStorage;
import booking.booking.TieredRefundPolicy;
import booking.model.User;
import booking.notify.NotificationOutbox;
import booking.retry.RetryPolicy;
import booking.support.FakeRazorpayServer;
import booking.support.Fixtures;
import booking.support.MutableClock;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the contract test can't see: the HTTP requests themselves, error mapping, timeouts, and configuration. */
class RazorpayPaymentGatewayTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final Duration LONGER_THAN_TIMEOUT = Duration.ofSeconds(1);

    private final MutableClock clock = new MutableClock(NOW);
    private final FakeRazorpayServer server = start();
    private final HttpClient http = HttpClient.newHttpClient();
    private final RazorpayPaymentGateway gateway = gateway(FakeRazorpayServer.KEY_SECRET);

    private FakeRazorpayServer start() {
        try {
            return new FakeRazorpayServer(clock);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not start fake Razorpay", e);
        }
    }

    private RazorpayPaymentGateway gateway(String secret) {
        return new RazorpayPaymentGateway(http, new RazorpayPaymentGateway.Config(
                server.baseUrl(), FakeRazorpayServer.KEY_ID, secret, "INR", Duration.ofMinutes(20), TIMEOUT));
    }

    @AfterEach
    void stop() {
        http.close();
        server.close();
    }

    private String authorized() {
        CheckoutSession session = gateway.startPayment("booking-0001", "u1", 20_000);
        return switch (gateway.verifyCheckout(session, server.simulateCheckout(session))) {
            case PaymentResult.Approved a -> a.authorizationId();
            case PaymentResult.Declined d -> throw new AssertionError(d.reason());
        };
    }

    private FakeRazorpayServer.Recorded last(String method, String pathSuffix) {
        return server.requests().reversed().stream()
                .filter(r -> r.method().equals(method) && r.path().endsWith(pathSuffix))
                .findFirst().orElseThrow(() -> new AssertionError("no " + method + " …" + pathSuffix));
    }

    // --- requests ---

    @Test
    void createsTheOrderForManualCaptureWithTheConfiguredExpiry() {
        CheckoutSession session = gateway.startPayment("booking-0001", "u1", 20_000);

        JSONObject body = new JSONObject(last("POST", "/v1/orders").body());
        assertEquals(20_000, body.getLong("amount"));
        assertEquals("INR", body.getString("currency"));
        assertEquals("booking-0001", body.getString("receipt"));
        assertEquals("manual", body.getJSONObject("payment").getString("capture"));
        assertEquals(20, body.getJSONObject("payment").getJSONObject("capture_options").getLong("manual_expiry_period"));
        assertEquals(FakeRazorpayServer.KEY_ID, session.publicKeyId());
        assertTrue(session.orderId().startsWith("order_"));
    }

    @Test
    void authenticatesWithBasicAuthOverTheKeyPair() {
        gateway.startPayment("booking-0001", "u1", 20_000);

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (FakeRazorpayServer.KEY_ID + ":" + FakeRazorpayServer.KEY_SECRET).getBytes());
        assertEquals(expected, last("POST", "/v1/orders").headers().get("authorization"));
    }

    @Test
    void wrongCredentialsAreAConfigurationErrorNotARejectedPayment() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> gateway("wrong_secret").startPayment("booking-0001", "u1", 20_000));
        assertTrue(e.getMessage().contains("credentials"));
    }

    @Test
    void capturesTheExactAmountInTheConfiguredCurrency() {
        String id = authorized();
        gateway.capture(id, 20_000);

        JSONObject body = new JSONObject(last("POST", "/capture").body());
        assertEquals(20_000, body.getLong("amount"));
        assertEquals("INR", body.getString("currency"));
    }

    @Test
    void sendsTheRefundIdempotencyKeyAsRazorpaysHeader() {
        String id = authorized();
        gateway.capture(id, 20_000);

        gateway.refund("booking-0001_cancel", id, 10_000);

        assertEquals("booking-0001_cancel", last("POST", "/refund").headers().get("x-refund-idempotency"));
    }

    @Test
    void neverVoidsBecauseRazorpayHasNoVoid() {
        String id = authorized();
        int before = server.requests().size();

        assertFalse(gateway.tryVoid(id));
        assertEquals(before, server.requests().size());
    }

    // --- verification ---

    @Test
    void rejectsAForgedSignatureWithoutCallingRazorpay() {
        CheckoutSession session = gateway.startPayment("booking-0001", "u1", 20_000);
        CheckoutResult paid = server.simulateCheckout(session);
        int before = server.requests().size();

        assertThrows(PaymentVerificationException.class,
                () -> gateway.verifyCheckout(session, new CheckoutResult(paid.orderId(), paid.paymentId(), "f".repeat(64))));
        assertEquals(before, server.requests().size());
    }

    @Test
    void rejectsACorrectlySignedIdForAPaymentThatDoesNotExist() {
        CheckoutSession session = gateway.startPayment("booking-0001", "u1", 20_000);
        CheckoutResult madeUp = new CheckoutResult(session.orderId(), "pay_404",
                RazorpaySignature.sign(FakeRazorpayServer.KEY_SECRET, session.orderId(), "pay_404"));

        assertThrows(PaymentVerificationException.class, () -> gateway.verifyCheckout(session, madeUp));
    }

    @Test
    void rejectsOurOrderSignedTogetherWithAnotherOrdersPayment() {
        CheckoutSession mine = gateway.startPayment("booking-0001", "u1", 20_000);
        CheckoutSession other = gateway.startPayment("booking-0002", "u1", 20_000);
        String othersPayment = server.simulateCheckout(other).paymentId();
        // Only possible with the key secret, so this checks the backstop behind the signature: Razorpay's own record.
        CheckoutResult spliced = new CheckoutResult(mine.orderId(), othersPayment,
                RazorpaySignature.sign(FakeRazorpayServer.KEY_SECRET, mine.orderId(), othersPayment));

        assertThrows(PaymentVerificationException.class, () -> gateway.verifyCheckout(mine, spliced));
    }

    // --- error mapping ---

    @Test
    void mapsA400ToARejectionThatRetryingWontFix() {
        String id = authorized();
        server.respondNext("POST", "/v1/payments/" + id + "/capture", 400,
                "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Capture amount must be equal\"}}");

        PaymentRejectedException e = assertThrows(PaymentRejectedException.class, () -> gateway.capture(id, 20_000));
        assertTrue(e.getMessage().contains("BAD_REQUEST_ERROR: Capture amount must be equal"));
    }

    @Test
    void mapsServerErrorsConflictsAndRateLimitsToOutcomeUnknown() {
        String id = authorized();
        for (int status : new int[] {500, 502, 503, 409, 429}) {
            server.respondNext("POST", "/v1/payments/" + id + "/capture", status, "{\"error\":{\"code\":\"SERVER_ERROR\"}}");
            assertThrows(RazorpayUnavailableException.class, () -> gateway.capture(id, 20_000), "HTTP " + status);
        }
        assertEquals(0, server.captureCount());
    }

    @Test
    void mapsATimeoutToOutcomeUnknown() {
        String id = authorized();
        server.stallNext("GET", "/v1/payments/" + id, LONGER_THAN_TIMEOUT, false);

        assertThrows(RazorpayUnavailableException.class, () -> gateway.status(id));
    }

    @Test
    void captureWhoseResponseTimedOutIsVisibleThroughStatus() {
        String id = authorized();
        server.stallNext("POST", "/v1/payments/" + id + "/capture", LONGER_THAN_TIMEOUT, true);

        assertThrows(RazorpayUnavailableException.class, () -> gateway.capture(id, 20_000));

        assertEquals(PaymentStatus.CAPTURED, gateway.status(id));
    }

    @Test
    void unreadableSuccessResponseIsOutcomeUnknownNotSuccess() {
        String id = authorized();
        server.respondNext("POST", "/v1/payments/" + id + "/capture", 200, "<html>gateway maintenance</html>");

        assertThrows(RazorpayUnavailableException.class, () -> gateway.capture(id, 20_000));
    }

    @Test
    void readsAnExpiredUncapturedPaymentAsReleasedAndACapturedRefundedOneAsCaptured() {
        String expired = authorized();
        clock.advance(Duration.ofMinutes(20));
        assertEquals(PaymentStatus.RELEASED, gateway.status(expired));

        CheckoutSession session = gateway.startPayment("booking-0002", "u1", 20_000);
        String refunded = ((PaymentResult.Approved) gateway.verifyCheckout(session, server.simulateCheckout(session))).authorizationId();
        gateway.capture(refunded, 20_000);
        gateway.refund("booking-0002_unresolved", refunded, 20_000);
        assertEquals("refunded", server.paymentStatus(refunded));
        assertEquals(PaymentStatus.CAPTURED, gateway.status(refunded));
    }

    // --- configuration ---

    @Test
    void configToStringNeverContainsTheSecret() {
        RazorpayPaymentGateway.Config config = RazorpayPaymentGateway.Config.fromEnvironment(
                Map.of("RAZORPAY_KEY_ID", "rzp_test_abc", "RAZORPAY_KEY_SECRET", "super_secret_value"));

        assertFalse(config.toString().contains("super_secret_value"));
        assertTrue(config.toString().contains("rzp_test_abc"));
    }

    @Test
    void missingKeyIsReportedByVariableName() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RazorpayPaymentGateway.Config.fromEnvironment(Map.of("RAZORPAY_KEY_ID", "rzp_test_abc")));
        assertEquals("RAZORPAY_KEY_SECRET is not set", e.getMessage());
    }

    @Test
    void manualExpiryMustBeWholeMinutesWithinRazorpaysLimit() {
        for (Duration bad : List.of(Duration.ZERO, Duration.ofMinutes(7201), Duration.ofSeconds(90))) {
            assertThrows(IllegalArgumentException.class, () -> new RazorpayPaymentGateway.Config(
                    server.baseUrl(), "k", "s", "INR", bad, TIMEOUT), bad.toString());
        }
        new RazorpayPaymentGateway.Config(server.baseUrl(), "k", "s", "INR", Duration.ofMinutes(7200), TIMEOUT);
    }

    // --- end to end ---

    @Test
    void booksAndCapturesThroughTheAdapterEvenWhenTheCaptureResponseIsLost() {
        InMemoryStorage storage = new InMemoryStorage(clock);
        storage.shows().add(Fixtures.show("sh", NOW.plus(Duration.ofHours(8))));
        BookingService service = new BookingService(storage, gateway,
                new TieredRefundPolicy(List.of()), new BookingConfig(Duration.ofMinutes(10), 10, 0, Duration.ofMinutes(15),
                BookingConfig.DEFAULT_CAPTURE_RETRY), clock,
                new NotificationOutbox(List.of(), new RetryPolicy(3, Duration.ofSeconds(30)), Duration.ofDays(1), clock));
        User alice = new User("u1", "Alice");
        Booking booking = service.holdSeats(alice, "sh", List.of("A1"));
        CheckoutResult paid = server.simulateCheckout(booking.checkout());
        server.stallNext("POST", "/capture", LONGER_THAN_TIMEOUT, true);

        service.confirm(alice, booking.id(), paid);

        assertEquals(BookingStatus.CONFIRMED, booking.status());
        assertEquals(1, server.captureCount());
    }
}
