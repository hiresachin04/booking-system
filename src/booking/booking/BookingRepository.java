package booking.booking;

import booking.model.User;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BookingRepository implements BookingStore {
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();

    @Override
    public void save(Booking booking) {
        if (bookings.putIfAbsent(booking.id(), booking) != null) {
            throw new IllegalStateException("booking already saved: " + booking.id());
        }
    }

    /** Nothing to write: callers changed the one shared instance. Rejects any other copy, which would be a bug. */
    @Override
    public void update(Booking booking) {
        if (bookings.get(booking.id()) != booking) throw new IllegalStateException("not the stored instance of booking " + booking.id());
    }

    @Override
    public Optional<Booking> find(String bookingId) {
        return Optional.ofNullable(bookings.get(bookingId));
    }

    @Override
    public List<Booking> findByStatus(BookingStatus status) {
        return bookings.values().stream().filter(b -> b.status() == status).toList();
    }

    @Override
    public List<Booking> findByUser(User user) {
        return bookings.values().stream().filter(b -> b.user().equals(user)).toList();
    }
}
