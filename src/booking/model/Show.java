package booking.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record Show(String id, Movie movie, Screen screen, Instant startsAt, Map<SeatTier, Long> tierPricePaise) {
    public Show {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(movie, "movie");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(startsAt, "startsAt");
        tierPricePaise = Map.copyOf(tierPricePaise);
        for (Seat seat : screen.seats()) {
            if (!tierPricePaise.containsKey(seat.tier())) {
                throw new IllegalArgumentException("show " + id + " has no price for tier " + seat.tier());
            }
        }
        tierPricePaise.forEach((tier, price) -> {
            if (price <= 0) throw new IllegalArgumentException("price for " + tier + " must be positive: " + price);
        });
    }

    public long priceOf(Seat seat) {
        return tierPricePaise.get(seat.tier());
    }
}
