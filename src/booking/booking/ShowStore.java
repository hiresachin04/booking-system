package booking.booking;

import booking.inventory.SeatInventory;
import booking.model.Show;

import java.util.Optional;

public interface ShowStore {
    SeatInventory add(Show show);

    Optional<SeatInventory> find(String showId);
}
