package booking.pricing;

import booking.model.Money;

final class PercentDiscount extends PriceDecorator {
    PercentDiscount(PriceComponent inner, Discount.Percent discount) {
        long off = Math.min(Money.basisPoints(inner.totalPaise(), discount.bps()), discount.capPaise());
        super(inner, new PriceLine("Discount", -off));
    }
}
