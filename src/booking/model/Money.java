package booking.model;

public final class Money {
    private static final long BPS_PER_WHOLE = 10_000;

    private Money() {}

    /** {@code bps} basis points of {@code paise}, rounded half-up. */
    public static long basisPoints(long paise, int bps) {
        if (paise < 0) throw new IllegalArgumentException("amount must not be negative: " + paise);
        if (bps < 0) throw new IllegalArgumentException("basis points must not be negative: " + bps);
        return Math.addExact(Math.multiplyExact(paise, bps), BPS_PER_WHOLE / 2) / BPS_PER_WHOLE;
    }
}
