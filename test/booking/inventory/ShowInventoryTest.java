package booking.inventory;

import booking.support.Fixtures;
import booking.support.MutableClock;
import booking.support.Race;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShowInventoryTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int RACERS = 20;
    private static final int RACE_ROUNDS = 200;

    private final MutableClock clock = new MutableClock(NOW);
    private final ShowInventory inventory = newInventory();

    private ShowInventory newInventory() {
        return new ShowInventory(Fixtures.show("sh", NOW.plus(Duration.ofHours(8))), clock);
    }

    private Instant expiry() {
        return clock.instant().plus(TTL);
    }

    @Test
    void rejectsSecondHoldOnAHeldSeat() {
        inventory.hold("b1", List.of("A1"), expiry());
        SeatsUnavailableException e = assertThrows(SeatsUnavailableException.class, () -> inventory.hold("b2", List.of("A1"), expiry()));
        assertEquals(List.of("A1"), e.seatIds());
    }

    @Test
    void rejectsHoldOnABookedSeatEvenAfterTheHoldWouldHaveExpired() {
        inventory.hold("b1", List.of("A1"), expiry());
        inventory.confirm("b1", List.of("A1"));
        clock.advance(TTL.multipliedBy(2));
        assertThrows(SeatsUnavailableException.class, () -> inventory.hold("b2", List.of("A1"), expiry()));
    }

    @Test
    void partialOverlapLeavesTheLoserHoldingNothing() {
        inventory.hold("b1", List.of("A2"), expiry());
        SeatsUnavailableException e = assertThrows(SeatsUnavailableException.class,
                () -> inventory.hold("b2", List.of("A1", "A2"), expiry()));
        assertEquals(List.of("A2"), e.seatIds());
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
    }

    @Test
    void seatIsStillHeldOneNanosecondBeforeExpiry() {
        inventory.hold("b1", List.of("A1"), expiry());
        clock.advance(TTL.minusNanos(1));
        assertThrows(SeatsUnavailableException.class, () -> inventory.hold("b2", List.of("A1"), expiry()));
    }

    @Test
    void seatCanBeReheldOnceTheHoldExpires() {
        inventory.hold("b1", List.of("A1"), expiry());
        clock.advance(TTL);
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
        inventory.hold("b2", List.of("A1"), expiry());
        inventory.confirm("b2", List.of("A1"));
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void rejectsConfirmAfterHoldExpires() {
        inventory.hold("b1", List.of("A1"), expiry());
        clock.advance(TTL);
        assertThrows(HoldExpiredException.class, () -> inventory.confirm("b1", List.of("A1")));
    }

    @Test
    void rejectsConfirmByADifferentBooking() {
        inventory.hold("b1", List.of("A1"), expiry());
        assertThrows(HoldExpiredException.class, () -> inventory.confirm("b2", List.of("A1")));
        assertEquals(SeatStatus.HELD, inventory.status("A1"));
    }

    @Test
    void confirmIsAllOrNothingWhenOneSeatWasLost() {
        inventory.hold("b1", List.of("A1", "A2"), expiry());
        clock.advance(TTL);
        inventory.hold("b2", List.of("A2"), expiry());
        assertThrows(HoldExpiredException.class, () -> inventory.confirm("b1", List.of("A1", "A2")));
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
    }

    @Test
    void releaseLeavesASeatThatSomeoneElseReheldAfterExpiry() {
        inventory.hold("b1", List.of("A1"), expiry());
        clock.advance(TTL);
        inventory.hold("b2", List.of("A1"), expiry());
        inventory.release("b1", List.of("A1"));
        inventory.confirm("b2", List.of("A1"));
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void cancelFreesBookedSeats() {
        inventory.hold("b1", List.of("A1"), expiry());
        inventory.confirm("b1", List.of("A1"));
        inventory.cancel("b1", List.of("A1"));
        inventory.hold("b2", List.of("A1"), expiry());
    }

    @Test
    void rejectsCancelOfSeatsBookedBySomeoneElse() {
        inventory.hold("b1", List.of("A1"), expiry());
        inventory.confirm("b1", List.of("A1"));
        assertThrows(IllegalStateException.class, () -> inventory.cancel("b2", List.of("A1")));
        assertEquals(SeatStatus.BOOKED, inventory.status("A1"));
    }

    @Test
    void rejectsUnknownSeat() {
        assertThrows(IllegalArgumentException.class, () -> inventory.hold("b1", List.of("Z9"), expiry()));
    }

    @Test
    void rejectsTheSameSeatTwiceInOneRequest() {
        assertThrows(IllegalArgumentException.class, () -> inventory.hold("b1", List.of("A1", "A1"), expiry()));
        assertEquals(SeatStatus.AVAILABLE, inventory.status("A1"));
    }

    @Test
    void exactlyOneOfManyConcurrentRacersGetsTheSameSeat() {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            ShowInventory fresh = newInventory();
            List<Boolean> outcomes = Race.run(RACERS, i -> {
                try {
                    fresh.hold("b" + i, List.of("A1"), expiry());
                    return true;
                } catch (SeatsUnavailableException e) {
                    return false;
                }
            });
            assertEquals(1, Race.winners(outcomes), "round " + round);
        }
    }

    @Test
    void overlappingMultiSeatRacesNeverGiveOneSeatToTwoBookings() {
        List<List<String>> requests = List.of(List.of("A1", "A2"), List.of("A2", "A3"), List.of("A3", "A4"), List.of("A4", "A1"));
        for (int round = 0; round < RACE_ROUNDS; round++) {
            ShowInventory fresh = newInventory();
            List<Boolean> outcomes = Race.run(RACERS, i -> {
                try {
                    fresh.hold("b" + i, requests.get(i % requests.size()), expiry());
                    return true;
                } catch (SeatsUnavailableException e) {
                    return false;
                }
            });
            Set<String> granted = new HashSet<>();
            for (int i = 0; i < outcomes.size(); i++) {
                if (!outcomes.get(i)) continue;
                for (String seat : requests.get(i % requests.size())) {
                    assertTrue(granted.add(seat), "seat " + seat + " granted twice in round " + round);
                }
                fresh.confirm("b" + i, requests.get(i % requests.size()));
            }
            assertFalse(granted.isEmpty(), "round " + round + ": nobody got a seat");
        }
    }
}
