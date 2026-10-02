package booking.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;

import java.lang.System.Logger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Connections, transactions and locks for the PostgreSQL stores, from two HikariCP pools. A store call made inside
 * {@link #inTransaction} on the same thread joins that transaction; outside one, each call borrows its own
 * auto-committed connection.
 * <p>
 * Advisory locks get their own pool because a lock holds its connection for as long as the locked work runs, and
 * that work needs a second connection for its queries. From one shared pool, enough concurrent lock holders would
 * own every connection while each waits for another: a deadlock until the pool timeout. Nothing holds a work
 * connection while waiting for another, so the work pool can't deadlock, and an exhausted lock pool just queues.
 */
public final class PostgresDatabase implements AutoCloseable {
    private static final Logger LOG = System.getLogger(PostgresDatabase.class.getName());
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_POOL_SIZE = 10;

    /** The password comes from the environment, never from the committed properties file. */
    public record Settings(String url, String user, String password, int poolSize, int lockPoolSize) {
        public Settings {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(password, "password");
            if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("not a PostgreSQL JDBC URL: " + url);
            if (poolSize < 1) throw new IllegalArgumentException("poolSize must be at least 1: " + poolSize);
            if (lockPoolSize < 1) throw new IllegalArgumentException("lockPoolSize must be at least 1: " + lockPoolSize);
        }

        public Settings(String url, String user, String password) {
            this(url, user, password, DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE);
        }

        /**
         * {@code postgres.url}, {@code postgres.user} and the optional {@code postgres.pool.size} and
         * {@code postgres.pool.lockSize} from the properties; the password from {@code POSTGRES_PASSWORD}, which may be
         * absent for local trust authentication (Postgres.app's default).
         */
        public static Settings from(Properties properties, Map<String, String> env) {
            return new Settings(require(properties, "postgres.url"), require(properties, "postgres.user"),
                    env.getOrDefault("POSTGRES_PASSWORD", ""),
                    size(properties, "postgres.pool.size"), size(properties, "postgres.pool.lockSize"));
        }

        private static String require(Properties properties, String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalStateException(key + " is not set");
            return value.strip();
        }

        private static int size(Properties properties, String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) return DEFAULT_POOL_SIZE;
            try {
                return Integer.parseInt(value.strip());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(key + " must be a whole number: " + value.strip(), e);
            }
        }

        public Settings withUrl(String otherUrl) {
            return new Settings(otherUrl, user, password, poolSize, lockPoolSize);
        }

        public Settings withPoolSizes(int otherPoolSize, int otherLockPoolSize) {
            return new Settings(url, user, password, otherPoolSize, otherLockPoolSize);
        }

        @Override
        public String toString() {
            return "Settings[url=" + url + ", user=" + user + ", password=" + (password.isEmpty() ? "(none)" : "***")
                    + ", poolSize=" + poolSize + ", lockPoolSize=" + lockPoolSize + "]";
        }
    }

    private final Settings settings;
    private final HikariDataSource work;
    private final HikariDataSource locks;
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();

    /** Fails here, not on first use, if the database can't be reached with these settings. */
    public PostgresDatabase(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.work = pool(settings, "work", settings.poolSize());
        try {
            this.locks = pool(settings, "locks", settings.lockPoolSize());
        } catch (RuntimeException e) {
            work.close();
            throw e;
        }
    }

    private static HikariDataSource pool(Settings settings, String name, int size) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("booking-" + name);
        config.setJdbcUrl(settings.url());
        config.setUsername(settings.user());
        if (!settings.password().isEmpty()) config.setPassword(settings.password());
        config.setMaximumPoolSize(size);
        // Connections are opened on demand rather than all at start, so idle instances and tests hold none.
        config.setMinimumIdle(0);
        config.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        config.addDataSourceProperty("ApplicationName", "booking-system/" + name);
        try {
            return new HikariDataSource(config);
        } catch (HikariPool.PoolInitializationException e) {
            throw new DatabaseException("could not connect to " + settings.url() + " as " + settings.user() + ": " + e.getMessage(), e);
        }
    }

    public Settings settings() {
        return settings;
    }

    /** Runs {@code work} in this thread's transaction if there is one, otherwise on its own auto-committed connection. */
    public <T> T withConnection(SqlWork<T> sqlWork) {
        Connection current = transaction.get();
        try {
            if (current != null) return sqlWork.apply(current);
            try (Connection connection = work.getConnection()) {
                return sqlWork.apply(connection);
            }
        } catch (SQLException e) {
            throw new DatabaseException("database call failed: " + e.getMessage(), e);
        }
    }

    /** All-or-nothing: commits if {@code work} returns, rolls back if it throws. A nested call joins the outer one. */
    public <T> T inTransaction(SqlWork<T> sqlWork) {
        if (transaction.get() != null) return withConnection(sqlWork);
        try (Connection connection = work.getConnection()) {
            connection.setAutoCommit(false);
            transaction.set(connection);
            try {
                T result = sqlWork.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new DatabaseException("transaction failed: " + e.getMessage(), e);
            } finally {
                transaction.remove();
            }
        } catch (SQLException e) {
            throw new DatabaseException("could not run transaction: " + e.getMessage(), e);
        }
    }

    public void inTransaction(Runnable runnable) {
        inTransaction(connection -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Holds a session advisory lock on {@code key} while {@code action} runs, so the action is serialised across every
     * instance using this database. The lock lives on a connection from the lock pool; {@code action} may use others.
     */
    public <T> T withAdvisoryLock(String key, Supplier<T> action) {
        try (Connection connection = locks.getConnection()) {
            lock(connection, key);
            T result;
            try {
                result = action.get();
            } catch (RuntimeException | Error e) {
                releaseOrEvict(connection, key, e);
                throw e;
            }
            releaseOrEvict(connection, key, null);
            return result;
        } catch (SQLException e) {
            throw new DatabaseException("advisory lock on " + key + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * A lock left held on a pooled connection would block that key for as long as the connection stays in the pool,
     * so a connection whose unlock didn't succeed is evicted: closing it for real ends the session and with it the
     * lock. That handles the failure, so it is reported (on the action's own exception if there is one) not thrown.
     */
    private void releaseOrEvict(Connection connection, String key, Throwable primary) {
        SQLException cause = null;
        boolean released = false;
        try {
            released = unlock(connection, key);
        } catch (SQLException e) {
            cause = e;
        }
        if (released) return;
        locks.evictConnection(connection);
        DatabaseException failure = new DatabaseException("could not release advisory lock on " + key + "; its connection was evicted", cause);
        if (primary != null) primary.addSuppressed(failure);
        else LOG.log(Logger.Level.WARNING, failure.getMessage(), failure);
    }

    private static void lock(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
            statement.setString(1, key);
            statement.execute();
        }
    }

    /** pg_advisory_unlock reports whether this session actually held the lock. */
    private static boolean unlock(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }

    /** Connections currently open in the work pool, busy or idle; for tests and operators. */
    int openWorkConnections() {
        return work.getHikariPoolMXBean().getTotalConnections();
    }

    @Override
    public void close() {
        try {
            work.close();
        } finally {
            locks.close();
        }
    }

    private static void rollback(Connection connection, Exception primary) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
    }
}
