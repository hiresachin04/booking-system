package booking.booking;

import booking.discount.DiscountStore;

/** One consistent set of stores for a BookingService; StorageFactory picks in-memory or PostgreSQL. */
public interface Storage {
    ShowStore shows();

    BookingStore bookings();

    DiscountStore discounts();

    BookingLocks locks();
}
