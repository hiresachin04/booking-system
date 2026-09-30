package booking.payment;

import booking.support.FakePaymentGateway;
import booking.support.MutableClock;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The promises BookingService relies on, checked against both processor profiles. A real adapter would be run
 * through the same assertions.
 */
class PaymentGatewayContractTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    private static final Duration RAZORPAY_EXPIRY = Duration.ofMinutes(12);

    private final MutableClock clock = new MutableClock(NOW);

    record Profile(Function<MutableClock, FakePaymentGateway> create, Duration authorizationExpiry) {}

    static Stream<Arguments> profiles() {
        return Stream.of(
                Arguments.of(Named.of("stripe", new Profile(FakePaymentGateway::stripe, Duration.ofDays(7)))),
                Arguments.of(Named.of("razorpay", new Profile(c -> FakePaymentGateway.razorpay(c, RAZORPAY_EXPIRY), RAZORPAY_EXPIRY))));
    }

    private static String authorize(PaymentGateway gateway, String key) {
        return switch (gateway.authorize(key, "u1", 20_000)) {
            case PaymentResult.Approved approved -> approved.authorizationId();
            case PaymentResult.Declined declined -> throw new AssertionError("declined: " + declined.reason());
        };
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void authorizingTwiceWithOneKeyReservesOnce(Profile profile) {
        FakePaymentGateway gateway = profile.create().apply(clock);
        String first = authorize(gateway, "booking-0001");
        String second = authorize(gateway, "booking-0001");

        assertEquals(first, second);
        assertEquals(PaymentStatus.AUTHORIZED, gateway.status(first));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void capturingTwiceNeverChargesTwice(Profile profile) {
        FakePaymentGateway gateway = profile.create().apply(clock);
        String id = authorize(gateway, "booking-0001");
        gateway.capture(id, 20_000);

        try {
            gateway.capture(id, 20_000);
        } catch (PaymentRejectedException allowedByTheContract) {
            // Razorpay answers a second capture with BAD_REQUEST_ERROR; Stripe treats it as a no-op.
        }

        assertEquals(1, gateway.captures().size());
        assertEquals(PaymentStatus.CAPTURED, gateway.status(id));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void refundingTwiceWithOneKeyRefundsOnce(Profile profile) {
        FakePaymentGateway gateway = profile.create().apply(clock);
        String id = authorize(gateway, "booking-0001");
        gateway.capture(id, 20_000);

        gateway.refund("booking-0001_cancel", id, 10_000);
        gateway.refund("booking-0001_cancel", id, 10_000);

        assertEquals(1, gateway.refunds().size());
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void uncapturedAuthorizationIsReleasedExactlyAtExpiryAndCanNoLongerBeCaptured(Profile profile) {
        FakePaymentGateway gateway = profile.create().apply(clock);
        String id = authorize(gateway, "booking-0001");

        clock.advance(profile.authorizationExpiry().minusNanos(1));
        assertEquals(PaymentStatus.AUTHORIZED, gateway.status(id));

        clock.advance(Duration.ofNanos(1));
        assertEquals(PaymentStatus.RELEASED, gateway.status(id));
        assertThrows(PaymentRejectedException.class, () -> gateway.capture(id, 20_000));
        assertEquals(0, gateway.captures().size());
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void successfulVoidMeansTheAuthorizationCanNeverBeCaptured(Profile profile) {
        FakePaymentGateway gateway = profile.create().apply(clock);
        String id = authorize(gateway, "booking-0001");

        if (gateway.tryVoid(id)) {
            assertEquals(PaymentStatus.RELEASED, gateway.status(id));
            assertThrows(PaymentRejectedException.class, () -> gateway.capture(id, 20_000));
        } else {
            assertEquals(PaymentStatus.AUTHORIZED, gateway.status(id));
        }
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void rejectsACaptureForADifferentAmountThanWasAuthorized(Profile profile) {
        FakePaymentGateway gateway = profile.create().apply(clock);
        String id = authorize(gateway, "booking-0001");

        assertThrows(PaymentRejectedException.class, () -> gateway.capture(id, 19_999));
        assertEquals(PaymentStatus.AUTHORIZED, gateway.status(id));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void rejectsIdempotencyKeysRazorpayWouldRefuse(Profile profile) {
        FakePaymentGateway gateway = profile.create().apply(clock);
        String id = authorize(gateway, "booking-0001");
        gateway.capture(id, 20_000);

        assertThrows(PaymentRejectedException.class, () -> gateway.refund("booking-0001:cancel", id, 10_000));
        assertThrows(PaymentRejectedException.class, () -> gateway.refund("short", id, 10_000));
        assertEquals(0, gateway.refunds().size());
    }

    @Test
    void stripeCanVoid() {
        FakePaymentGateway gateway = FakePaymentGateway.stripe(clock);
        assertTrue(gateway.tryVoid(authorize(gateway, "booking-0001")));
    }

    @Test
    void razorpayCannotVoid() {
        FakePaymentGateway gateway = FakePaymentGateway.razorpay(clock, RAZORPAY_EXPIRY);
        String id = authorize(gateway, "booking-0001");

        assertFalse(gateway.tryVoid(id));
        assertEquals(PaymentStatus.AUTHORIZED, gateway.status(id));
    }

    @Test
    void razorpayRejectsASecondCapture() {
        FakePaymentGateway gateway = FakePaymentGateway.razorpay(clock, RAZORPAY_EXPIRY);
        String id = authorize(gateway, "booking-0001");
        gateway.capture(id, 20_000);

        assertThrows(PaymentRejectedException.class, () -> gateway.capture(id, 20_000));
    }
}
