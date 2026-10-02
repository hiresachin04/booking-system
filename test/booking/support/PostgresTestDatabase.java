package booking.support;

import booking.app.StorageFactory;
import booking.db.PostgresDatabase;
import booking.db.SchemaMigrator;

import java.nio.file.Path;
import java.sql.Statement;
import java.util.UUID;

/**
 * A throwaway schema on the server named in config/application.properties, migrated to the current version and
 * dropped on close, so PostgreSQL tests never see each other's rows or leave anything behind.
 */
public final class PostgresTestDatabase implements AutoCloseable {
    public static final Path MIGRATIONS = Path.of("db/migrations");

    private final PostgresDatabase admin;
    private final String schema;
    private final PostgresDatabase database;

    private PostgresTestDatabase(boolean migrate) {
        PostgresDatabase.Settings settings = PostgresDatabase.Settings.from(
                StorageFactory.load(Path.of("config/application.properties")), System.getenv());
        this.admin = new PostgresDatabase(settings);
        this.schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        admin.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            return null;
        });
        String separator = settings.url().contains("?") ? "&" : "?";
        this.database = new PostgresDatabase(settings.withUrl(settings.url() + separator + "currentSchema=" + schema));
        if (migrate) new SchemaMigrator(database, MIGRATIONS).migrate();
    }

    /** Migrated to the latest schema. */
    public static PostgresTestDatabase create() {
        return new PostgresTestDatabase(true);
    }

    /** An empty schema, for testing the migrator itself. */
    public static PostgresTestDatabase empty() {
        return new PostgresTestDatabase(false);
    }

    public PostgresDatabase database() {
        return database;
    }

    public String schema() {
        return schema;
    }

    @Override
    public void close() {
        admin.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
            return null;
        });
    }
}
