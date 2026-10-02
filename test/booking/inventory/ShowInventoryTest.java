package booking.inventory;

import booking.model.Show;

/** The in-memory inventory against the SeatInventory contract. */
class ShowInventoryTest extends SeatInventoryContract {
    @Override
    protected SeatInventory inventoryFor(Show show) {
        return new ShowInventory(show, clock);
    }

    @Override
    protected int raceRounds() {
        return 200;
    }
}
