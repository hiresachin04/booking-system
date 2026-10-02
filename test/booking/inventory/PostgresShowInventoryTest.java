package booking.inventory;

import booking.booking.PostgresShowRepository;
import booking.model.Show;
import booking.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The PostgreSQL inventory against the same contract, in a throwaway schema. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class PostgresShowInventoryTest extends SeatInventoryContract {
    private final PostgresTestDatabase test = PostgresTestDatabase.create();
    private final PostgresShowRepository shows = new PostgresShowRepository(test.database(), clock);

    @AfterEach
    void drop() {
        test.close();
    }

    @Override
    protected SeatInventory inventoryFor(Show show) {
        return shows.add(show);
    }

    // Each attempt is a database round trip; 20 rounds of 20 racers is still 400 contested holds per race test.
    @Override
    protected int raceRounds() {
        return 20;
    }
}
