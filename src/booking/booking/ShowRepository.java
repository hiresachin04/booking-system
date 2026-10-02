package booking.booking;

import booking.inventory.SeatInventory;
import booking.inventory.ShowInventory;
import booking.model.Show;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ShowRepository implements ShowStore {
    private final Clock clock;
    private final Map<String, ShowInventory> inventories = new ConcurrentHashMap<>();

    public ShowRepository(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ShowInventory add(Show show) {
        ShowInventory inventory = new ShowInventory(show, clock);
        if (inventories.putIfAbsent(show.id(), inventory) != null) {
            throw new IllegalArgumentException("show already exists: " + show.id());
        }
        return inventory;
    }

    @Override
    public Optional<SeatInventory> find(String showId) {
        return Optional.<SeatInventory>ofNullable(inventories.get(showId));
    }
}
