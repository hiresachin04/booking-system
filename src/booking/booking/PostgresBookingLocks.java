package booking.booking;

import booking.db.PostgresDatabase;

import java.util.Objects;
import java.util.function.Supplier;

/** A session advisory lock per booking id, so changes to one booking are serialised across every instance. */
public final class PostgresBookingLocks implements BookingLocks {
    private final PostgresDatabase database;

    public PostgresBookingLocks(PostgresDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public <T> T withLock(String bookingId, Supplier<T> action) {
        return database.withAdvisoryLock("booking:" + bookingId, action);
    }
}
