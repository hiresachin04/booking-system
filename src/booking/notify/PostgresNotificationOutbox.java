package booking.notify;

import booking.booking.Booking;
import booking.booking.BookingStore;
import booking.db.EpochNanos;
import booking.db.PostgresDatabase;
import booking.retry.RetryPolicy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The outbox in the notification_outbox table: one row per event per listener. {@link #publish} joins the caller's
 * transaction, so the row commits with the booking change or not at all. {@link #dispatchDue} claims due rows by
 * pushing their next attempt one lease into the future (SKIP LOCKED, so instances never claim the same row), delivers
 * outside any transaction, then records the outcome. A dispatcher that dies mid-delivery leaves the row to come due
 * again when the lease ends: delivery is at-least-once, and BookingEvent.id() lets listeners dedupe.
 */
public final class PostgresNotificationOutbox implements Outbox {
    /** How long a claimed row is hidden from other dispatchers; longer than any one delivery should take. */
    static final Duration LEASE = Duration.ofMinutes(5);
    private static final int BATCH = 100;

    /** One row as tests and operators see it. */
    public record Entry(String eventId, BookingEventType type, String bookingId, String listener, DeliveryStatus status,
                        int attempts, Instant nextAttemptAt, Optional<String> lastError) {}

    private record Claimed(long id, String eventId, BookingEventType type, String bookingId, String listener, int attempts) {}

    private final PostgresDatabase database;
    private final BookingStore bookings;
    private final Map<String, BookingListener> listeners = new HashMap<>();
    private final RetryPolicy retry;
    private final Duration sentRetention;
    private final Clock clock;

    public PostgresNotificationOutbox(PostgresDatabase database, BookingStore bookings, List<BookingListener> listeners,
                                      RetryPolicy retry, Duration sentRetention, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.bookings = Objects.requireNonNull(bookings, "bookings");
        for (BookingListener listener : listeners) {
            if (this.listeners.putIfAbsent(listener.name(), listener) != null) {
                throw new IllegalArgumentException("two listeners named " + listener.name() + "; outbox rows couldn't tell them apart");
            }
        }
        this.retry = Objects.requireNonNull(retry, "retry");
        this.sentRetention = Objects.requireNonNull(sentRetention, "sentRetention");
        if (sentRetention.isNegative()) throw new IllegalArgumentException("sentRetention must not be negative");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void publish(BookingEventType type, Booking booking) {
        if (listeners.isEmpty()) return;
        String eventId = UUID.randomUUID().toString();
        long now = EpochNanos.of(clock.instant());
        database.withConnection(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO notification_outbox (event_id, event_type, booking_id, listener, status, next_attempt_at) "
                            + "VALUES (?, ?, ?, ?, 'PENDING', ?)")) {
                for (String listener : listeners.keySet()) {
                    insert.setString(1, eventId);
                    insert.setString(2, type.name());
                    insert.setString(3, booking.id());
                    insert.setString(4, listener);
                    insert.setLong(5, now);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    @Override
    public DispatchReport dispatchDue() {
        Instant now = clock.instant();
        int sent = 0;
        int failedAttempts = 0;
        int parked = 0;
        for (Claimed claimed : claimDue(now)) {
            try {
                deliver(claimed);
                markSent(claimed, now);
                sent++;
            } catch (RuntimeException e) {
                failedAttempts++;
                if (markFailedAttempt(claimed, e, now)) parked++;
            }
        }
        return new DispatchReport(sent, failedAttempts, parked, purgeSentBefore(now.minus(sentRetention)));
    }

    public List<Entry> entries(DeliveryStatus status) {
        return database.withConnection(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT event_id, event_type, booking_id, listener, status, attempts, next_attempt_at, last_error "
                            + "FROM notification_outbox WHERE status = ? ORDER BY id")) {
                select.setString(1, status.name());
                try (ResultSet row = select.executeQuery()) {
                    List<Entry> entries = new ArrayList<>();
                    while (row.next()) {
                        entries.add(new Entry(row.getString(1), BookingEventType.valueOf(row.getString(2)), row.getString(3),
                                row.getString(4), DeliveryStatus.valueOf(row.getString(5)), row.getInt(6),
                                EpochNanos.toInstant(row.getLong(7)), Optional.ofNullable(row.getString(8))));
                    }
                    return entries;
                }
            }
        });
    }

    private List<Claimed> claimDue(Instant now) {
        return database.withConnection(connection -> {
            try (PreparedStatement claim = connection.prepareStatement(
                    "UPDATE notification_outbox SET next_attempt_at = ? WHERE id IN ("
                            + "SELECT id FROM notification_outbox WHERE status = 'PENDING' AND next_attempt_at <= ? "
                            + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED) "
                            + "RETURNING id, event_id, event_type, booking_id, listener, attempts")) {
                claim.setLong(1, EpochNanos.of(now.plus(LEASE)));
                claim.setLong(2, EpochNanos.of(now));
                claim.setInt(3, BATCH);
                try (ResultSet row = claim.executeQuery()) {
                    List<Claimed> claimed = new ArrayList<>();
                    while (row.next()) {
                        claimed.add(new Claimed(row.getLong(1), row.getString(2), BookingEventType.valueOf(row.getString(3)),
                                row.getString(4), row.getString(5), row.getInt(6)));
                    }
                    return claimed;
                }
            }
        });
    }

    private void deliver(Claimed claimed) {
        BookingListener listener = listeners.get(claimed.listener());
        if (listener == null) throw new IllegalStateException("no listener named " + claimed.listener() + " is registered");
        Booking booking = bookings.find(claimed.bookingId())
                .orElseThrow(() -> new IllegalStateException("booking " + claimed.bookingId() + " no longer exists"));
        BookingEvent event = new BookingEvent(claimed.eventId(), claimed.type(), booking);
        switch (claimed.type()) {
            case CONFIRMED -> listener.onConfirmed(event);
            case CANCELLED -> listener.onCancelled(event);
            case PAYMENT_FAILED -> listener.onPaymentFailed(event);
            case PAYMENT_REFUNDED -> listener.onPaymentRefunded(event);
        }
    }

    private void markSent(Claimed claimed, Instant now) {
        database.withConnection(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE notification_outbox SET status = 'SENT', attempts = attempts + 1, sent_at = ?, last_error = NULL WHERE id = ?")) {
                update.setLong(1, EpochNanos.of(now));
                update.setLong(2, claimed.id());
                update.executeUpdate();
            }
            return null;
        });
    }

    /** Returns whether this failure used up the last attempt and parked the entry as FAILED. */
    private boolean markFailedAttempt(Claimed claimed, RuntimeException error, Instant now) {
        int attempts = claimed.attempts() + 1;
        boolean parked = attempts >= retry.maxAttempts();
        database.withConnection(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE notification_outbox SET attempts = ?, status = ?, next_attempt_at = ?, last_error = ? WHERE id = ?")) {
                update.setInt(1, attempts);
                update.setString(2, parked ? "FAILED" : "PENDING");
                update.setLong(3, EpochNanos.of(parked ? now : now.plus(retry.backoffAfter(attempts))));
                update.setString(4, error.toString());
                update.setLong(5, claimed.id());
                update.executeUpdate();
            }
            return null;
        });
        return parked;
    }

    private int purgeSentBefore(Instant cutoff) {
        return database.withConnection(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM notification_outbox WHERE status = 'SENT' AND sent_at <= ?")) {
                delete.setLong(1, EpochNanos.of(cutoff));
                return delete.executeUpdate();
            }
        });
    }
}
