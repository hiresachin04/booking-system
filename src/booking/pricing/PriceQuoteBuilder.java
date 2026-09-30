package booking.pricing;

import booking.model.Seat;
import booking.model.Show;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Objects;

/**
 * The only way to assemble a price. Decorator order changes the total (a discount taken before a surcharge
 * shrinks the surcharge), so {@link #build()} always stacks base → weekend surcharge → discount,
 * whatever order the setters were called in.
 */
public final class PriceQuoteBuilder {
    private final Show show;
    private final List<Seat> seats;
    private int weekendSurchargeBps;
    private Discount discount;

    public PriceQuoteBuilder(Show show, List<Seat> seats) {
        this.show = Objects.requireNonNull(show, "show");
        this.seats = List.copyOf(seats);
    }

    public PriceQuoteBuilder weekendSurchargeBps(int bps) {
        if (bps < 0) throw new IllegalArgumentException("weekend surcharge must not be negative: " + bps);
        this.weekendSurchargeBps = bps;
        return this;
    }

    public PriceQuoteBuilder discount(Discount discount) {
        this.discount = Objects.requireNonNull(discount, "discount");
        return this;
    }

    public PriceComponent build() {
        PriceComponent price = new SeatBasePrice(show, seats);
        if (weekendSurchargeBps > 0 && isWeekendShow()) {
            price = new WeekendSurcharge(price, weekendSurchargeBps);
        }
        if (discount != null) {
            price = switch (discount) {
                case Discount.Percent percent -> new PercentDiscount(price, percent);
                case Discount.Flat flat -> new FlatDiscount(price, flat);
            };
        }
        return price;
    }

    // Local date in the theater's zone: a Saturday 01:00 show in Kolkata is still Friday in UTC.
    private boolean isWeekendShow() {
        DayOfWeek day = show.startsAt().atZone(show.screen().theater().zone()).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
