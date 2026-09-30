package booking.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record Screen(String id, Theater theater, List<Seat> seats) {
    public Screen {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(theater, "theater");
        seats = List.copyOf(seats);
        if (seats.isEmpty()) throw new IllegalArgumentException("screen " + id + " has no seats");
        Set<String> ids = new HashSet<>();
        for (Seat seat : seats) {
            if (!ids.add(seat.id())) throw new IllegalArgumentException("duplicate seat " + seat.id() + " on screen " + id);
        }
    }

    public Optional<Seat> seat(String seatId) {
        return seats.stream().filter(s -> s.id().equals(seatId)).findFirst();
    }
}
