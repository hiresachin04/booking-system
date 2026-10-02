package booking.notify;

/** Callbacks may be repeated for the same event; use {@link BookingEvent#id()} to make them idempotent. */
public interface BookingListener {
    /**
     * Identifies this listener in a persistent outbox, which keeps one delivery per event per listener; keep it
     * stable across releases or pending deliveries to it are orphaned.
     */
    default String name() {
        return getClass().getName();
    }

    default void onConfirmed(BookingEvent event) {}

    default void onCancelled(BookingEvent event) {}

    /** A payment that was left pending turned out not to go through; the customer holds no seats. */
    default void onPaymentFailed(BookingEvent event) {}

    /** A pending payment was captured after its seats went back on sale, so it was refunded in full. */
    default void onPaymentRefunded(BookingEvent event) {}
}
