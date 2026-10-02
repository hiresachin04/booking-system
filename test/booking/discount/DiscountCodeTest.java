package booking.discount;

import booking.pricing.Discount;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscountCodeTest {
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant UNTIL = FROM.plus(Duration.ofDays(7));

    private final DiscountCode code = new DiscountCode("FEST", new Discount.Flat(5_000), FROM, UNTIL, 0, 2);

    @Test
    void consumesUpToTheUsageLimitAndNoFurther() {
        assertTrue(code.tryConsume(FROM));
        assertTrue(code.tryConsume(FROM));
        assertFalse(code.tryConsume(FROM));
        assertEquals(2, code.used());
    }

    @Test
    void isValidFromItsStartInstant() {
        assertTrue(code.tryConsume(FROM));
    }

    @Test
    void isInvalidAtItsEndInstant() {
        assertFalse(code.tryConsume(UNTIL));
        assertThrows(DiscountRejectedException.class, () -> code.checkApplicable(10_000, UNTIL));
        assertEquals(0, code.used());
    }

    @Test
    void releasedUseCanBeConsumedAgain() {
        code.tryConsume(FROM);
        code.tryConsume(FROM);
        code.releaseUse();
        assertTrue(code.tryConsume(FROM));
    }

    @Test
    void rejectsReleasingAUseThatWasNeverTaken() {
        assertThrows(IllegalStateException.class, code::releaseUse);
        assertEquals(0, code.used());
    }

    @Test
    void registryRefusesToConsumeAnUnknownCode() {
        DiscountRegistry registry = new DiscountRegistry();
        assertFalse(registry.tryConsume("NOPE", FROM));
    }

    @Test
    void registryConsumesThroughTheCodesOwnCounter() {
        DiscountRegistry registry = new DiscountRegistry();
        registry.add(code);

        assertTrue(registry.tryConsume("FEST", FROM));
        assertTrue(registry.tryConsume("FEST", FROM));
        assertFalse(registry.tryConsume("FEST", FROM));
        registry.releaseUse("FEST");
        assertEquals(1, code.used());
    }

    @Test
    void registryRejectsReleasingAUseOfAnUnknownCode() {
        assertThrows(IllegalStateException.class, () -> new DiscountRegistry().releaseUse("NOPE"));
    }

    @Test
    void snapshotWithUsesAlreadyTakenStartsFromThatCount() {
        DiscountCode restored = new DiscountCode("FEST", new Discount.Flat(5_000), FROM, UNTIL, 0, 2, 2);

        assertEquals(2, restored.used());
        assertFalse(restored.tryConsume(FROM));
    }

    @Test
    void snapshotRejectsMoreUsesThanTheLimit() {
        assertThrows(IllegalArgumentException.class, () -> new DiscountCode("FEST", new Discount.Flat(5_000), FROM, UNTIL, 0, 2, 3));
    }

    @Test
    void registryRejectsDuplicateCode() {
        DiscountRegistry registry = new DiscountRegistry();
        registry.add(code);
        assertThrows(IllegalArgumentException.class,
                () -> registry.add(new DiscountCode("FEST", new Discount.Flat(1), FROM, UNTIL, 0, 1)));
    }
}
