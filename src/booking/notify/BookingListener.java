package booking.notify;

import booking.booking.Booking;

public interface BookingListener {
    default void onConfirmed(Booking booking) {}

    default void onCancelled(Booking booking) {}
}
