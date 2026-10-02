package booking.app;

import booking.booking.InMemoryStorage;
import booking.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageFactoryTest {
    private static final Path COMMITTED_CONFIG = Path.of("config/application.properties");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T06:30:00Z"));

    private static Properties storage(String value) {
        Properties properties = new Properties();
        properties.setProperty(StorageFactory.STORAGE_KEY, value);
        return properties;
    }

    @Test
    void committedConfigSelectsInMemoryStorage() {
        assertInstanceOf(InMemoryStorage.class, StorageFactory.create(StorageFactory.load(COMMITTED_CONFIG), Map.of(), clock));
    }

    @Test
    void committedConfigHoldsNoSecrets() {
        for (String key : StorageFactory.load(COMMITTED_CONFIG).stringPropertyNames()) {
            String lower = key.toLowerCase();
            assertTrue(!lower.contains("password") && !lower.contains("secret"), "secret-looking key in committed config: " + key);
        }
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertInstanceOf(InMemoryStorage.class, StorageFactory.create(storage("  in-memory "), Map.of(), clock));
    }

    @Test
    void refusesToStartWhenStorageIsNotSet() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> StorageFactory.create(new Properties(), Map.of(), clock));
        assertEquals("storage is not set; expected in-memory or postgres", e.getMessage());
    }

    @Test
    void refusesABlankStorage() {
        assertThrows(IllegalStateException.class, () -> StorageFactory.create(storage("   "), Map.of(), clock));
    }

    @Test
    void refusesAnUnknownStorageAndNamesTheValue() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> StorageFactory.create(storage("in_memory"), Map.of(), clock));
        assertEquals("storage=in_memory is not supported; expected in-memory or postgres", e.getMessage());
    }

    @Test
    void postgresWithoutConnectionSettingsNamesTheMissingOne() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> StorageFactory.create(storage("postgres"), Map.of(), clock));
        assertEquals("postgres.url is not set", e.getMessage());
    }

    @Test
    void reportsAMissingConfigFileByPath() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> StorageFactory.load(Path.of("config/nope.properties")));
        assertTrue(e.getMessage().startsWith("config file not found: "), e.getMessage());
        assertTrue(e.getMessage().endsWith("config/nope.properties"), e.getMessage());
    }
}
