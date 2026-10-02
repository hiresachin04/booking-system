package booking.discount;

import booking.pricing.Discount;
import booking.support.Race;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every DiscountStore must guarantee, above all that racing confirms never exceed a code's usage limit. */
abstract class DiscountStoreContract {
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant UNTIL = FROM.plus(Duration.ofDays(7));

    private DiscountStore store;

    protected abstract DiscountStore newStore();

    @BeforeEach
    void freshStore() {
        store = newStore();
    }

    private DiscountCode code(String code, Discount discount, int limit) {
        DiscountCode dc = new DiscountCode(code, discount, FROM, UNTIL, 1_500, limit);
        store.add(dc);
        return dc;
    }

    @Test
    void findReturnsEveryFieldItWasAddedWith() {
        code("PCT", new Discount.Percent(1_500, 10_000), 3);
        code("UNCAPPED", Discount.Percent.uncapped(500), 3);
        code("FLAT", new Discount.Flat(5_000), 3);

        DiscountCode pct = store.find("PCT").orElseThrow();
        assertEquals(new Discount.Percent(1_500, 10_000), pct.discount());
        assertEquals(FROM, pct.validFrom());
        assertEquals(UNTIL, pct.validUntil());
        assertEquals(1_500, pct.minOrderPaise());
        assertEquals(3, pct.usageLimit());
        assertEquals(0, pct.used());
        assertEquals(Discount.Percent.uncapped(500), store.find("UNCAPPED").orElseThrow().discount());
        assertEquals(new Discount.Flat(5_000), store.find("FLAT").orElseThrow().discount());
    }

    @Test
    void unknownCodeIsAbsent() {
        assertTrue(store.find("NOPE").isEmpty());
    }

    @Test
    void rejectsADuplicateCode() {
        code("FEST", new Discount.Flat(5_000), 3);
        assertThrows(IllegalArgumentException.class, () -> code("FEST", new Discount.Flat(1), 1));
    }

    @Test
    void consumesUpToTheLimitAndNoFurther() {
        code("FEST", new Discount.Flat(5_000), 2);

        assertTrue(store.tryConsume("FEST", FROM));
        assertTrue(store.tryConsume("FEST", FROM));
        assertFalse(store.tryConsume("FEST", FROM));
        assertEquals(2, store.find("FEST").orElseThrow().used());
    }

    @Test
    void isValidFromItsStartAndInvalidAtItsEnd() {
        code("FEST", new Discount.Flat(5_000), 5);

        assertFalse(store.tryConsume("FEST", FROM.minusNanos(1)));
        assertTrue(store.tryConsume("FEST", FROM));
        assertTrue(store.tryConsume("FEST", UNTIL.minusNanos(1)));
        assertFalse(store.tryConsume("FEST", UNTIL));
        assertEquals(2, store.find("FEST").orElseThrow().used());
    }

    @Test
    void refusesToConsumeAnUnknownCode() {
        assertFalse(store.tryConsume("NOPE", FROM));
    }

    @Test
    void releasedUseCanBeTakenAgain() {
        code("FEST", new Discount.Flat(5_000), 1);
        assertTrue(store.tryConsume("FEST", FROM));

        store.releaseUse("FEST");

        assertEquals(0, store.find("FEST").orElseThrow().used());
        assertTrue(store.tryConsume("FEST", FROM));
    }

    @Test
    void refusesToReleaseAUseThatWasNeverTaken() {
        code("FEST", new Discount.Flat(5_000), 1);
        assertThrows(IllegalStateException.class, () -> store.releaseUse("FEST"));
        assertEquals(0, store.find("FEST").orElseThrow().used());
    }

    @Test
    void refusesToReleaseAUseOfAnUnknownCode() {
        assertThrows(IllegalStateException.class, () -> store.releaseUse("NOPE"));
    }

    @Test
    void racingConsumersNeverExceedTheLimit() {
        code("LAST5", new Discount.Flat(5_000), 5);

        List<Boolean> outcomes = Race.run(20, i -> store.tryConsume("LAST5", FROM));

        assertEquals(5, Race.winners(outcomes));
        assertEquals(5, store.find("LAST5").orElseThrow().used());
    }
}
