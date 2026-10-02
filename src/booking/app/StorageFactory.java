package booking.app;

import booking.booking.InMemoryStorage;
import booking.booking.Storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Properties;

/** Builds the Storage named by the {@code storage} property, so switching stores is a config change, not a code change. */
public final class StorageFactory {
    public static final String STORAGE_KEY = "storage";
    static final String IN_MEMORY = "in-memory";

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

    // An absent or misspelt value fails at startup instead of quietly choosing a store the operator didn't ask for.
    public static Storage create(Properties properties, Clock clock) {
        String storage = properties.getProperty(STORAGE_KEY);
        if (storage == null || storage.isBlank()) {
            throw new IllegalStateException(STORAGE_KEY + " is not set; expected " + IN_MEMORY);
        }
        return switch (storage.strip()) {
            case IN_MEMORY -> new InMemoryStorage(clock);
            default -> throw new IllegalStateException(STORAGE_KEY + "=" + storage.strip() + " is not supported; expected " + IN_MEMORY);
        };
    }
}
