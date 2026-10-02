package booking.booking;

import booking.discount.DiscountStore;
import booking.notify.BookingListener;
import booking.notify.Outbox;
import booking.retry.RetryPolicy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** One consistent set of stores for a BookingService; StorageFactory picks in-memory or PostgreSQL. */
public interface Storage extends AutoCloseable {
    ShowStore shows();

    BookingStore bookings();

    DiscountStore discounts();

    BookingLocks locks();

    Transactions transactions();

    /** The outbox kept by this storage, so its entries are written in the same transactions as bookings. */
    Outbox outbox(List<BookingListener> listeners, RetryPolicy retry, Duration sentRetention, Clock clock);

    /** Releases what the stores hold (e.g. pooled database connections). Nothing to do by default. */
    @Override
    default void close() {}
}
