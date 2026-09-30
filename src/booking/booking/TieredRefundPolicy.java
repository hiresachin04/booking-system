package booking.booking;

import booking.model.Money;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Refunds the percentage of the highest tier whose minimum notice is met; nothing if none is. */
public final class TieredRefundPolicy implements RefundPolicy {
    public record Tier(Duration minNotice, int refundBps) {
        public Tier {
            Objects.requireNonNull(minNotice, "minNotice");
            if (minNotice.isNegative()) throw new IllegalArgumentException("minNotice must not be negative");
            if (refundBps < 0 || refundBps > 10_000) throw new IllegalArgumentException("refundBps out of range: " + refundBps);
        }
    }

    private final List<Tier> tiersLongestNoticeFirst;

    public TieredRefundPolicy(List<Tier> tiers) {
        List<Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparing(Tier::minNotice).reversed());
        this.tiersLongestNoticeFirst = List.copyOf(sorted);
    }

    @Override
    public long refundPaise(long paidPaise, Duration noticeBeforeShow) {
        for (Tier tier : tiersLongestNoticeFirst) {
            if (noticeBeforeShow.compareTo(tier.minNotice()) >= 0) return Money.basisPoints(paidPaise, tier.refundBps());
        }
        return 0;
    }
}
