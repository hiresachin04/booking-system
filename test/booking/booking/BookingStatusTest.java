package booking.booking;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookingStatusTest {
    private static Set<BookingStatus> reachableFrom(BookingStatus from) {
        Set<BookingStatus> reachable = EnumSet.noneOf(BookingStatus.class);
        for (BookingStatus to : BookingStatus.values()) {
            if (from.canTransitionTo(to)) reachable.add(to);
        }
        return reachable;
    }

    @Test
    void heldCanOnlyBeConfirmedReleasedOrExpired() {
        assertEquals(EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.RELEASED, BookingStatus.EXPIRED), reachableFrom(BookingStatus.HELD));
    }

    @Test
    void confirmedCanOnlyBeCancelled() {
        assertEquals(EnumSet.of(BookingStatus.CANCELLED), reachableFrom(BookingStatus.CONFIRMED));
    }

    @Test
    void terminalStatesGoNowhere() {
        for (BookingStatus terminal : EnumSet.of(BookingStatus.RELEASED, BookingStatus.EXPIRED, BookingStatus.CANCELLED)) {
            assertEquals(EnumSet.noneOf(BookingStatus.class), reachableFrom(terminal), terminal.name());
        }
    }
}
