package booking.discount;

import java.time.Instant;
import java.util.Optional;

/**
 * Discount codes and their usage counts. {@link #tryConsume} is the authoritative check: it must never let more
 * bookings use a code than its limit, however many confirms race for the last use.
 */
public interface DiscountStore {
    void add(DiscountCode code);

    /** A snapshot for pricing and the cheap hold-time check; its usage count may be stale by the time it's read. */
    Optional<DiscountCode> find(String code);

    boolean tryConsume(String code, Instant now);

    /** Gives back a use taken by {@link #tryConsume} when the booking it was taken for did not go through. */
    void releaseUse(String code);
}
