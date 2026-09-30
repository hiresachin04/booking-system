package booking.pricing;

import java.util.Objects;

public record PriceLine(String label, long amountPaise) {
    public PriceLine {
        Objects.requireNonNull(label, "label");
    }
}
