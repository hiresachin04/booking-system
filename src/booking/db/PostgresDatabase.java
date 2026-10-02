package booking.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Connections, transactions and locks for the PostgreSQL stores. A store call made inside {@link #inTransaction}
 * on the same thread joins that transaction; outside one, each call gets its own auto-committed connection. There
 * is no pool: a connection per unit of work is plenty at this scale and keeps the driver the only dependency.
 */
public final class PostgresDatabase {
    /** The password comes from the environment, never from the committed properties file. */
    public record Settings(String url, String user, String password) {
        public Settings {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(password, "password");
            if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("not a PostgreSQL JDBC URL: " + url);
        }

        /**
         * {@code postgres.url} and {@code postgres.user} from the properties; the password from {@code POSTGRES_PASSWORD},
         * which may be absent for local trust authentication (Postgres.app's default).
         */
        public static Settings from(Properties properties, Map<String, String> env) {
            return new Settings(require(properties, "postgres.url"), require(properties, "postgres.user"),
                    env.getOrDefault("POSTGRES_PASSWORD", ""));
        }

        private static String require(Properties properties, String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalStateException(key + " is not set");
            return value.strip();
        }

        public Settings withUrl(String otherUrl) {
            return new Settings(otherUrl, user, password);
        }

        @Override
        public String toString() {
            return "Settings[url=" + url + ", user=" + user + ", password=" + (password.isEmpty() ? "(none)" : "***") + "]";
        }
    }

    private final Settings settings;
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();

    public PostgresDatabase(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public Settings settings() {
        return settings;
    }

    /** Runs {@code work} in this thread's transaction if there is one, otherwise on its own auto-committed connection. */
    public <T> T withConnection(SqlWork<T> work) {
        Connection current = transaction.get();
        try {
            if (current != null) return work.apply(current);
            try (Connection connection = open()) {
                return work.apply(connection);
            }
        } catch (SQLException e) {
            throw new DatabaseException("database call failed: " + e.getMessage(), e);
        }
    }

    /** All-or-nothing: commits if {@code work} returns, rolls back if it throws. A nested call joins the outer one. */
    public <T> T inTransaction(SqlWork<T> work) {
        if (transaction.get() != null) return withConnection(work);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            transaction.set(connection);
            try {
                T result = work.apply(connection);
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

    public void inTransaction(Runnable work) {
        inTransaction(connection -> {
            work.run();
            return null;
        });
    }

    /**
     * Holds a session advisory lock on {@code key} while {@code action} runs, so the action is serialised across every
     * instance using this database. The lock lives on its own connection; {@code action} may open others.
     */
    public <T> T withAdvisoryLock(String key, Supplier<T> action) {
        try (Connection connection = open()) {
            lockStatement(connection, "SELECT pg_advisory_lock(hashtextextended(?, 0))", key);
            try {
                return action.get();
            } finally {
                // Closing the session would release the lock too; unlocking first frees it without waiting for close.
                lockStatement(connection, "SELECT pg_advisory_unlock(hashtextextended(?, 0))", key);
            }
        } catch (SQLException e) {
            throw new DatabaseException("advisory lock on " + key + " failed: " + e.getMessage(), e);
        }
    }

    private static void lockStatement(Connection connection, String sql, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.execute();
        }
    }

    private Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", settings.user());
        if (!settings.password().isEmpty()) properties.setProperty("password", settings.password());
        properties.setProperty("ApplicationName", "booking-system");
        return DriverManager.getConnection(settings.url(), properties);
    }

    private static void rollback(Connection connection, Exception primary) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
    }
}
