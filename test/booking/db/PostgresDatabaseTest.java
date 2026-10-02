package booking.db;

import booking.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class PostgresDatabaseTest {
    private final PostgresTestDatabase test = PostgresTestDatabase.empty();
    private final PostgresDatabase db = test.database();

    PostgresDatabaseTest() {
        exec("CREATE TABLE scratch (n integer)");
    }

    @AfterEach
    void drop() {
        test.close();
    }

    private void exec(String sql) {
        db.withConnection(c -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    private int rows() {
        return db.withConnection(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM scratch")) {
                r.next();
                return r.getInt(1);
            }
        });
    }

    @Test
    void commitsWhenTheWorkReturns() {
        db.inTransaction(() -> exec("INSERT INTO scratch VALUES (1)"));
        assertEquals(1, rows());
    }

    @Test
    void rollsBackEverythingWhenTheWorkThrows() {
        RuntimeException boom = new RuntimeException("boom");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> db.inTransaction(() -> {
            exec("INSERT INTO scratch VALUES (1)");
            exec("INSERT INTO scratch VALUES (2)");
            throw boom;
        }));
        assertEquals(boom, thrown);
        assertEquals(0, rows());
    }

    @Test
    void callsInsideATransactionJoinItAndSeeItsUncommittedRows() {
        db.inTransaction(() -> {
            exec("INSERT INTO scratch VALUES (1)");
            assertEquals(1, rows());
            db.inTransaction(() -> exec("INSERT INTO scratch VALUES (2)"));
        });
        assertEquals(2, rows());
    }

    @Test
    void nestedTransactionFailureRollsBackTheWholeOuterOne() {
        assertThrows(RuntimeException.class, () -> db.inTransaction(() -> {
            exec("INSERT INTO scratch VALUES (1)");
            db.inTransaction(() -> {
                throw new RuntimeException("inner");
            });
        }));
        assertEquals(0, rows());
    }

    @Test
    void outsideATransactionEachCallCommitsOnItsOwn() {
        exec("INSERT INTO scratch VALUES (1)");
        assertThrows(DatabaseException.class, () -> exec("INSERT INTO no_such_table VALUES (1)"));
        assertEquals(1, rows());
    }

    @Test
    void advisoryLockMakesASecondHolderWaitForTheFirst() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondEnteredEarly = new AtomicBoolean();
        Thread first = Thread.ofPlatform().start(() -> db.withAdvisoryLock("booking-1", () -> {
            firstInside.countDown();
            await(releaseFirst);
            return null;
        }));
        assertTrue(firstInside.await(5, TimeUnit.SECONDS));
        CountDownLatch secondInside = new CountDownLatch(1);
        Thread second = Thread.ofPlatform().start(() -> db.withAdvisoryLock("booking-1", () -> {
            if (releaseFirst.getCount() > 0) secondEnteredEarly.set(true);
            secondInside.countDown();
            return null;
        }));

        assertFalse(secondInside.await(300, TimeUnit.MILLISECONDS), "second holder got in while the first held the lock");
        releaseFirst.countDown();
        assertTrue(secondInside.await(5, TimeUnit.SECONDS));
        first.join();
        second.join();
        assertFalse(secondEnteredEarly.get());
    }

    @Test
    void advisoryLocksOnDifferentKeysDoNotBlockEachOther() throws Exception {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstInside = new CountDownLatch(1);
        Thread first = Thread.ofPlatform().start(() -> db.withAdvisoryLock("booking-1", () -> {
            firstInside.countDown();
            await(releaseFirst);
            return null;
        }));
        assertTrue(firstInside.await(5, TimeUnit.SECONDS));

        assertEquals("ran", db.withAdvisoryLock("booking-2", () -> "ran"));
        releaseFirst.countDown();
        first.join();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
