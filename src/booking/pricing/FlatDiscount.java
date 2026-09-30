package booking.pricing;

final class FlatDiscount extends PriceDecorator {
    FlatDiscount(PriceComponent inner, Discount.Flat discount) {
        long off = Math.min(discount.paise(), inner.totalPaise());
        super(inner, new PriceLine("Discount", -off));
    }
}
