package booking.app;

import booking.booking.ShowStore;
import booking.discount.DiscountCode;
import booking.discount.DiscountStore;
import booking.model.City;
import booking.model.Movie;
import booking.model.Screen;
import booking.model.Seat;
import booking.model.SeatTier;
import booking.model.Show;
import booking.model.Theater;
import booking.pricing.Discount;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The demo's one show and discount code, standing in for an admin API that doesn't exist yet. Seeding is
 * idempotent, so restarting on PostgreSQL keeps the show and its bookings rather than failing on a duplicate.
 */
public final class DemoCatalog {
    public static final String DISCOUNT_CODE = "WELCOME50";
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SHOWTIME = LocalTime.of(19, 30);

    private DemoCatalog() {}

    /** Ensures tomorrow evening's show (Kolkata time) and the discount code exist; returns the show's id. */
    public static String seed(ShowStore shows, DiscountStore discounts, Clock clock) {
        LocalDate tomorrow = LocalDate.ofInstant(clock.instant(), KOLKATA).plusDays(1);
        String showId = "demo-" + tomorrow;
        if (shows.find(showId).isEmpty()) shows.add(show(showId, tomorrow));
        if (discounts.find(DISCOUNT_CODE).isEmpty()) {
            discounts.add(new DiscountCode(DISCOUNT_CODE, new Discount.Flat(5_000), clock.instant().minus(Duration.ofDays(1)),
                    clock.instant().plus(Duration.ofDays(30)), 0, 100));
        }
        return showId;
    }

    private static Show show(String id, LocalDate date) {
        List<Seat> seats = new ArrayList<>();
        for (String row : List.of("A", "B", "C", "D", "E")) {
            SeatTier tier = row.compareTo("D") < 0 ? SeatTier.REGULAR : SeatTier.PREMIUM;
            for (int number = 1; number <= 10; number++) seats.add(new Seat(row, number, tier));
        }
        Theater theater = new Theater("pvr-forum", "PVR Forum", new City("blr", "Bengaluru"), KOLKATA);
        return new Show(id, new Movie("dune-2", "Dune: Part Two", Duration.ofMinutes(166)), new Screen("screen-1", theater, seats),
                date.atTime(SHOWTIME).atZone(KOLKATA).toInstant(), Map.of(SeatTier.REGULAR, 20_000L, SeatTier.PREMIUM, 35_000L));
    }
}
