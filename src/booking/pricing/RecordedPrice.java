package booking.pricing;

import java.util.List;

/**
 * A price as it was quoted, rebuilt from its breakdown lines (e.g. after loading from a database). Every decorator
 * adds exactly one line for its adjustment, so the sum of the lines is the quoted total.
 */
public record RecordedPrice(List<PriceLine> lines) implements PriceComponent {
    public RecordedPrice {
        lines = List.copyOf(lines);
        if (lines.isEmpty()) throw new IllegalArgumentException("a price has at least one line");
    }

    @Override
    public long totalPaise() {
        long total = 0;
        for (PriceLine line : lines) total = Math.addExact(total, line.amountPaise());
        return total;
    }
}
