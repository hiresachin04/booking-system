package booking.support;

import booking.payment.PaymentGateway;
import booking.payment.PaymentResult;
import booking.payment.PaymentStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stands in for the external processor, the one process boundary in this system. */
public final class FakePaymentGateway implements PaymentGateway {
    public record Capture(String authorizationId, String userId, long amountPaise) {}

    public record Refund(String paymentId, long amountPaise) {}

    private record Authorization(String userId, long amountPaise) {}

    private final Map<String, String> authorizationIdByKey = new HashMap<>();
    private final Map<String, Authorization> authorizations = new HashMap<>();
    private final Map<String, PaymentStatus> states = new HashMap<>();
    private final List<Capture> captures = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final Set<String> refundKeys = new HashSet<>();
    private int authorizeAttempts;
    private String declineNextWith;
    private RuntimeException failNextCapture;
    private RuntimeException loseNextCaptureResponse;
    private int unreachableStatusLookups;
    private RuntimeException failNextVoid;
    private RuntimeException failNextRefund;
    private RuntimeException loseNextRefundResponse;
    private Runnable duringAuthorize = () -> {};

    public synchronized void declineNext(String reason) {
        declineNextWith = reason;
    }

    public synchronized void failNextCapture(RuntimeException failure) {
        failNextCapture = failure;
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
        authorizations.put(authorizationId, new Authorization(userId, amountPaise));
        states.put(authorizationId, PaymentStatus.AUTHORIZED);
        return new PaymentResult.Approved(authorizationId);
    }

    @Override
    public synchronized void capture(String authorizationId) {
        if (failNextCapture != null) {
            RuntimeException failure = failNextCapture;
            failNextCapture = null;
            throw failure;
        }
        PaymentStatus state = requireKnown(authorizationId);
        if (state == PaymentStatus.CAPTURED) return;
        if (state == PaymentStatus.VOIDED) throw new IllegalStateException("capture of voided " + authorizationId);
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
    public synchronized void voidAuthorization(String authorizationId) {
        if (failNextVoid != null) {
            RuntimeException failure = failNextVoid;
            failNextVoid = null;
            throw failure;
        }
        if (requireKnown(authorizationId) != PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException("void of " + states.get(authorizationId) + " " + authorizationId);
        }
        states.put(authorizationId, PaymentStatus.VOIDED);
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

    private PaymentStatus requireKnown(String authorizationId) {
        PaymentStatus state = states.get(authorizationId);
        if (state == null) throw new IllegalArgumentException("unknown authorization " + authorizationId);
        return state;
    }

    /** Every call to authorize, including idempotent replays that returned an existing authorization. */
    public synchronized int authorizeAttempts() {
        return authorizeAttempts;
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
