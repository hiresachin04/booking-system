package booking.pricing;

import booking.model.Seat;
import booking.model.Show;
import booking.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceQuoteBuilderTest {
    // Thursday 20:00 in Kolkata.
    private static final Instant WEEKDAY = Instant.parse("2026-10-01T14:30:00Z");
    // Saturday 01:00 in Kolkata, still Friday in UTC.
    private static final Instant SATURDAY_1AM_IST = Instant.parse("2026-10-02T19:30:00Z");
    // Monday 00:30 in Kolkata, still Sunday in UTC.
    private static final Instant MONDAY_0030_IST = Instant.parse("2026-10-04T19:00:00Z");

    private static List<Seat> seats(Show show, String... ids) {
        return Arrays.stream(ids).map(id -> show.screen().seat(id).orElseThrow()).toList();
    }

    @Test
    void basePriceSumsEachSeatAtItsTierPrice() {
        Show show = Fixtures.show("sh", WEEKDAY);
        PriceComponent price = new PriceQuoteBuilder(show, seats(show, "A1", "B1")).weekendSurchargeBps(1_000).build();
        assertEquals(Fixtures.REGULAR_PAISE + Fixtures.PREMIUM_PAISE, price.totalPaise());
        assertEquals(List.of(new PriceLine("Seat A1 (REGULAR)", 20_000), new PriceLine("Seat B1 (PREMIUM)", 35_000)), price.lines());
    }

    @Test
    void appliesWeekendSurchargeByTheaterLocalDateNotUtc() {
        Show show = Fixtures.show("sh", SATURDAY_1AM_IST);
        PriceComponent price = new PriceQuoteBuilder(show, seats(show, "A1")).weekendSurchargeBps(1_000).build();
        assertEquals(22_000, price.totalPaise());
        assertEquals(new PriceLine("Weekend surcharge", 2_000), price.lines().getLast());
    }

    @Test
    void skipsWeekendSurchargeWhenUtcSaysSundayButTheaterSaysMonday() {
        Show show = Fixtures.show("sh", MONDAY_0030_IST);
        PriceComponent price = new PriceQuoteBuilder(show, seats(show, "A1")).weekendSurchargeBps(1_000).build();
        assertEquals(20_000, price.totalPaise());
    }

    @Test
    void capsPercentDiscount() {
        Show show = Fixtures.show("sh", WEEKDAY);
        PriceComponent price = new PriceQuoteBuilder(show, seats(show, "A1", "B1"))
                .discount(new Discount.Percent(5_000, 10_000)).build();
        assertEquals(55_000 - 10_000, price.totalPaise());
    }

    @Test
    void percentDiscountBelowCapTakesFullPercentage() {
        Show show = Fixtures.show("sh", WEEKDAY);
        PriceComponent price = new PriceQuoteBuilder(show, seats(show, "A1")).discount(new Discount.Percent(1_000, 10_000)).build();
        assertEquals(18_000, price.totalPaise());
    }

    @Test
    void flatDiscountNeverTakesTotalBelowZero() {
        Show show = Fixtures.show("sh", WEEKDAY);
        PriceComponent price = new PriceQuoteBuilder(show, seats(show, "A1")).discount(new Discount.Flat(1_000_000)).build();
        assertEquals(0, price.totalPaise());
        assertEquals(new PriceLine("Discount", -20_000), price.lines().getLast());
    }

    @Test
    void appliesSurchargeBeforeDiscountWhateverOrderTheSettersAreCalledIn() {
        Show show = Fixtures.show("sh", SATURDAY_1AM_IST);
        PriceComponent price = new PriceQuoteBuilder(show, seats(show, "A1", "A2"))
                .discount(new Discount.Flat(5_000))
                .weekendSurchargeBps(1_000)
                .build();
        // Surcharge first: 40000 + 4000 - 5000. Discount first would be (40000 - 5000) + 3500 = 38500.
        assertEquals(39_000, price.totalPaise());
        assertEquals(List.of("Seat A1 (REGULAR)", "Seat A2 (REGULAR)", "Weekend surcharge", "Discount"),
                price.lines().stream().map(PriceLine::label).toList());
    }
}
