package booking.notify;

import booking.booking.Booking;

public interface BookingListener {
    default void onConfirmed(Booking booking) {}

    default void onCancelled(Booking booking) {}

    /** A payment that was left pending turned out not to go through; the customer holds no seats. */
    default void onPaymentFailed(Booking booking) {}

    /** A pending payment was captured after its seats went back on sale, so it was refunded in full. */
    default void onPaymentRefunded(Booking booking) {}
}
