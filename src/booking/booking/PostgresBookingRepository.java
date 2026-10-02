package booking.booking;

import booking.db.BookingJson;
import booking.db.EpochNanos;
import booking.db.PostgresDatabase;
import booking.db.ShowJson;
import booking.model.User;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bookings in the bookings table. Every find returns a fresh copy; callers change it and {@link #update} it under its lock. */
public final class PostgresBookingRepository implements BookingStore {
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String SELECT = "SELECT b.id, b.user_id, b.user_name, s.definition, b.seat_ids, b.price, b.hold_expires_at, "
            + "b.discount_code, b.checkout, b.status, b.payment_id, b.payment_pending_since, b.capture_attempts, b.next_capture_at, "
            + "b.capture_given_up, b.refunded_paise FROM bookings b JOIN shows s ON s.id = b.show_id ";

    private final PostgresDatabase database;

    public PostgresBookingRepository(PostgresDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void save(Booking booking) {
        Booking.State state = booking.state();
        try {
            database.withConnection(connection -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO bookings (id, user_id, user_name, show_id, seat_ids, price, hold_expires_at, discount_code, checkout, "
                                + "status, payment_id, payment_pending_since, capture_attempts, next_capture_at, capture_given_up, refunded_paise) "
                                + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, booking.id());
                    insert.setString(2, booking.user().id());
                    insert.setString(3, booking.user().name());
                    insert.setString(4, booking.show().id());
                    insert.setArray(5, connection.createArrayOf("text", booking.seatIds().toArray()));
                    insert.setString(6, BookingJson.encodePrice(booking.price()));
                    insert.setLong(7, EpochNanos.of(booking.holdExpiresAt()));
                    insert.setString(8, booking.discountCode().orElse(null));
                    insert.setString(9, BookingJson.encodeCheckout(booking.checkout()));
                    setState(insert, 10, state);
                    insert.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) throw new IllegalStateException("booking already saved: " + booking.id(), e);
            throw e;
        }
    }

    @Override
    public void update(Booking booking) {
        Booking.State state = booking.state();
        int updated = database.withConnection(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE bookings SET status = ?, payment_id = ?, payment_pending_since = ?, capture_attempts = ?, next_capture_at = ?, "
                            + "capture_given_up = ?, refunded_paise = ? WHERE id = ?")) {
                setState(update, 1, state);
                update.setString(8, booking.id());
                return update.executeUpdate();
            }
        });
        if (updated != 1) throw new IllegalStateException("no stored booking " + booking.id() + " to update");
    }

    @Override
    public Optional<Booking> find(String bookingId) {
        List<Booking> found = query("WHERE b.id = ?", bookingId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    // Matches the in-memory store, which compares the whole User (id and name).
    @Override
    public List<Booking> findByUser(User user) {
        return query("WHERE b.user_id = ? AND b.user_name = ?", user.id(), user.name());
    }

    @Override
    public List<Booking> findByStatus(BookingStatus status) {
        return query("WHERE b.status = ?", status.name());
    }

    private List<Booking> query(String where, String... parameters) {
        return database.withConnection(connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT + where)) {
                for (int i = 0; i < parameters.length; i++) select.setString(i + 1, parameters[i]);
                try (ResultSet row = select.executeQuery()) {
                    List<Booking> bookings = new ArrayList<>();
                    while (row.next()) bookings.add(read(row));
                    return bookings;
                }
            }
        });
    }

    private static Booking read(ResultSet row) throws SQLException {
        Array seats = row.getArray(5);
        Booking.State state = new Booking.State(BookingStatus.valueOf(row.getString(10)), row.getString(11),
                instantOrNull(row, 12), row.getInt(13), instantOrNull(row, 14), row.getBoolean(15), row.getLong(16));
        return Booking.restore(row.getString(1), new User(row.getString(2), row.getString(3)), ShowJson.decode(row.getString(4)),
                Arrays.asList((String[]) seats.getArray()), BookingJson.decodePrice(row.getString(6)),
                EpochNanos.toInstant(row.getLong(7)), row.getString(8), BookingJson.decodeCheckout(row.getString(9)), state);
    }

    private static void setState(PreparedStatement statement, int first, Booking.State state) throws SQLException {
        statement.setString(first, state.status().name());
        statement.setString(first + 1, state.paymentId());
        setInstant(statement, first + 2, state.paymentPendingSince());
        statement.setInt(first + 3, state.captureAttempts());
        setInstant(statement, first + 4, state.nextCaptureAt());
        statement.setBoolean(first + 5, state.captureGivenUp());
        statement.setLong(first + 6, state.refundedPaise());
    }

    private static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, EpochNanos.of(instant));
    }

    private static Instant instantOrNull(ResultSet row, int column) throws SQLException {
        long nanos = row.getLong(column);
        return row.wasNull() ? null : EpochNanos.toInstant(nanos);
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
