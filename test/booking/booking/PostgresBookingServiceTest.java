package booking.booking;

import booking.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

/** BookingService on PostgreSQL storage: every booking flow, end to end through the database. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class PostgresBookingServiceTest extends BookingServiceContract {
    private final PostgresTestDatabase test = PostgresTestDatabase.create();

    @AfterEach
    void drop() {
        test.close();
    }

    @Override
    protected Storage newStorage() {
        return new PostgresStorage(test.database(), clock);
    }

    // The booking lock is a PostgreSQL advisory lock: a waiter sits in a socket read (RUNNABLE to Java) and shows up
    // in pg_locks as an advisory lock not yet granted.
    @Override
    protected void awaitWaitingForBookingLock(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!waitingForAdvisoryLock()) {
            if (System.nanoTime() > deadline) throw new AssertionError("no session ever waited for an advisory lock; thread is " + thread.getState());
            Thread.onSpinWait();
        }
    }

    private boolean waitingForAdvisoryLock() {
        return test.database().withConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")) {
                row.next();
                return row.getInt(1) > 0;
            }
        });
    }
}
