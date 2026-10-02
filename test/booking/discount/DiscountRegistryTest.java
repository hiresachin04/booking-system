package booking.discount;

/** The in-memory store against the DiscountStore contract. */
class DiscountRegistryTest extends DiscountStoreContract {
    @Override
    protected DiscountStore newStore() {
        return new DiscountRegistry();
    }
}
