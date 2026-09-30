package booking.support;

import booking.payment.PaymentGateway;
import booking.payment.PaymentRejectedException;
import booking.payment.PaymentResult;
import booking.payment.PaymentStatus;

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

    private record Authorization(String userId, long amountPaise, Instant authorizedAt) {}

    private static final Duration STRIPE_AUTHORIZATION_VALIDITY = Duration.ofDays(7);

    private final Clock clock;
    private final Duration authorizationExpiry;
    private final boolean canVoid;
    private final boolean rejectsSecondCapture;

    private final Map<String, String> authorizationIdByKey = new HashMap<>();
    private final Map<String, Authorization> authorizations = new HashMap<>();
    private final Map<String, PaymentStatus> states = new HashMap<>();
    private final List<Capture> captures = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final Set<String> refundKeys = new HashSet<>();
    private int authorizeAttempts;
    private int captureAttempts;
    private int unavailableCaptures;
    private String declineNextWith;
    private RuntimeException failNextCapture;
    private RuntimeException loseNextCaptureResponse;
    private int unreachableStatusLookups;
    private RuntimeException failNextVoid;
    private RuntimeException failNextRefund;
    private RuntimeException loseNextRefundResponse;
    private Runnable duringAuthorize = () -> {};

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

    public synchronized void declineNext(String reason) {
        declineNextWith = reason;
    }

    public synchronized void failNextCapture(RuntimeException failure) {
        failNextCapture = failure;
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

    /** Runs inside the next authorize call, e.g. to let the hold expire while the customer is paying. */
    public synchronized void duringAuthorize(Runnable action) {
        duringAuthorize = action;
    }

    @Override
    public synchronized PaymentResult authorize(String idempotencyKey, String userId, long amountPaise) {
        authorizeAttempts++;
        Runnable action = duringAuthorize;
        duringAuthorize = () -> {};
        action.run();
        if (declineNextWith != null) {
            String reason = declineNextWith;
            declineNextWith = null;
            return new PaymentResult.Declined(reason);
        }
        String existing = authorizationIdByKey.get(idempotencyKey);
        if (existing != null) return new PaymentResult.Approved(existing);
        String authorizationId = "auth_" + (authorizations.size() + 1);
        authorizationIdByKey.put(idempotencyKey, authorizationId);
        authorizations.put(authorizationId, new Authorization(userId, amountPaise, clock.instant()));
        states.put(authorizationId, PaymentStatus.AUTHORIZED);
        return new PaymentResult.Approved(authorizationId);
    }

    @Override
    public synchronized void capture(String authorizationId) {
        captureAttempts++;
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
        states.put(authorizationId, PaymentStatus.CAPTURED);
        captures.add(new Capture(authorizationId, auth.userId(), auth.amountPaise()));
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

    /** Every call to authorize, including idempotent replays that returned an existing authorization. */
    public synchronized int authorizeAttempts() {
        return authorizeAttempts;
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
