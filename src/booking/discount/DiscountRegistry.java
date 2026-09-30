package booking.discount;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DiscountRegistry {
    private final Map<String, DiscountCode> codes = new ConcurrentHashMap<>();

    public void add(DiscountCode code) {
        if (codes.putIfAbsent(code.code(), code) != null) {
            throw new IllegalArgumentException("discount code already exists: " + code.code());
        }
    }

    public Optional<DiscountCode> find(String code) {
        return Optional.ofNullable(codes.get(code));
    }
}
