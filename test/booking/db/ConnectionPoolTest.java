package booking.db;

import booking.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import booking.support.Race;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class ConnectionPoolTest {
    private static int selectOne(PostgresDatabase db) {
        return db.withConnection(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT 1")) {
                r.next();
                return r.getInt(1);
            }
        });
    }

    @Test
    void reusesPooledConnectionsInsteadOfOpeningOnePerCall() {
        try (PostgresTestDatabase test = PostgresTestDatabase.emptyWithPools(5, 1)) {
            for (int i = 0; i < 100; i++) selectOne(test.database());

            assertTrue(test.database().openWorkConnections() <= 1, "open work connections: " + test.database().openWorkConnections());
        }
    }

    @Test
    void neverOpensMoreThanThePoolSizeWhenMoreCallersWantOneAtOnce() {
        try (PostgresTestDatabase test = PostgresTestDatabase.emptyWithPools(3, 1)) {
            PostgresDatabase db = test.database();
            AtomicInteger peak = new AtomicInteger();
            // Each caller keeps its connection for a while, so twelve of them genuinely compete for three.
            Race.run(12, i -> db.withConnection(c -> {
                peak.accumulateAndGet(db.openWorkConnections(), Math::max);
                sleep(100);
                return true;
            }));

            assertEquals(3, peak.get(), "peak open work connections");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void lockHoldersCanAlwaysRunTheirQueriesEvenWithAOneConnectionWorkPool() throws Exception {
        try (PostgresTestDatabase test = PostgresTestDatabase.emptyWithPools(1, 2)) {
            PostgresDatabase db = test.database();
            CountDownLatch bothLocked = new CountDownLatch(2);
            Queue<Object> outcomes = new ConcurrentLinkedQueue<>();
            List<Thread> holders = List.of("booking-a", "booking-b").stream().map(key -> Thread.ofPlatform().start(() -> {
                try {
                    outcomes.add(db.withAdvisoryLock(key, () -> {
                        bothLocked.countDown();
                        await(bothLocked);
                        // Both locks are held now; each holder still needs a work connection for its query.
                        return selectOne(db);
                    }));
                } catch (RuntimeException e) {
                    outcomes.add(e);
                }
            })).toList();

            for (Thread holder : holders) holder.join(TimeUnit.SECONDS.toMillis(20));
            assertEquals(List.of(1, 1), List.copyOf(outcomes), "each lock holder should have run its query");
        }
    }

    @Test
    void closedDatabaseRefusesWork() {
        PostgresTestDatabase test = PostgresTestDatabase.emptyWithPools(1, 1);
        PostgresDatabase db = test.database();
        test.close();

        assertThrows(DatabaseException.class, () -> selectOne(db));
    }

    @Test
    void unreachableDatabaseFailsAtStartupNotOnFirstUse() {
        PostgresDatabase.Settings unreachable = new PostgresDatabase.Settings("jdbc:postgresql://127.0.0.1:1/booking", "booking", "");

        DatabaseException e = assertThrows(DatabaseException.class, () -> new PostgresDatabase(unreachable));
        assertTrue(e.getMessage().startsWith("could not connect to jdbc:postgresql://127.0.0.1:1/booking"), e.getMessage());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("the other holder never took its lock");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
