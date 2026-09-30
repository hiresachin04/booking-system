package booking.booking;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TieredRefundPolicyTest {
    // Tiers deliberately given shortest-first to check the policy doesn't depend on input order.
    private final TieredRefundPolicy policy = new TieredRefundPolicy(List.of(
            new TieredRefundPolicy.Tier(Duration.ofHours(4), 5_000),
            new TieredRefundPolicy.Tier(Duration.ofHours(24), 10_000)));

    @Test
    void refundsInFullWithExactlyTwentyFourHoursNotice() {
        assertEquals(20_000, policy.refundPaise(20_000, Duration.ofHours(24)));
    }

    @Test
    void dropsToHalfOneSecondShortOfTwentyFourHours() {
        assertEquals(10_000, policy.refundPaise(20_000, Duration.ofHours(24).minusSeconds(1)));
    }

    @Test
    void refundsNothingBelowTheLowestTier() {
        assertEquals(0, policy.refundPaise(20_000, Duration.ofHours(4).minusSeconds(1)));
    }

    @Test
    void roundsPartialRefundHalfUp() {
        assertEquals(2, policy.refundPaise(3, Duration.ofHours(5)));
    }

    @Test
    void rejectsTierRefundingMoreThanWasPaid() {
        assertThrows(IllegalArgumentException.class, () -> new TieredRefundPolicy.Tier(Duration.ZERO, 10_001));
    }
}
