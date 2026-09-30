package booking.booking;

import booking.model.User;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BookingRepository {
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();

    void save(Booking booking) {
        if (bookings.putIfAbsent(booking.id(), booking) != null) {
            throw new IllegalStateException("booking already saved: " + booking.id());
        }
    }

    public Optional<Booking> find(String bookingId) {
        return Optional.ofNullable(bookings.get(bookingId));
    }

    public List<Booking> findByStatus(BookingStatus status) {
        return bookings.values().stream().filter(b -> b.status() == status).toList();
    }

    public List<Booking> findByUser(User user) {
        return bookings.values().stream().filter(b -> b.user().equals(user)).toList();
    }
}
