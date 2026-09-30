package booking.support;

import booking.payment.PaymentGateway;
import booking.payment.PaymentResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stands in for the external processor, the one process boundary in this system. */
public final class FakePaymentGateway implements PaymentGateway {
    public record Charge(String chargeId, String userId, long amountPaise) {}

    public record Refund(String chargeId, long amountPaise) {}

    private final Map<String, String> chargeIdByKey = new HashMap<>();
    private final List<Charge> charges = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private int chargeAttempts;
    private String declineNextWith;
    private Runnable duringCharge = () -> {};

    public synchronized void declineNext(String reason) {
        declineNextWith = reason;
    }

    /** Runs inside the next charge call, e.g. to let the hold expire while the customer is paying. */
    public synchronized void duringCharge(Runnable action) {
        duringCharge = action;
    }

    @Override
    public synchronized PaymentResult charge(String idempotencyKey, String userId, long amountPaise) {
        chargeAttempts++;
        Runnable action = duringCharge;
        duringCharge = () -> {};
        action.run();
        if (declineNextWith != null) {
            String reason = declineNextWith;
            declineNextWith = null;
            return new PaymentResult.Declined(reason);
        }
        String existing = chargeIdByKey.get(idempotencyKey);
        if (existing != null) return new PaymentResult.Approved(existing);
        String chargeId = "ch_" + (charges.size() + 1);
        chargeIdByKey.put(idempotencyKey, chargeId);
        charges.add(new Charge(chargeId, userId, amountPaise));
        return new PaymentResult.Approved(chargeId);
    }

    @Override
    public synchronized void refund(String chargeId, long amountPaise) {
        refunds.add(new Refund(chargeId, amountPaise));
    }

    /** Every call to charge, including idempotent replays that returned an existing charge. */
    public synchronized int chargeAttempts() {
        return chargeAttempts;
    }

    public synchronized List<Charge> charges() {
        return List.copyOf(charges);
    }

    public synchronized List<Refund> refunds() {
        return List.copyOf(refunds);
    }
}
