package booking.notify;

import booking.retry.RetryPolicy;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One event for one listener, so a retry never re-sends to listeners that already got it. */
public final class OutboxEntry {
    private final BookingEvent event;
    private final BookingListener listener;
    private DeliveryStatus status = DeliveryStatus.PENDING;
    private int attempts;
    private Instant nextAttemptAt;
    private RuntimeException lastError;
    private Instant sentAt;

    OutboxEntry(BookingEvent event, BookingListener listener, Instant dueAt) {
        this.event = Objects.requireNonNull(event, "event");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.nextAttemptAt = Objects.requireNonNull(dueAt, "dueAt");
    }

    public BookingEvent event() {
        return event;
    }

    public String listener() {
        return listener.name();
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
        switch (event.type()) {
            case CONFIRMED -> listener.onConfirmed(event);
            case CANCELLED -> listener.onCancelled(event);
            case PAYMENT_FAILED -> listener.onPaymentFailed(event);
            case PAYMENT_REFUNDED -> listener.onPaymentRefunded(event);
        }
    }

    synchronized void markSent(Instant now) {
        attempts++;
        status = DeliveryStatus.SENT;
        lastError = null;
        sentAt = now;
    }

    synchronized boolean isSentBefore(Instant cutoff) {
        return status == DeliveryStatus.SENT && !sentAt.isAfter(cutoff);
    }

    synchronized void markFailedAttempt(RuntimeException error, Instant now, RetryPolicy retry) {
        attempts++;
        lastError = error;
        if (attempts >= retry.maxAttempts()) status = DeliveryStatus.FAILED;
        else nextAttemptAt = now.plus(retry.backoffAfter(attempts));
    }
}
