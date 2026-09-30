package booking.support;

import booking.model.City;
import booking.model.Movie;
import booking.model.Screen;
import booking.model.Seat;
import booking.model.SeatTier;
import booking.model.Show;
import booking.model.Theater;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Screen: A1–A8 REGULAR at 200.00, B1–B4 PREMIUM at 350.00, in a Kolkata theater. */
public final class Fixtures {
    public static final long REGULAR_PAISE = 20_000;
    public static final long PREMIUM_PAISE = 35_000;
    public static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private Fixtures() {}

    public static Screen screen() {
        List<Seat> seats = new ArrayList<>();
        for (int n = 1; n <= 8; n++) seats.add(new Seat("A", n, SeatTier.REGULAR));
        for (int n = 1; n <= 4; n++) seats.add(new Seat("B", n, SeatTier.PREMIUM));
        Theater theater = new Theater("t1", "PVR Forum", new City("blr", "Bengaluru"), KOLKATA);
        return new Screen("s1", theater, seats);
    }

    public static Show show(String id, Instant startsAt) {
        return new Show(id, new Movie("m1", "Dune", Duration.ofMinutes(155)), screen(), startsAt,
                Map.of(SeatTier.REGULAR, REGULAR_PAISE, SeatTier.PREMIUM, PREMIUM_PAISE));
    }
}
