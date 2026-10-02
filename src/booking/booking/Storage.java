package booking.booking;

import booking.discount.DiscountRegistry;

/** One consistent set of stores for a BookingService; StorageFactory picks in-memory or PostgreSQL. */
public interface Storage {
    ShowStore shows();

    BookingStore bookings();

    DiscountRegistry discounts();

    BookingLocks locks();
}
