package booking.pricing;

import booking.model.Money;

final class WeekendSurcharge extends PriceDecorator {
    WeekendSurcharge(PriceComponent inner, int bps) {
        long surcharge = Money.basisPoints(inner.totalPaise(), bps);
        super(inner, new PriceLine("Weekend surcharge", surcharge));
    }
}
