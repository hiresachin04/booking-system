package booking.notify;

import booking.booking.Booking;

import java.util.Objects;

/**
 * {@code id} is fixed when the event is published and stays the same on every retry. Delivery is at-least-once (a
 * listener can succeed and then fail to report it), so listeners pass {@code id} to their provider as the
 * idempotency key; that is what turns a retried send into a no-op instead of a duplicate email.
 */
public record BookingEvent(String id, BookingEventType type, Booking booking) {
    public BookingEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(booking, "booking");
    }
}
