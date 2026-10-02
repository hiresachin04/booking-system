package booking.booking;

import booking.db.PostgresDatabase;
import booking.discount.PostgresDiscountRegistry;
import booking.notify.BookingListener;
import booking.notify.PostgresNotificationOutbox;
import booking.retry.RetryPolicy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Everything in PostgreSQL: survives restarts, and stays correct with several instances on one database (row locks
 * for seats, conditional updates for discounts, advisory locks for bookings, SKIP LOCKED for the outbox).
 * The schema must already be migrated; StorageFactory does that at startup.
 */
public final class PostgresStorage implements Storage {
    private final PostgresDatabase database;
    private final PostgresShowRepository shows;
    private final PostgresBookingRepository bookings;
    private final PostgresDiscountRegistry discounts;
    private final PostgresBookingLocks locks;
    private final PostgresTransactions transactions;

    public PostgresStorage(PostgresDatabase database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        Objects.requireNonNull(clock, "clock");
        this.shows = new PostgresShowRepository(database, clock);
        this.bookings = new PostgresBookingRepository(database);
        this.discounts = new PostgresDiscountRegistry(database);
        this.locks = new PostgresBookingLocks(database);
        this.transactions = new PostgresTransactions(database);
    }

    @Override
    public PostgresShowRepository shows() {
        return shows;
    }

    @Override
    public PostgresBookingRepository bookings() {
        return bookings;
    }

    @Override
    public PostgresDiscountRegistry discounts() {
        return discounts;
    }

    @Override
    public PostgresBookingLocks locks() {
        return locks;
    }

    @Override
    public PostgresTransactions transactions() {
        return transactions;
    }

    @Override
    public PostgresNotificationOutbox outbox(List<BookingListener> listeners, RetryPolicy retry, Duration sentRetention, Clock outboxClock) {
        return new PostgresNotificationOutbox(database, bookings, listeners, retry, sentRetention, outboxClock);
    }
}
