package booking.inventory;

import booking.db.EpochNanos;
import booking.db.PostgresDatabase;
import booking.model.Seat;
import booking.model.Show;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Seat state for one show in the show_seats table. Every change first locks the requested rows with
 * {@code SELECT … ORDER BY seat_id FOR UPDATE} in one transaction, then applies the same rules as {@link ShowInventory}.
 * The row locks serialise racing bookings, including across instances, and the fixed lock order rules out
 * deadlocks between two holds that overlap in different orders.
 */
public final class PostgresShowInventory implements SeatInventory {
    private record Row(String status, String bookingId, Long holdExpiresAt) {}

    private final PostgresDatabase database;
    private final Show show;
    private final Clock clock;
    private final Set<String> seatIds;

    public PostgresShowInventory(PostgresDatabase database, Show show, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.show = Objects.requireNonNull(show, "show");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.seatIds = show.screen().seats().stream().map(Seat::id).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Show show() {
        return show;
    }

    @Override
    public void hold(String bookingId, List<String> requested, Instant expiresAt) {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireKnownDistinct(requested);
        database.inTransaction(connection -> {
            Map<String, Row> rows = lock(connection, requested);
            long now = EpochNanos.of(clock.instant());
            if (EpochNanos.of(expiresAt) <= now) throw new IllegalArgumentException("hold would already be expired: " + expiresAt);
            List<String> taken = new ArrayList<>();
            for (String seatId : requested) {
                if (!isFree(rows.get(seatId), now)) taken.add(seatId);
            }
            if (!taken.isEmpty()) throw new SeatsUnavailableException(taken);
            set(connection, requested, "HELD", bookingId, EpochNanos.of(expiresAt));
            return null;
        });
    }

    @Override
    public void confirm(String bookingId, List<String> requested) {
        requireKnownDistinct(requested);
        database.inTransaction(connection -> {
            Map<String, Row> rows = lock(connection, requested);
            long now = EpochNanos.of(clock.instant());
            for (String seatId : requested) {
                Row row = rows.get(seatId);
                boolean liveHold = row.status().equals("HELD") && bookingId.equals(row.bookingId()) && now < row.holdExpiresAt();
                boolean alreadyOurs = row.status().equals("BOOKED") && bookingId.equals(row.bookingId());
                if (!liveHold && !alreadyOurs) throw new HoldExpiredException(bookingId);
            }
            set(connection, requested, "BOOKED", bookingId, null);
            return null;
        });
    }

    @Override
    public void release(String bookingId, List<String> requested) {
        requireKnownDistinct(requested);
        database.inTransaction(connection -> {
            Map<String, Row> rows = lock(connection, requested);
            List<String> ours = new ArrayList<>();
            for (String seatId : requested) {
                Row row = rows.get(seatId);
                if (row.status().equals("HELD") && bookingId.equals(row.bookingId())) ours.add(seatId);
            }
            if (!ours.isEmpty()) set(connection, ours, "AVAILABLE", null, null);
            return null;
        });
    }

    @Override
    public void cancel(String bookingId, List<String> requested) {
        requireKnownDistinct(requested);
        database.inTransaction(connection -> {
            Map<String, Row> rows = lock(connection, requested);
            for (String seatId : requested) {
                Row row = rows.get(seatId);
                if (!(row.status().equals("BOOKED") && bookingId.equals(row.bookingId()))) {
                    throw new IllegalStateException("seat " + seatId + " is not booked by " + bookingId);
                }
            }
            set(connection, requested, "AVAILABLE", null, null);
            return null;
        });
    }

    @Override
    public void releaseAll(String bookingId, List<String> requested) {
        requireKnownDistinct(requested);
        database.inTransaction(connection -> {
            Map<String, Row> rows = lock(connection, requested);
            List<String> ours = new ArrayList<>();
            for (String seatId : requested) {
                if (bookingId.equals(rows.get(seatId).bookingId())) ours.add(seatId);
            }
            if (!ours.isEmpty()) set(connection, ours, "AVAILABLE", null, null);
            return null;
        });
    }

    @Override
    public SeatStatus status(String seatId) {
        requireKnownDistinct(List.of(seatId));
        return database.withConnection(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT status, hold_expires_at FROM show_seats WHERE show_id = ? AND seat_id = ?")) {
                select.setString(1, show.id());
                select.setString(2, seatId);
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) throw new IllegalStateException("no row for seat " + seatId + " of show " + show.id());
                    return switch (row.getString(1)) {
                        case "AVAILABLE" -> SeatStatus.AVAILABLE;
                        case "HELD" -> EpochNanos.of(clock.instant()) < row.getLong(2) ? SeatStatus.HELD : SeatStatus.AVAILABLE;
                        case "BOOKED" -> SeatStatus.BOOKED;
                        default -> throw new IllegalStateException("unknown seat status " + row.getString(1));
                    };
                }
            }
        });
    }

    private Map<String, Row> lock(Connection connection, List<String> requested) throws SQLException {
        Map<String, Row> rows = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT seat_id, status, booking_id, hold_expires_at FROM show_seats "
                        + "WHERE show_id = ? AND seat_id = ANY (?) ORDER BY seat_id FOR UPDATE")) {
            select.setString(1, show.id());
            select.setArray(2, textArray(connection, requested));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    long expires = row.getLong(4);
                    rows.put(row.getString(1), new Row(row.getString(2), row.getString(3), row.wasNull() ? null : expires));
                }
            }
        }
        if (rows.size() != requested.size()) throw new IllegalStateException("show " + show.id() + " is missing seat rows for " + requested);
        return rows;
    }

    private void set(Connection connection, List<String> seats, String status, String bookingId, Long holdExpiresAt) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE show_seats SET status = ?, booking_id = ?, hold_expires_at = ? WHERE show_id = ? AND seat_id = ANY (?)")) {
            update.setString(1, status);
            update.setString(2, bookingId);
            if (holdExpiresAt == null) update.setNull(3, java.sql.Types.BIGINT);
            else update.setLong(3, holdExpiresAt);
            update.setString(4, show.id());
            update.setArray(5, textArray(connection, seats));
            update.executeUpdate();
        }
    }

    private static Array textArray(Connection connection, List<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray());
    }

    private static boolean isFree(Row row, long now) {
        return switch (row.status()) {
            case "AVAILABLE" -> true;
            case "HELD" -> now >= row.holdExpiresAt();
            case "BOOKED" -> false;
            default -> throw new IllegalStateException("unknown seat status " + row.status());
        };
    }

    private void requireKnownDistinct(List<String> requested) {
        if (requested.isEmpty()) throw new IllegalArgumentException("no seats given");
        Set<String> seen = new HashSet<>();
        for (String seatId : requested) {
            if (!seatIds.contains(seatId)) throw new IllegalArgumentException("seat " + seatId + " is not in show " + show.id());
            if (!seen.add(seatId)) throw new IllegalArgumentException("seat " + seatId + " requested twice");
        }
    }
}
