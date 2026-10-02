package booking.booking;

import booking.db.EpochNanos;
import booking.db.PostgresDatabase;
import booking.db.ShowJson;
import booking.inventory.PostgresShowInventory;
import booking.inventory.SeatInventory;
import booking.model.Seat;
import booking.model.Show;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Shows in the shows table, with one show_seats row per seat created alongside in the same transaction. */
public final class PostgresShowRepository implements ShowStore {
    private static final String UNIQUE_VIOLATION = "23505";

    private final PostgresDatabase database;
    private final Clock clock;

    public PostgresShowRepository(PostgresDatabase database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PostgresShowInventory add(Show show) {
        try {
            database.inTransaction(connection -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO shows (id, definition, starts_at) VALUES (?, ?::jsonb, ?)")) {
                    insert.setString(1, show.id());
                    insert.setString(2, ShowJson.encode(show).toString());
                    insert.setLong(3, EpochNanos.of(show.startsAt()));
                    insert.executeUpdate();
                }
                try (PreparedStatement seat = connection.prepareStatement(
                        "INSERT INTO show_seats (show_id, seat_id, status) VALUES (?, ?, 'AVAILABLE')")) {
                    for (Seat s : show.screen().seats()) {
                        seat.setString(1, show.id());
                        seat.setString(2, s.id());
                        seat.addBatch();
                    }
                    seat.executeBatch();
                }
                return null;
            });
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) throw new IllegalArgumentException("show already exists: " + show.id(), e);
            throw e;
        }
        return new PostgresShowInventory(database, show, clock);
    }

    @Override
    public Optional<SeatInventory> find(String showId) {
        return database.withConnection(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT definition FROM shows WHERE id = ?")) {
                select.setString(1, showId);
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) return Optional.empty();
                    return Optional.of(new PostgresShowInventory(database, ShowJson.decode(row.getString(1)), clock));
                }
            }
        });
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
