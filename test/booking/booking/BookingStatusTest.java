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
    void heldCanOnlyBeConfirmedReleasedExpiredOrGoPending() {
        assertEquals(EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.RELEASED, BookingStatus.EXPIRED, BookingStatus.PAYMENT_PENDING),
                reachableFrom(BookingStatus.HELD));
    }

    @Test
    void pendingPaymentCanBeSettledOrGiveUpItsSeats() {
        assertEquals(EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.PAYMENT_FAILED, BookingStatus.PAYMENT_UNRESOLVED),
                reachableFrom(BookingStatus.PAYMENT_PENDING));
    }

    @Test
    void unresolvedPaymentCanNeverBecomeConfirmedBecauseItsSeatsAreGone() {
        assertEquals(EnumSet.of(BookingStatus.PAYMENT_FAILED, BookingStatus.PAYMENT_REFUNDED), reachableFrom(BookingStatus.PAYMENT_UNRESOLVED));
    }

    @Test
    void confirmedCanOnlyBeCancelled() {
        assertEquals(EnumSet.of(BookingStatus.CANCELLED), reachableFrom(BookingStatus.CONFIRMED));
    }

    @Test
    void terminalStatesGoNowhere() {
        for (BookingStatus terminal : EnumSet.of(BookingStatus.RELEASED, BookingStatus.EXPIRED, BookingStatus.PAYMENT_FAILED,
                BookingStatus.PAYMENT_REFUNDED, BookingStatus.CANCELLED)) {
            assertEquals(EnumSet.noneOf(BookingStatus.class), reachableFrom(terminal), terminal.name());
        }
    }
}
