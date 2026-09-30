package booking.booking;

import booking.inventory.ShowInventory;
import booking.model.Show;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ShowRepository {
    private final Clock clock;
    private final Map<String, ShowInventory> inventories = new ConcurrentHashMap<>();

    public ShowRepository(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ShowInventory add(Show show) {
        ShowInventory inventory = new ShowInventory(show, clock);
        if (inventories.putIfAbsent(show.id(), inventory) != null) {
            throw new IllegalArgumentException("show already exists: " + show.id());
        }
        return inventory;
    }

    public Optional<ShowInventory> find(String showId) {
        return Optional.ofNullable(inventories.get(showId));
    }
}
