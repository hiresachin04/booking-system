package booking.pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

abstract class PriceDecorator implements PriceComponent {
    private final PriceComponent inner;
    private final PriceLine adjustment;

    PriceDecorator(PriceComponent inner, PriceLine adjustment) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.adjustment = Objects.requireNonNull(adjustment, "adjustment");
    }

    @Override
    public final long totalPaise() {
        return Math.addExact(inner.totalPaise(), adjustment.amountPaise());
    }

    @Override
    public final List<PriceLine> lines() {
        List<PriceLine> all = new ArrayList<>(inner.lines());
        all.add(adjustment);
        return List.copyOf(all);
    }
}
