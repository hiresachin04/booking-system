package booking.discount;

import booking.pricing.Discount;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Usage is consumed at confirm, not at hold, so abandoned holds don't burn uses. */
public final class DiscountCode {
    private final String code;
    private final Discount discount;
    private final Instant validFrom;
    private final Instant validUntil;
    private final long minOrderPaise;
    private final int usageLimit;
    private final AtomicInteger used = new AtomicInteger();

    public DiscountCode(String code, Discount discount, Instant validFrom, Instant validUntil, long minOrderPaise, int usageLimit) {
        this(code, discount, validFrom, validUntil, minOrderPaise, usageLimit, 0);
    }

    /** For stores that keep the count elsewhere and rebuild a snapshot with the uses taken so far. */
    public DiscountCode(String code, Discount discount, Instant validFrom, Instant validUntil, long minOrderPaise, int usageLimit,
                        int used) {
        if (used < 0 || used > usageLimit) throw new IllegalArgumentException("used must be within 0.." + usageLimit + ": " + used);
        this.used.set(used);
        this.code = Objects.requireNonNull(code, "code");
        this.discount = Objects.requireNonNull(discount, "discount");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (!validUntil.isAfter(validFrom)) throw new IllegalArgumentException("validUntil must be after validFrom");
        if (minOrderPaise < 0) throw new IllegalArgumentException("minOrderPaise must not be negative");
        if (usageLimit <= 0) throw new IllegalArgumentException("usageLimit must be positive");
        this.minOrderPaise = minOrderPaise;
        this.usageLimit = usageLimit;
    }

    public String code() {
        return code;
    }

    public Discount discount() {
        return discount;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Instant validUntil() {
        return validUntil;
    }

    public long minOrderPaise() {
        return minOrderPaise;
    }

    public int usageLimit() {
        return usageLimit;
    }

    public int used() {
        return used.get();
    }

    /** Cheap pre-check at hold time; {@link #tryConsume} is the authoritative check. */
    public void checkApplicable(long orderPaise, Instant now) {
        if (!inWindow(now)) throw new DiscountRejectedException(code, "not valid at " + now);
        if (orderPaise < minOrderPaise) throw new DiscountRejectedException(code, "order below minimum of " + minOrderPaise + " paise");
        if (used.get() >= usageLimit) throw new DiscountRejectedException(code, "usage limit reached");
    }

    public boolean tryConsume(Instant now) {
        if (!inWindow(now)) return false;
        while (true) {
            int current = used.get();
            if (current >= usageLimit) return false;
            if (used.compareAndSet(current, current + 1)) return true;
        }
    }

    /** Gives back a use taken by {@link #tryConsume} when the booking it was taken for did not go through. */
    public void releaseUse() {
        used.updateAndGet(n -> {
            if (n == 0) throw new IllegalStateException("discount code " + code + " has no uses to release");
            return n - 1;
        });
    }

    private boolean inWindow(Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }
}
