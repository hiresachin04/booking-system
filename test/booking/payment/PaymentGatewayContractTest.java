package booking.payment;

import booking.support.FakePaymentGateway;
import booking.support.FakeRazorpayServer;
import booking.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The promises BookingService relies on, checked against the Stripe and Razorpay fakes and against the real
 * RazorpayPaymentGateway talking HTTP to FakeRazorpayServer.
 */
class PaymentGatewayContractTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    private static final Duration RAZORPAY_EXPIRY = Duration.ofMinutes(12);

    private final MutableClock clock = new MutableClock(NOW);
    private final List<Harness> opened = new ArrayList<>();

    /** A gateway plus the test-only controls around it: the customer's browser and the processor's own records. */
    interface Harness extends AutoCloseable {
        PaymentGateway gateway();

        CheckoutResult pay(CheckoutSession session);

        void declineNextPayment(String reason);

        int captureCount();

        int refundCount();

        Duration authorizationExpiry();

        @Override
        void close();
    }

    interface HarnessFactory {
        Harness open(MutableClock clock) throws Exception;
    }

    private static Harness fake(FakePaymentGateway fake, Duration expiry) {
        return new Harness() {
            public PaymentGateway gateway() { return fake; }
            public CheckoutResult pay(CheckoutSession session) { return fake.simulateCheckout(session); }
            public void declineNextPayment(String reason) { fake.declineNext(reason); }
            public int captureCount() { return fake.captures().size(); }
            public int refundCount() { return fake.refunds().size(); }
            public Duration authorizationExpiry() { return expiry; }
            public void close() {}
        };
    }

    static Harness razorpayOverHttp(MutableClock clock) throws Exception {
        FakeRazorpayServer server = new FakeRazorpayServer(clock);
        HttpClient http = HttpClient.newHttpClient();
        RazorpayPaymentGateway adapter = new RazorpayPaymentGateway(http, new RazorpayPaymentGateway.Config(
                server.baseUrl(), FakeRazorpayServer.KEY_ID, FakeRazorpayServer.KEY_SECRET, "INR", RAZORPAY_EXPIRY, Duration.ofSeconds(5)));
        return new Harness() {
            public PaymentGateway gateway() { return adapter; }
            public CheckoutResult pay(CheckoutSession session) { return server.simulateCheckout(session); }
            public void declineNextPayment(String reason) { server.declineNextPayment(reason); }
            public int captureCount() { return server.captureCount(); }
            public int refundCount() { return server.refunds().size(); }
            public Duration authorizationExpiry() { return RAZORPAY_EXPIRY; }
            public void close() {
                http.close();
                server.close();
            }
        };
    }

    static Stream<Arguments> profiles() {
        return Stream.concat(Stream.of(Arguments.of(Named.of("stripe fake",
                (HarnessFactory) c -> fake(FakePaymentGateway.stripe(c), Duration.ofDays(7))))), razorpayProfiles());
    }

    static Stream<Arguments> razorpayProfiles() {
        return Stream.of(
                Arguments.of(Named.of("razorpay fake",
                        (HarnessFactory) c -> fake(FakePaymentGateway.razorpay(c, RAZORPAY_EXPIRY), RAZORPAY_EXPIRY))),
                Arguments.of(Named.of("razorpay adapter over HTTP", (HarnessFactory) PaymentGatewayContractTest::razorpayOverHttp)));
    }

    private Harness open(HarnessFactory factory) throws Exception {
        Harness harness = factory.open(clock);
        opened.add(harness);
        return harness;
    }

    @AfterEach
    void closeHarnesses() {
        opened.forEach(Harness::close);
    }

    /** Starts a payment, pays it in the simulated browser, verifies it, and returns the authorization id. */
    private static String authorize(Harness h, String reference) {
        CheckoutSession session = h.gateway().startPayment(reference, "u1", 20_000);
        return approved(h.gateway().verifyCheckout(session, h.pay(session)));
    }

    private static String approved(PaymentResult result) {
        return switch (result) {
            case PaymentResult.Approved a -> a.authorizationId();
            case PaymentResult.Declined d -> throw new AssertionError("declined: " + d.reason());
        };
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void verifyingTheSameResultTwiceGivesTheSameAuthorization(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        CheckoutSession session = gateway.startPayment("booking-0001", "u1", 20_000);
        CheckoutResult paid = h.pay(session);

        String first = approved(gateway.verifyCheckout(session, paid));
        String second = approved(gateway.verifyCheckout(session, paid));

        assertEquals(first, second);
        assertEquals(PaymentStatus.AUTHORIZED, gateway.status(first));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void rejectsAResultWhoseSignatureDoesNotMatch(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        CheckoutSession session = gateway.startPayment("booking-0001", "u1", 20_000);
        CheckoutResult paid = h.pay(session);

        assertThrows(PaymentVerificationException.class,
                () -> gateway.verifyCheckout(session, new CheckoutResult(paid.orderId(), paid.paymentId(), paid.signature() + "0")));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void rejectsAGenuineResultForAnotherOrder(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        CheckoutSession mine = gateway.startPayment("booking-0001", "u1", 20_000);
        CheckoutSession other = gateway.startPayment("booking-0002", "u1", 20_000);

        assertThrows(PaymentVerificationException.class, () -> gateway.verifyCheckout(mine, h.pay(other)));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void rejectsAPaymentForADifferentAmountThanTheSessionExpects(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        CheckoutSession session = gateway.startPayment("booking-0001", "u1", 20_000);
        CheckoutResult paid = h.pay(session);
        CheckoutSession pricier = new CheckoutSession(session.orderId(), 55_000, session.currency(), session.publicKeyId());

        assertThrows(PaymentVerificationException.class, () -> gateway.verifyCheckout(pricier, paid));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void reportsAFailedPaymentAsDeclined(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        CheckoutSession session = gateway.startPayment("booking-0001", "u1", 20_000);
        h.declineNextPayment("insufficient funds");

        PaymentResult result = gateway.verifyCheckout(session, h.pay(session));

        assertEquals(new PaymentResult.Declined("insufficient funds"), result);
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void capturingTwiceNeverChargesTwice(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        String id = authorize(h, "booking-0001");
        gateway.capture(id, 20_000);

        try {
            gateway.capture(id, 20_000);
        } catch (PaymentRejectedException allowedByTheContract) {
            // Razorpay answers a second capture with BAD_REQUEST_ERROR; Stripe treats it as a no-op.
        }

        assertEquals(1, h.captureCount());
        assertEquals(PaymentStatus.CAPTURED, gateway.status(id));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void refundingTwiceWithOneKeyRefundsOnce(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        String id = authorize(h, "booking-0001");
        gateway.capture(id, 20_000);

        gateway.refund("booking-0001_cancel", id, 10_000);
        gateway.refund("booking-0001_cancel", id, 10_000);

        assertEquals(1, h.refundCount());
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void uncapturedAuthorizationIsReleasedExactlyAtExpiryAndCanNoLongerBeCaptured(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        String id = authorize(h, "booking-0001");

        clock.advance(h.authorizationExpiry().minusNanos(1));
        assertEquals(PaymentStatus.AUTHORIZED, gateway.status(id));

        clock.advance(Duration.ofNanos(1));
        assertEquals(PaymentStatus.RELEASED, gateway.status(id));
        assertThrows(PaymentRejectedException.class, () -> gateway.capture(id, 20_000));
        assertEquals(0, h.captureCount());
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void successfulVoidMeansTheAuthorizationCanNeverBeCaptured(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        String id = authorize(h, "booking-0001");

        if (gateway.tryVoid(id)) {
            assertEquals(PaymentStatus.RELEASED, gateway.status(id));
            assertThrows(PaymentRejectedException.class, () -> gateway.capture(id, 20_000));
        } else {
            assertEquals(PaymentStatus.AUTHORIZED, gateway.status(id));
        }
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void rejectsACaptureForADifferentAmountThanWasAuthorized(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        String id = authorize(h, "booking-0001");

        assertThrows(PaymentRejectedException.class, () -> gateway.capture(id, 19_999));
        assertEquals(PaymentStatus.AUTHORIZED, gateway.status(id));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void rejectsIdempotencyKeysRazorpayWouldRefuse(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        PaymentGateway gateway = h.gateway();
        String id = authorize(h, "booking-0001");
        gateway.capture(id, 20_000);

        assertThrows(PaymentRejectedException.class, () -> gateway.refund("booking-0001:cancel", id, 10_000));
        assertThrows(PaymentRejectedException.class, () -> gateway.refund("short", id, 10_000));
        assertEquals(0, h.refundCount());
    }

    @Test
    void stripeCanVoid() throws Exception {
        Harness h = open(c -> fake(FakePaymentGateway.stripe(c), Duration.ofDays(7)));
        assertTrue(h.gateway().tryVoid(authorize(h, "booking-0001")));
    }

    @ParameterizedTest
    @MethodSource("razorpayProfiles")
    void razorpayCannotVoid(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        String id = authorize(h, "booking-0001");

        assertFalse(h.gateway().tryVoid(id));
        assertEquals(PaymentStatus.AUTHORIZED, h.gateway().status(id));
    }

    @ParameterizedTest
    @MethodSource("razorpayProfiles")
    void razorpayRejectsASecondCapture(HarnessFactory profile) throws Exception {
        Harness h = open(profile);
        String id = authorize(h, "booking-0001");
        h.gateway().capture(id, 20_000);

        assertThrows(PaymentRejectedException.class, () -> h.gateway().capture(id, 20_000));
    }
}
