package booking.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies {@code V<n>__<name>.sql} files in version order, recording each in {@code schema_version}. Everything
 * pending runs in one transaction under an advisory lock, so a failure leaves the schema untouched and two
 * instances starting together can't both migrate.
 */
public final class SchemaMigrator {
    private static final Pattern FILE = Pattern.compile("V(\\d+)__([A-Za-z0-9_]+)\\.sql");

    private final PostgresDatabase database;
    private final Path directory;

    public SchemaMigrator(PostgresDatabase database, Path directory) {
        this.database = Objects.requireNonNull(database, "database");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** Returns the versions applied by this call; empty if the schema was already current. */
    public List<Integer> migrate() {
        TreeMap<Integer, Path> files = migrationFiles();
        return database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('booking.schema_version', 0))");
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version integer PRIMARY KEY, name text NOT NULL, "
                        + "applied_at timestamptz NOT NULL DEFAULT now())");
            }
            Set<Integer> applied = new HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT version FROM schema_version")) {
                while (rows.next()) applied.add(rows.getInt(1));
            }
            for (int version : applied) {
                if (!files.containsKey(version)) {
                    throw new IllegalStateException("database has migration V" + version + " that this code doesn't know; "
                            + "refusing to run older code against a newer schema");
                }
            }
            List<Integer> ran = new ArrayList<>();
            for (var file : files.entrySet()) {
                if (applied.contains(file.getKey())) continue;
                try (Statement statement = connection.createStatement()) {
                    statement.execute(read(file.getValue()));
                }
                try (PreparedStatement record = connection.prepareStatement("INSERT INTO schema_version (version, name) VALUES (?, ?)")) {
                    record.setInt(1, file.getKey());
                    record.setString(2, file.getValue().getFileName().toString());
                    record.executeUpdate();
                }
                ran.add(file.getKey());
            }
            return ran;
        });
    }

    // A .sql file that doesn't match the naming pattern is an error, not skipped: a typo must not silently drop a migration.
    private TreeMap<Integer, Path> migrationFiles() {
        TreeMap<Integer, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.sql")) {
            for (Path entry : entries) {
                Matcher name = FILE.matcher(entry.getFileName().toString());
                if (!name.matches()) throw new IllegalStateException("migration file not named V<n>__<name>.sql: " + entry);
                Path previous = files.put(Integer.parseInt(name.group(1)), entry);
                if (previous != null) throw new IllegalStateException("two migrations for version " + name.group(1) + ": " + previous + ", " + entry);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not list migrations in " + directory.toAbsolutePath(), e);
        }
        if (files.isEmpty()) throw new IllegalStateException("no migrations in " + directory.toAbsolutePath());
        return files;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read migration " + file, e);
        }
    }
}
