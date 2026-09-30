package booking.pricing;

public sealed interface Discount {
    record Percent(int bps, long capPaise) implements Discount {
        public Percent {
            if (bps <= 0 || bps > 10_000) throw new IllegalArgumentException("percent discount bps out of range: " + bps);
            if (capPaise <= 0) throw new IllegalArgumentException("cap must be positive: " + capPaise);
        }

        public static Percent uncapped(int bps) {
            return new Percent(bps, Long.MAX_VALUE);
        }
    }

    record Flat(long paise) implements Discount {
        public Flat {
            if (paise <= 0) throw new IllegalArgumentException("flat discount must be positive: " + paise);
        }
    }
}
