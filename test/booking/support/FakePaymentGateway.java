package booking.support;

import booking.payment.PaymentGateway;
import booking.payment.PaymentResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stands in for the external processor, the one process boundary in this system. */
public final class FakePaymentGateway implements PaymentGateway {
    public enum AuthorizationState { AUTHORIZED, CAPTURED, VOIDED }

    public record Capture(String authorizationId, String userId, long amountPaise) {}

    public record Refund(String paymentId, long amountPaise) {}

    private record Authorization(String userId, long amountPaise) {}

    private final Map<String, String> authorizationIdByKey = new HashMap<>();
    private final Map<String, Authorization> authorizations = new HashMap<>();
    private final Map<String, AuthorizationState> states = new HashMap<>();
    private final List<Capture> captures = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private int authorizeAttempts;
    private String declineNextWith;
    private RuntimeException failNextCapture;
    private RuntimeException failNextVoid;
    private Runnable duringAuthorize = () -> {};

    public synchronized void declineNext(String reason) {
        declineNextWith = reason;
    }

    public synchronized void failNextCapture(RuntimeException failure) {
        failNextCapture = failure;
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
        states.put(authorizationId, AuthorizationState.AUTHORIZED);
        return new PaymentResult.Approved(authorizationId);
    }

    @Override
    public synchronized void capture(String authorizationId) {
        if (failNextCapture != null) {
            RuntimeException failure = failNextCapture;
            failNextCapture = null;
            throw failure;
        }
        AuthorizationState state = requireKnown(authorizationId);
        if (state == AuthorizationState.CAPTURED) return;
        if (state == AuthorizationState.VOIDED) throw new IllegalStateException("capture of voided " + authorizationId);
        Authorization auth = authorizations.get(authorizationId);
        states.put(authorizationId, AuthorizationState.CAPTURED);
        captures.add(new Capture(authorizationId, auth.userId(), auth.amountPaise()));
    }

    @Override
    public synchronized void voidAuthorization(String authorizationId) {
        if (failNextVoid != null) {
            RuntimeException failure = failNextVoid;
            failNextVoid = null;
            throw failure;
        }
        if (requireKnown(authorizationId) != AuthorizationState.AUTHORIZED) {
            throw new IllegalStateException("void of " + states.get(authorizationId) + " " + authorizationId);
        }
        states.put(authorizationId, AuthorizationState.VOIDED);
    }

    @Override
    public synchronized void refund(String paymentId, long amountPaise) {
        if (requireKnown(paymentId) != AuthorizationState.CAPTURED) {
            throw new IllegalStateException("refund of uncaptured " + paymentId);
        }
        refunds.add(new Refund(paymentId, amountPaise));
    }

    private AuthorizationState requireKnown(String authorizationId) {
        AuthorizationState state = states.get(authorizationId);
        if (state == null) throw new IllegalArgumentException("unknown authorization " + authorizationId);
        return state;
    }

    /** Every call to authorize, including idempotent replays that returned an existing authorization. */
    public synchronized int authorizeAttempts() {
        return authorizeAttempts;
    }

    public synchronized AuthorizationState state(String authorizationId) {
        return requireKnown(authorizationId);
    }

    public synchronized List<Capture> captures() {
        return List.copyOf(captures);
    }

    public synchronized List<Refund> refunds() {
        return List.copyOf(refunds);
    }
}
