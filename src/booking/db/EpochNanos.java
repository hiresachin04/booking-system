package booking.db;

import java.time.Instant;

/** Instants as bigint nanoseconds since the epoch: exact, unlike timestamptz's microseconds (see V1__schema.sql). */
public final class EpochNanos {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private EpochNanos() {}

    public static long of(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
    }

    public static Instant toInstant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND), Math.floorMod(nanos, NANOS_PER_SECOND));
    }
}
