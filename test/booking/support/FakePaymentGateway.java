package booking.support;

import booking.payment.CheckoutResult;
import booking.payment.CheckoutSession;
import booking.payment.PaymentGateway;
import booking.payment.PaymentRejectedException;
import booking.payment.PaymentResult;
import booking.payment.PaymentStatus;
import booking.payment.PaymentVerificationException;
import booking.payment.RazorpaySignature;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stands in for the external processor, the one process boundary in this system. Two profiles, differing only
 * where the real processors do:
 * <ul>
 *   <li>{@link #stripe}: can void; an uncaptured authorization lapses after 7 days; a second capture is a no-op.</li>
 *   <li>{@link #razorpay}: no void API; an uncaptured payment is auto-refunded after {@code manual_expiry_period}
 *       and reads as RELEASED; capturing an already-captured payment is a BAD_REQUEST_ERROR.</li>
 * </ul>
 */
public final class FakePaymentGateway implements PaymentGateway {
    public record Capture(String authorizationId, String userId, long amountPaise) {}

    public record Refund(String paymentId, long amountPaise) {}

    private record Authorization(String orderId, String userId, long amountPaise, Instant authorizedAt) {}

    private record Order(String reference, String userId, long amountPaise) {}

    /** The fake's API key secret; checkout results are signed with it exactly as Razorpay signs them. */
    public static final String KEY_SECRET = "fake_key_secret";
    private static final String KEY_ID = "rzp_test_fake";
    private static final int MAX_RECEIPT_LENGTH = 40;

    private static final Duration STRIPE_AUTHORIZATION_VALIDITY = Duration.ofDays(7);

    private final Clock clock;
    private final Duration authorizationExpiry;
    private final boolean canVoid;
    private final boolean rejectsSecondCapture;

    private final Map<String, Order> orders = new HashMap<>();
    private final Map<String, String> authorizedPaymentByOrder = new HashMap<>();
    private final Map<String, String> orderOfFailedPayment = new HashMap<>();
    private final Map<String, String> failureReasons = new HashMap<>();
    private final Map<String, Authorization> authorizations = new HashMap<>();
    private final Map<String, PaymentStatus> states = new HashMap<>();
    private final List<Capture> captures = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final Set<String> refundKeys = new HashSet<>();
    private int verifyAttempts;
    private RuntimeException failNextStartPayment;
    private int captureAttempts;
    private int unavailableCaptures;
    private Boolean crashDuringNextCapture;
    private String declineNextWith;
    private RuntimeException failNextCapture;
    private RuntimeException loseNextCaptureResponse;
    private int unreachableStatusLookups;
    private RuntimeException failNextVoid;
    private RuntimeException failNextRefund;
    private RuntimeException loseNextRefundResponse;
    private Runnable duringVerify = () -> {};

    /** Stripe-like, on the system clock; for tests that never let an authorization run for 7 days. */
    public FakePaymentGateway() {
        this(Clock.systemUTC(), STRIPE_AUTHORIZATION_VALIDITY, true, false);
    }

    private FakePaymentGateway(Clock clock, Duration authorizationExpiry, boolean canVoid, boolean rejectsSecondCapture) {
        this.clock = clock;
        this.authorizationExpiry = authorizationExpiry;
        this.canVoid = canVoid;
        this.rejectsSecondCapture = rejectsSecondCapture;
    }

    public static FakePaymentGateway stripe(Clock clock) {
        return new FakePaymentGateway(clock, STRIPE_AUTHORIZATION_VALIDITY, true, false);
    }

    public static FakePaymentGateway razorpay(Clock clock, Duration manualExpiryPeriod) {
        return new FakePaymentGateway(clock, manualExpiryPeriod, false, true);
    }

    /** The next {@link #simulateCheckout} ends in a failed payment, as when the bank declines the card. */
    public synchronized void declineNext(String reason) {
        declineNextWith = reason;
    }

    public synchronized void failNextStartPayment(RuntimeException failure) {
        failNextStartPayment = failure;
    }

    public synchronized void failNextCapture(RuntimeException failure) {
        failNextCapture = failure;
    }

    /**
     * The next capture "kills the process" with {@link SimulatedCrash}: before the processor sees it, or, with
     * {@code afterApplying}, after the processor has taken the money.
     */
    public synchronized void crashDuringNextCapture(boolean afterApplying) {
        crashDuringNextCapture = afterApplying;
    }

    /** The next {@code times} captures fail without reaching the processor, like a server error. */
    public synchronized void failCaptures(int times) {
        unavailableCaptures = times;
    }

    /** The next capture goes through at the processor but the caller gets {@code failure}, like a timeout. */
    public synchronized void loseNextCaptureResponse(RuntimeException failure) {
        loseNextCaptureResponse = failure;
    }

    public synchronized void failStatusLookups(int times) {
        unreachableStatusLookups = times;
    }

    public synchronized void failNextRefund(RuntimeException failure) {
        failNextRefund = failure;
    }

    /** The next refund goes through at the processor but the caller gets {@code failure}, like a timeout. */
    public synchronized void loseNextRefundResponse(RuntimeException failure) {
        loseNextRefundResponse = failure;
    }

    public synchronized void failNextVoid(RuntimeException failure) {
        failNextVoid = failure;
    }

    /** Runs inside the next verifyCheckout call, e.g. to let the hold expire while the server is checking. */
    public synchronized void duringVerify(Runnable action) {
        duringVerify = action;
    }

    @Override
    public synchronized CheckoutSession startPayment(String reference, String userId, long amountPaise) {
        if (failNextStartPayment != null) {
            RuntimeException failure = failNextStartPayment;
            failNextStartPayment = null;
            throw failure;
        }
        if (reference.length() > MAX_RECEIPT_LENGTH) throw new PaymentRejectedException("BAD_REQUEST_ERROR: receipt too long");
        String orderId = "order_" + (orders.size() + 1);
        orders.put(orderId, new Order(reference, userId, amountPaise));
        return new CheckoutSession(orderId, amountPaise, "INR", KEY_ID);
    }

    /**
     * Plays the customer's browser: pays the order and returns the signed result the Checkout handler would get.
     * Paying an order that already has an authorized payment returns that payment again.
     */
    public synchronized CheckoutResult simulateCheckout(CheckoutSession session) {
        Order order = orders.get(session.orderId());
        if (order == null) throw new IllegalArgumentException("unknown order " + session.orderId());
        String paymentId = authorizedPaymentByOrder.get(session.orderId());
        if (paymentId == null && declineNextWith != null) {
            paymentId = "failed_" + (failureReasons.size() + 1);
            failureReasons.put(paymentId, declineNextWith);
            orderOfFailedPayment.put(paymentId, session.orderId());
            declineNextWith = null;
        } else if (paymentId == null) {
            paymentId = "auth_" + (authorizations.size() + 1);
            authorizations.put(paymentId, new Authorization(session.orderId(), order.userId(), order.amountPaise(), clock.instant()));
            states.put(paymentId, PaymentStatus.AUTHORIZED);
            authorizedPaymentByOrder.put(session.orderId(), paymentId);
        }
        return new CheckoutResult(session.orderId(), paymentId, RazorpaySignature.sign(KEY_SECRET, session.orderId(), paymentId));
    }

    @Override
    public synchronized PaymentResult verifyCheckout(CheckoutSession session, CheckoutResult result) {
        verifyAttempts++;
        Runnable action = duringVerify;
        duringVerify = () -> {};
        action.run();
        if (!RazorpaySignature.matches(KEY_SECRET, result)) throw new PaymentVerificationException("signature mismatch");
        if (!result.orderId().equals(session.orderId())) {
            throw new PaymentVerificationException("result is for order " + result.orderId() + ", not " + session.orderId());
        }
        String failureReason = failureReasons.get(result.paymentId());
        if (failureReason != null && session.orderId().equals(orderOfFailedPayment.get(result.paymentId()))) {
            return new PaymentResult.Declined(failureReason);
        }
        Authorization auth = authorizations.get(result.paymentId());
        if (auth == null || !auth.orderId().equals(session.orderId())) {
            throw new PaymentVerificationException("payment " + result.paymentId() + " is not for order " + session.orderId());
        }
        if (auth.amountPaise() != session.amountPaise()) {
            throw new PaymentVerificationException("paid " + auth.amountPaise() + " paise, expected " + session.amountPaise());
        }
        if (requireKnown(result.paymentId()) == PaymentStatus.RELEASED) return new PaymentResult.Declined("authorization expired");
        return new PaymentResult.Approved(result.paymentId());
    }

    @Override
    public synchronized void capture(String authorizationId, long amountPaise) {
        captureAttempts++;
        Boolean crash = crashDuringNextCapture;
        crashDuringNextCapture = null;
        if (Boolean.FALSE.equals(crash)) throw new SimulatedCrash("before the capture reached the processor");
        if (unavailableCaptures > 0) {
            unavailableCaptures--;
            throw new RuntimeException("processor unavailable");
        }
        if (failNextCapture != null) {
            RuntimeException failure = failNextCapture;
            failNextCapture = null;
            throw failure;
        }
        PaymentStatus state = requireKnown(authorizationId);
        if (state == PaymentStatus.CAPTURED) {
            if (rejectsSecondCapture) throw new PaymentRejectedException("BAD_REQUEST_ERROR: payment has already been captured");
            return;
        }
        if (state == PaymentStatus.RELEASED) throw new PaymentRejectedException("BAD_REQUEST_ERROR: payment is not authorized: " + authorizationId);
        Authorization auth = authorizations.get(authorizationId);
        if (amountPaise != auth.amountPaise()) {
            throw new PaymentRejectedException("BAD_REQUEST_ERROR: capture amount must be equal to the authorized amount");
        }
        states.put(authorizationId, PaymentStatus.CAPTURED);
        captures.add(new Capture(authorizationId, auth.userId(), auth.amountPaise()));
        if (Boolean.TRUE.equals(crash)) throw new SimulatedCrash("after the processor captured");
        if (loseNextCaptureResponse != null) {
            RuntimeException failure = loseNextCaptureResponse;
            loseNextCaptureResponse = null;
            throw failure;
        }
    }

    @Override
    public synchronized boolean tryVoid(String authorizationId) {
        if (failNextVoid != null) {
            RuntimeException failure = failNextVoid;
            failNextVoid = null;
            throw failure;
        }
        if (!canVoid) return false;
        if (requireKnown(authorizationId) != PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException("void of " + states.get(authorizationId) + " " + authorizationId);
        }
        states.put(authorizationId, PaymentStatus.RELEASED);
        return true;
    }

    @Override
    public synchronized PaymentStatus status(String authorizationId) {
        if (unreachableStatusLookups > 0) {
            unreachableStatusLookups--;
            throw new RuntimeException("processor unreachable");
        }
        return requireKnown(authorizationId);
    }

    @Override
    public synchronized void refund(String idempotencyKey, String paymentId, long amountPaise) {
        requireValidKey(idempotencyKey);
        if (failNextRefund != null) {
            RuntimeException failure = failNextRefund;
            failNextRefund = null;
            throw failure;
        }
        if (requireKnown(paymentId) != PaymentStatus.CAPTURED) {
            throw new IllegalStateException("refund of uncaptured " + paymentId);
        }
        if (!refundKeys.add(idempotencyKey)) return;
        refunds.add(new Refund(paymentId, amountPaise));
        if (loseNextRefundResponse != null) {
            RuntimeException failure = loseNextRefundResponse;
            loseNextRefundResponse = null;
            throw failure;
        }
    }

    private static void requireValidKey(String key) {
        if (!key.matches("[A-Za-z0-9_-]{10,}")) throw new PaymentRejectedException("BAD_REQUEST_ERROR: invalid idempotency key " + key);
    }

    // Expiry is applied whenever the payment is looked at, so it tracks the test clock without a timer.
    private PaymentStatus requireKnown(String authorizationId) {
        PaymentStatus state = states.get(authorizationId);
        if (state == null) throw new IllegalArgumentException("unknown authorization " + authorizationId);
        Instant expiresAt = authorizations.get(authorizationId).authorizedAt().plus(authorizationExpiry);
        if (state == PaymentStatus.AUTHORIZED && !clock.instant().isBefore(expiresAt)) {
            states.put(authorizationId, PaymentStatus.RELEASED);
            return PaymentStatus.RELEASED;
        }
        return state;
    }

    /** Every call to verifyCheckout, including repeats of the same result. */
    public synchronized int verifyAttempts() {
        return verifyAttempts;
    }

    /** Every call to capture, including ones made to fail. */
    public synchronized int captureAttempts() {
        return captureAttempts;
    }

    public synchronized PaymentStatus state(String authorizationId) {
        return requireKnown(authorizationId);
    }

    public synchronized List<Capture> captures() {
        return List.copyOf(captures);
    }

    public synchronized List<Refund> refunds() {
        return List.copyOf(refunds);
    }
}
