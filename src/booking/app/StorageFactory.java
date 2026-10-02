package booking.app;

import booking.booking.InMemoryStorage;
import booking.booking.PostgresStorage;
import booking.booking.Storage;
import booking.db.PostgresDatabase;
import booking.db.SchemaMigrator;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Properties;

/** Builds the Storage named by the {@code storage} property, so switching stores is a config change, not a code change. */
public final class StorageFactory {
    public static final String STORAGE_KEY = "storage";
    static final String IN_MEMORY = "in-memory";
    static final String POSTGRES = "postgres";
    private static final String MIGRATIONS_KEY = "postgres.migrations";
    private static final String DEFAULT_MIGRATIONS = "db/migrations";
    private static final String EXPECTED = IN_MEMORY + " or " + POSTGRES;

    private StorageFactory() {}

    public static Properties load(Path file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("config file not found: " + file.toAbsolutePath(), e);
        } catch (IOException e) {
            throw new IllegalStateException("could not read config file " + file.toAbsolutePath(), e);
        }
        return properties;
    }

    /**
     * An absent or misspelt value fails at startup instead of quietly choosing a store the operator didn't ask for.
     * For {@code postgres} the schema is migrated before returning, so the app never runs against an old schema;
     * {@code env} supplies POSTGRES_PASSWORD.
     */
    public static Storage create(Properties properties, Map<String, String> env, Clock clock) {
        String storage = properties.getProperty(STORAGE_KEY);
        if (storage == null || storage.isBlank()) {
            throw new IllegalStateException(STORAGE_KEY + " is not set; expected " + EXPECTED);
        }
        return switch (storage.strip()) {
            case IN_MEMORY -> new InMemoryStorage(clock);
            case POSTGRES -> {
                PostgresDatabase database = new PostgresDatabase(PostgresDatabase.Settings.from(properties, env));
                new SchemaMigrator(database, Path.of(properties.getProperty(MIGRATIONS_KEY, DEFAULT_MIGRATIONS).strip())).migrate();
                yield new PostgresStorage(database, clock);
            }
            default -> throw new IllegalStateException(STORAGE_KEY + "=" + storage.strip() + " is not supported; expected " + EXPECTED);
        };
    }
}
