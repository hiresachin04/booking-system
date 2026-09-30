package booking.inventory;

import java.util.List;

public final class SeatsUnavailableException extends RuntimeException {
    private final List<String> seatIds;

    public SeatsUnavailableException(List<String> seatIds) {
        super("seats unavailable: " + seatIds);
        this.seatIds = List.copyOf(seatIds);
    }

    public List<String> seatIds() {
        return seatIds;
    }
}
