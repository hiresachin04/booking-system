package booking.notify;

import booking.booking.Booking;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Decouples notifications from the booking flow. {@link #publish} only records the event, so a broken listener
 * can't make a committed booking look failed. {@link #dispatchDue} delivers, retries with backoff, and parks
 * entries that keep failing as FAILED, with their last error, for someone to look at.
 */
public final class NotificationOutbox {
    private final List<BookingListener> listeners;
    private final RetryPolicy retry;
    private final Clock clock;
    private final List<OutboxEntry> entries = new ArrayList<>();
    // Separate from the entries lock so publish never waits behind a slow listener.
    private final Object dispatchLock = new Object();

    public NotificationOutbox(List<BookingListener> listeners, RetryPolicy retry, Clock clock) {
        this.listeners = List.copyOf(listeners);
        this.retry = Objects.requireNonNull(retry, "retry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void publish(BookingEventType type, Booking booking) {
        Instant now = clock.instant();
        BookingEvent event = new BookingEvent(UUID.randomUUID().toString(), type, booking);
        synchronized (entries) {
            for (BookingListener listener : listeners) entries.add(new OutboxEntry(event, listener, now));
        }
    }

    /**
     * Attempts every entry that is due. Meant to run on a schedule. Never throws for a listener failure: that is
     * recorded on the entry and retried, so one bad listener can't stop the schedule.
     */
    public void dispatchDue() {
        synchronized (dispatchLock) {
            Instant now = clock.instant();
            for (OutboxEntry entry : snapshot()) {
                if (!entry.isDue(now)) continue;
                try {
                    entry.deliver();
                    entry.markSent();
                } catch (RuntimeException e) {
                    entry.markFailedAttempt(e, now, retry);
                }
            }
        }
    }

    public List<OutboxEntry> entries(DeliveryStatus status) {
        return snapshot().stream().filter(e -> e.status() == status).toList();
    }

    private List<OutboxEntry> snapshot() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }
}
