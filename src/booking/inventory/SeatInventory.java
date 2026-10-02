package booking.inventory;

import booking.model.Show;

import java.time.Instant;
import java.util.List;

/**
 * Seat state for one show. Every implementation must make {@link #hold} all-or-nothing and must never let two
 * bookings own the same seat, however many callers race. A HELD seat past its expiry counts as free.
 */
public interface SeatInventory {
    Show show();

    void hold(String bookingId, List<String> seatIds, Instant expiresAt);

    /** Converts this booking's live hold to BOOKED, or throws {@link HoldExpiredException} without changing anything. */
    void confirm(String bookingId, List<String> seatIds);

    /** Frees seats still held by this booking; seats re-held by someone else after expiry are left alone. */
    void release(String bookingId, List<String> seatIds);

    void cancel(String bookingId, List<String> seatIds);

    SeatStatus status(String seatId);
}
