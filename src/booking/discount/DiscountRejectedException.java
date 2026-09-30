package booking.discount;

public final class DiscountRejectedException extends RuntimeException {
    public DiscountRejectedException(String code, String reason) {
        super("discount code " + code + " rejected: " + reason);
    }
}
