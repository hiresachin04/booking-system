package booking.db;

import booking.model.City;
import booking.model.Movie;
import booking.model.Screen;
import booking.model.Seat;
import booking.model.SeatTier;
import booking.model.Show;
import booking.model.Theater;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A Show and everything it contains as one JSON document, for the shows.definition column. */
public final class ShowJson {
    private ShowJson() {}

    public static JSONObject encode(Show show) {
        Theater theater = show.screen().theater();
        JSONArray seats = new JSONArray();
        for (Seat seat : show.screen().seats()) {
            seats.put(new JSONObject().put("row", seat.row()).put("number", seat.number()).put("tier", seat.tier().name()));
        }
        JSONObject prices = new JSONObject();
        show.tierPricePaise().forEach((tier, paise) -> prices.put(tier.name(), paise));
        return new JSONObject()
                .put("id", show.id())
                .put("movie", new JSONObject().put("id", show.movie().id()).put("title", show.movie().title())
                        .put("runtime", show.movie().runtime().toString()))
                .put("screen", new JSONObject().put("id", show.screen().id()).put("seats", seats)
                        .put("theater", new JSONObject().put("id", theater.id()).put("name", theater.name())
                                .put("zone", theater.zone().getId())
                                .put("city", new JSONObject().put("id", theater.city().id()).put("name", theater.city().name()))))
                .put("startsAt", EpochNanos.of(show.startsAt()))
                .put("tierPricePaise", prices);
    }

    public static Show decode(String json) {
        JSONObject show = new JSONObject(json);
        JSONObject movie = show.getJSONObject("movie");
        JSONObject screen = show.getJSONObject("screen");
        JSONObject theater = screen.getJSONObject("theater");
        JSONObject city = theater.getJSONObject("city");
        List<Seat> seats = new ArrayList<>();
        JSONArray seatArray = screen.getJSONArray("seats");
        for (int i = 0; i < seatArray.length(); i++) {
            JSONObject seat = seatArray.getJSONObject(i);
            seats.add(new Seat(seat.getString("row"), seat.getInt("number"), SeatTier.valueOf(seat.getString("tier"))));
        }
        Map<SeatTier, Long> prices = new EnumMap<>(SeatTier.class);
        JSONObject priceObject = show.getJSONObject("tierPricePaise");
        for (String tier : priceObject.keySet()) prices.put(SeatTier.valueOf(tier), priceObject.getLong(tier));
        return new Show(show.getString("id"),
                new Movie(movie.getString("id"), movie.getString("title"), Duration.parse(movie.getString("runtime"))),
                new Screen(screen.getString("id"),
                        new Theater(theater.getString("id"), theater.getString("name"),
                                new City(city.getString("id"), city.getString("name")), ZoneId.of(theater.getString("zone"))),
                        seats),
                EpochNanos.toInstant(show.getLong("startsAt")), prices);
    }
}
