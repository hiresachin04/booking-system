package booking.model;

import booking.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelValidationTest {
    private static final Movie MOVIE = new Movie("m1", "Dune", Duration.ofMinutes(155));
    private static final Instant START = Instant.parse("2026-10-01T14:30:00Z");

    @Test
    void rejectsShowWithNoPriceForATierOnItsScreen() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Show("sh1", MOVIE, Fixtures.screen(), START, Map.of(SeatTier.REGULAR, 20_000L)));
        assertEquals("show sh1 has no price for tier PREMIUM", e.getMessage());
    }

    @Test
    void rejectsZeroPrice() {
        assertThrows(IllegalArgumentException.class,
                () -> new Show("sh1", MOVIE, Fixtures.screen(), START, Map.of(SeatTier.REGULAR, 0L, SeatTier.PREMIUM, 1L)));
    }

    @Test
    void rejectsScreenWithDuplicateSeat() {
        Theater theater = Fixtures.screen().theater();
        List<Seat> seats = List.of(new Seat("A", 1, SeatTier.REGULAR), new Seat("A", 1, SeatTier.PREMIUM));
        assertThrows(IllegalArgumentException.class, () -> new Screen("s", theater, seats));
    }

    @Test
    void rejectsRowWithDigitsBecauseSeatIdsWouldCollide() {
        assertThrows(IllegalArgumentException.class, () -> new Seat("A1", 1, SeatTier.REGULAR));
    }

    @Test
    void rejectsNonPositiveSeatNumber() {
        assertThrows(IllegalArgumentException.class, () -> new Seat("A", 0, SeatTier.REGULAR));
    }

    @Test
    void basisPointsRoundHalfUpNotHalfEven() {
        assertEquals(3, Money.basisPoints(25, 1_000));
        assertEquals(2, Money.basisPoints(24, 1_000));
        assertEquals(4, Money.basisPoints(35, 1_000));
    }

    @Test
    void basisPointsThrowOnOverflowInsteadOfWrapping() {
        assertThrows(ArithmeticException.class, () -> Money.basisPoints(Long.MAX_VALUE / 2, 10_000));
    }
}
