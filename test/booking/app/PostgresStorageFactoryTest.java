package booking.app;

import booking.booking.PostgresStorage;
import booking.booking.Storage;
import booking.support.Fixtures;
import booking.support.MutableClock;
import booking.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class PostgresStorageFactoryTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T06:30:00Z"));
    private final PostgresTestDatabase test = PostgresTestDatabase.empty();

    @AfterEach
    void drop() {
        test.close();
    }

    private Properties postgres() {
        Properties properties = StorageFactory.load(Path.of("config/application.properties"));
        properties.setProperty("storage", "postgres");
        properties.setProperty("postgres.url", test.database().settings().url());
        return properties;
    }

    private int migrations() {
        return test.database().withConnection(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM schema_version")) {
                r.next();
                return r.getInt(1);
            }
        });
    }

    @Test
    void migratesTheSchemaAndReturnsWorkingPostgresStorage() {
        try (Storage storage = StorageFactory.create(postgres(), Map.of(), clock)) {
            assertInstanceOf(PostgresStorage.class, storage);
            assertEquals(1, migrations());
            storage.shows().add(Fixtures.show("sh", clock.instant().plus(Duration.ofHours(8))));
            assertTrue(storage.shows().find("sh").isPresent());
        }
    }

    @Test
    void startingAgainOnAMigratedDatabaseKeepsItsData() {
        try (Storage first = StorageFactory.create(postgres(), Map.of(), clock)) {
            first.shows().add(Fixtures.show("sh", clock.instant().plus(Duration.ofHours(8))));
        }

        try (Storage restarted = StorageFactory.create(postgres(), Map.of(), clock)) {
            assertEquals(1, migrations());
            assertTrue(restarted.shows().find("sh").isPresent());
        }
    }
}
