package booking.payment;

import java.util.Objects;

public sealed interface PaymentResult {
    record Approved(String chargeId) implements PaymentResult {
        public Approved {
            Objects.requireNonNull(chargeId, "chargeId");
        }
    }

    record Declined(String reason) implements PaymentResult {
        public Declined {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
