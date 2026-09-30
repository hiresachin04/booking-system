package booking.payment;

import java.util.Objects;

public sealed interface PaymentResult {
    record Approved(String authorizationId) implements PaymentResult {
        public Approved {
            Objects.requireNonNull(authorizationId, "authorizationId");
        }
    }

    record Declined(String reason) implements PaymentResult {
        public Declined {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
