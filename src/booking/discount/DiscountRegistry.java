package booking.discount;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In memory: each DiscountCode object counts its own uses with a compare-and-set. */
public final class DiscountRegistry implements DiscountStore {
    private final Map<String, DiscountCode> codes = new ConcurrentHashMap<>();

    @Override
    public void add(DiscountCode code) {
        if (codes.putIfAbsent(code.code(), code) != null) {
            throw new IllegalArgumentException("discount code already exists: " + code.code());
        }
    }

    @Override
    public Optional<DiscountCode> find(String code) {
        return Optional.ofNullable(codes.get(code));
    }

    @Override
    public boolean tryConsume(String code, Instant now) {
        DiscountCode discount = codes.get(code);
        return discount != null && discount.tryConsume(now);
    }

    @Override
    public void releaseUse(String code) {
        DiscountCode discount = codes.get(code);
        if (discount == null) throw new IllegalStateException("no discount code " + code + " to release a use of");
        discount.releaseUse();
    }
}
