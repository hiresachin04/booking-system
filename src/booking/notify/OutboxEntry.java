package booking.notify;

import booking.booking.Booking;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One event for one listener, so a retry never re-sends to listeners that already got it. */
public final class OutboxEntry {
    private final BookingEventType type;
    private final Booking booking;
    private final BookingListener listener;
    private DeliveryStatus status = DeliveryStatus.PENDING;
    private int attempts;
    private Instant nextAttemptAt;
    private RuntimeException lastError;

    OutboxEntry(BookingEventType type, Booking booking, BookingListener listener, Instant dueAt) {
        this.type = Objects.requireNonNull(type, "type");
        this.booking = Objects.requireNonNull(booking, "booking");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.nextAttemptAt = Objects.requireNonNull(dueAt, "dueAt");
    }

    public BookingEventType type() {
        return type;
    }

    public Booking booking() {
        return booking;
    }

    public synchronized DeliveryStatus status() {
        return status;
    }

    public synchronized int attempts() {
        return attempts;
    }

    public synchronized Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public synchronized Optional<RuntimeException> lastError() {
        return Optional.ofNullable(lastError);
    }

    synchronized boolean isDue(Instant now) {
        return status == DeliveryStatus.PENDING && !now.isBefore(nextAttemptAt);
    }

    void deliver() {
        switch (type) {
            case CONFIRMED -> listener.onConfirmed(booking);
            case CANCELLED -> listener.onCancelled(booking);
        }
    }

    synchronized void markSent() {
        attempts++;
        status = DeliveryStatus.SENT;
        lastError = null;
    }

    synchronized void markFailedAttempt(RuntimeException error, Instant now, RetryPolicy retry) {
        attempts++;
        lastError = error;
        if (attempts >= retry.maxAttempts()) status = DeliveryStatus.FAILED;
        else nextAttemptAt = now.plus(retry.backoffAfter(attempts));
    }
}
