package booking.db;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No server needed: how connection settings are read and printed. */
class PostgresSettingsTest {
    private static Properties props(String url, String user) {
        Properties p = new Properties();
        if (url != null) p.setProperty("postgres.url", url);
        if (user != null) p.setProperty("postgres.user", user);
        return p;
    }

    @Test
    void takesThePasswordFromTheEnvironmentNotTheProperties() {
        PostgresDatabase.Settings settings = PostgresDatabase.Settings.from(
                props("jdbc:postgresql://localhost/booking", "booking"), Map.of("POSTGRES_PASSWORD", "hunter2-long"));

        assertEquals("hunter2-long", settings.password());
        assertFalse(settings.toString().contains("hunter2-long"));
        assertTrue(settings.toString().contains("password=***"));
    }

    @Test
    void allowsNoPasswordForLocalTrustAuthentication() {
        PostgresDatabase.Settings settings = PostgresDatabase.Settings.from(props("jdbc:postgresql://localhost/booking", "booking"), Map.of());

        assertEquals("", settings.password());
        assertTrue(settings.toString().contains("password=(none)"));
    }

    @Test
    void namesTheMissingProperty() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PostgresDatabase.Settings.from(props("jdbc:postgresql://localhost/booking", null), Map.of()));
        assertEquals("postgres.user is not set", e.getMessage());
    }

    @Test
    void readsBothPoolSizes() {
        Properties properties = props("jdbc:postgresql://localhost/booking", "booking");
        properties.setProperty("postgres.pool.size", " 25 ");
        properties.setProperty("postgres.pool.lockSize", "4");

        PostgresDatabase.Settings settings = PostgresDatabase.Settings.from(properties, Map.of());

        assertEquals(25, settings.poolSize());
        assertEquals(4, settings.lockPoolSize());
    }

    @Test
    void defaultsBothPoolSizesWhenUnset() {
        PostgresDatabase.Settings settings = PostgresDatabase.Settings.from(props("jdbc:postgresql://localhost/booking", "booking"), Map.of());

        assertEquals(PostgresDatabase.DEFAULT_POOL_SIZE, settings.poolSize());
        assertEquals(PostgresDatabase.DEFAULT_POOL_SIZE, settings.lockPoolSize());
    }

    @Test
    void namesAPoolSizeThatIsNotANumber() {
        Properties properties = props("jdbc:postgresql://localhost/booking", "booking");
        properties.setProperty("postgres.pool.lockSize", "ten");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PostgresDatabase.Settings.from(properties, Map.of()));
        assertEquals("postgres.pool.lockSize must be a whole number: ten", e.getMessage());
    }

    @Test
    void rejectsAnEmptyPool() {
        assertThrows(IllegalArgumentException.class, () -> new PostgresDatabase.Settings("jdbc:postgresql://localhost/booking", "u", "", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PostgresDatabase.Settings("jdbc:postgresql://localhost/booking", "u", "", 1, 0));
    }

    @Test
    void rejectsAUrlForAnotherDatabase() {
        assertThrows(IllegalArgumentException.class, () -> new PostgresDatabase.Settings("jdbc:mysql://localhost/booking", "u", ""));
    }
}
