package booking.notify;

import booking.booking.Booking;

/**
 * Notifications decoupled from the booking flow: {@link #publish} only records an event; {@link #dispatchDue},
 * run on a schedule, delivers it with retries. Callers publish inside the same transaction as the state change.
 */
public interface Outbox {
    void publish(BookingEventType type, Booking booking);

    /** Never throws for a listener failure; that is recorded and retried. */
    DispatchReport dispatchDue();
}
