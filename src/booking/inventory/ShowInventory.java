package booking.inventory;

import booking.model.Seat;
import booking.model.Show;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Seat state for one show. Every read and write goes through one lock per show: a multi-seat hold must be
 * all-or-nothing, and per-seat locks would need globally ordered acquisition to do that without deadlock.
 * Expiry is evaluated on read, so a stale HELD slot never blocks anyone and no sweeper is needed.
 */
public final class ShowInventory implements SeatInventory {
    private sealed interface Slot {}

    private record Available() implements Slot {}

    private record Held(String bookingId, Instant expiresAt) implements Slot {}

    private record Booked(String bookingId) implements Slot {}

    private static final Slot AVAILABLE = new Available();

    private final Show show;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Slot> slots = new HashMap<>();
    private final Set<String> seatIds;

    public ShowInventory(Show show, Clock clock) {
        this.show = Objects.requireNonNull(show, "show");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (Seat seat : show.screen().seats()) slots.put(seat.id(), AVAILABLE);
        this.seatIds = Set.copyOf(slots.keySet());
    }

    public Show show() {
        return show;
    }

    public void hold(String bookingId, List<String> seatIds, Instant expiresAt) {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireKnownDistinct(seatIds);
        lock.lock();
        try {
            Instant now = clock.instant();
            if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("hold would already be expired: " + expiresAt);
            List<String> taken = new ArrayList<>();
            for (String seatId : seatIds) {
                if (!isFree(slots.get(seatId), now)) taken.add(seatId);
            }
            if (!taken.isEmpty()) throw new SeatsUnavailableException(taken);
            Slot held = new Held(bookingId, expiresAt);
            for (String seatId : seatIds) slots.put(seatId, held);
        } finally {
            lock.unlock();
        }
    }

    /** Converts this booking's live hold to BOOKED, or throws without changing anything. */
    public void confirm(String bookingId, List<String> seatIds) {
        requireKnownDistinct(seatIds);
        lock.lock();
        try {
            Instant now = clock.instant();
            for (String seatId : seatIds) {
                if (!(slots.get(seatId) instanceof Held h && h.bookingId().equals(bookingId) && now.isBefore(h.expiresAt()))) {
                    throw new HoldExpiredException(bookingId);
                }
            }
            Slot booked = new Booked(bookingId);
            for (String seatId : seatIds) slots.put(seatId, booked);
        } finally {
            lock.unlock();
        }
    }

    /** Frees seats still held by this booking; seats re-held by someone else after expiry are left alone. */
    public void release(String bookingId, List<String> seatIds) {
        requireKnownDistinct(seatIds);
        lock.lock();
        try {
            for (String seatId : seatIds) {
                if (slots.get(seatId) instanceof Held h && h.bookingId().equals(bookingId)) slots.put(seatId, AVAILABLE);
            }
        } finally {
            lock.unlock();
        }
    }

    public void cancel(String bookingId, List<String> seatIds) {
        requireKnownDistinct(seatIds);
        lock.lock();
        try {
            for (String seatId : seatIds) {
                if (!(slots.get(seatId) instanceof Booked b && b.bookingId().equals(bookingId))) {
                    throw new IllegalStateException("seat " + seatId + " is not booked by " + bookingId);
                }
            }
            for (String seatId : seatIds) slots.put(seatId, AVAILABLE);
        } finally {
            lock.unlock();
        }
    }

    public SeatStatus status(String seatId) {
        requireKnownDistinct(List.of(seatId));
        lock.lock();
        try {
            return switch (slots.get(seatId)) {
                case Available a -> SeatStatus.AVAILABLE;
                case Held h -> clock.instant().isBefore(h.expiresAt()) ? SeatStatus.HELD : SeatStatus.AVAILABLE;
                case Booked b -> SeatStatus.BOOKED;
            };
        } finally {
            lock.unlock();
        }
    }

    private static boolean isFree(Slot slot, Instant now) {
        return switch (slot) {
            case Available a -> true;
            case Held h -> !now.isBefore(h.expiresAt());
            case Booked b -> false;
        };
    }

    // Checks against the immutable seat set, not the map, so it is safe to call before taking the lock.
    private void requireKnownDistinct(List<String> requested) {
        if (requested.isEmpty()) throw new IllegalArgumentException("no seats given");
        Set<String> seen = new HashSet<>();
        for (String seatId : requested) {
            if (!seatIds.contains(seatId)) throw new IllegalArgumentException("seat " + seatId + " is not in show " + show.id());
            if (!seen.add(seatId)) throw new IllegalArgumentException("seat " + seatId + " requested twice");
        }
    }
}
