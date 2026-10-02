package booking.booking;

import booking.model.User;

import java.util.List;
import java.util.Optional;

/**
 * Bookings by id. Callers change a {@link Booking} and then {@link #update} it while holding its
 * {@link BookingLocks lock}; an implementation may hand out a fresh copy on every {@link #find}.
 */
public interface BookingStore {
    void save(Booking booking);

    void update(Booking booking);

    Optional<Booking> find(String bookingId);

    List<Booking> findByUser(User user);

    List<Booking> findByStatus(BookingStatus status);
}
