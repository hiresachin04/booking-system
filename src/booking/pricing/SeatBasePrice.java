package booking.pricing;

import booking.model.Seat;
import booking.model.Show;

import java.util.ArrayList;
import java.util.List;

final class SeatBasePrice implements PriceComponent {
    private final List<PriceLine> lines;
    private final long totalPaise;

    SeatBasePrice(Show show, List<Seat> seats) {
        if (seats.isEmpty()) throw new IllegalArgumentException("no seats to price");
        List<PriceLine> seatLines = new ArrayList<>(seats.size());
        long total = 0;
        for (Seat seat : seats) {
            long price = show.priceOf(seat);
            seatLines.add(new PriceLine("Seat " + seat.id() + " (" + seat.tier() + ")", price));
            total = Math.addExact(total, price);
        }
        this.lines = List.copyOf(seatLines);
        this.totalPaise = total;
    }

    @Override
    public long totalPaise() {
        return totalPaise;
    }

    @Override
    public List<PriceLine> lines() {
        return lines;
    }
}
