package booking.booking;

import booking.db.PostgresDatabase;

import java.util.Objects;

/** A real database transaction; store calls made inside it on the same thread join it. */
public final class PostgresTransactions implements Transactions {
    private final PostgresDatabase database;

    public PostgresTransactions(PostgresDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void inTransaction(Runnable work) {
        database.inTransaction(work);
    }
}
