package booking.discount;

import booking.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The PostgreSQL store against the same contract, in a throwaway schema. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class PostgresDiscountRegistryTest extends DiscountStoreContract {
    private final PostgresTestDatabase test = PostgresTestDatabase.create();

    @AfterEach
    void drop() {
        test.close();
    }

    @Override
    protected DiscountStore newStore() {
        return new PostgresDiscountRegistry(test.database());
    }
}
