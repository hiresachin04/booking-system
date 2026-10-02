package booking.db;

import booking.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class SchemaMigratorTest {
    private final PostgresTestDatabase test = PostgresTestDatabase.empty();
    private final PostgresDatabase db = test.database();

    @TempDir
    Path dir;

    @AfterEach
    void drop() {
        test.close();
    }

    private List<String> tables() {
        return db.withConnection(c -> {
            List<String> names = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() ORDER BY table_name")) {
                while (r.next()) names.add(r.getString(1));
            }
            return names;
        });
    }

    @Test
    void appliesTheRealMigrationsOnceAndThenHasNothingToDo() {
        SchemaMigrator migrator = new SchemaMigrator(db, PostgresTestDatabase.MIGRATIONS);

        assertEquals(List.of(1), migrator.migrate());
        assertEquals(List.of(), migrator.migrate());
        assertEquals(List.of("bookings", "discount_codes", "notification_outbox", "schema_version", "show_seats", "shows"), tables());
    }

    @Test
    void appliesPendingVersionsInNumericOrder() throws IOException {
        Files.writeString(dir.resolve("V10__second.sql"), "ALTER TABLE t ADD COLUMN b integer;");
        Files.writeString(dir.resolve("V2__first.sql"), "CREATE TABLE t (a integer);");

        assertEquals(List.of(2, 10), new SchemaMigrator(db, dir).migrate());
    }

    @Test
    void aFailingMigrationLeavesTheSchemaAsItWas() throws IOException {
        Files.writeString(dir.resolve("V1__ok.sql"), "CREATE TABLE t (a integer);");
        Files.writeString(dir.resolve("V2__broken.sql"), "CREATE TABLE t (a integer);");

        assertThrows(DatabaseException.class, () -> new SchemaMigrator(db, dir).migrate());
        assertEquals(List.of(), tables());
    }

    @Test
    void refusesToRunAgainstADatabaseFromNewerCode() throws IOException {
        Files.writeString(dir.resolve("V1__ok.sql"), "CREATE TABLE t (a integer);");
        new SchemaMigrator(db, dir).migrate();
        Files.delete(dir.resolve("V1__ok.sql"));
        Files.writeString(dir.resolve("V2__other.sql"), "CREATE TABLE u (a integer);");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new SchemaMigrator(db, dir).migrate());
        assertTrue(e.getMessage().contains("V1"), e.getMessage());
    }

    @Test
    void refusesAMisnamedMigrationFileRatherThanSkippingIt() throws IOException {
        Files.writeString(dir.resolve("V1__ok.sql"), "CREATE TABLE t (a integer);");
        Files.writeString(dir.resolve("v2-oops.sql"), "CREATE TABLE u (a integer);");

        assertThrows(IllegalStateException.class, () -> new SchemaMigrator(db, dir).migrate());
        assertEquals(List.of(), tables());
    }

    @Test
    void refusesTwoMigrationsWithTheSameVersion() throws IOException {
        Files.writeString(dir.resolve("V1__a.sql"), "CREATE TABLE t (a integer);");
        Files.writeString(dir.resolve("V01__b.sql"), "CREATE TABLE u (a integer);");

        assertThrows(IllegalStateException.class, () -> new SchemaMigrator(db, dir).migrate());
    }
}
