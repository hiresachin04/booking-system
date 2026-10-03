package booking.api;

import booking.booking.Booking;
import booking.inventory.SeatInventory;
import booking.model.Seat;
import booking.model.Show;
import booking.pricing.PriceLine;
import org.json.JSONArray;
import org.json.JSONObject;

/** The API's JSON shapes. Only what a client needs: no internal retry state, never a key secret. */
final class ApiJson {
    private ApiJson() {}

    static JSONObject show(SeatInventory inventory) {
        Show show = inventory.show();
        JSONArray seats = new JSONArray();
        for (Seat seat : show.screen().seats()) {
            seats.put(new JSONObject().put("id", seat.id()).put("row", seat.row()).put("number", seat.number())
                    .put("tier", seat.tier().name()).put("pricePaise", show.priceOf(seat))
                    .put("status", inventory.status(seat.id()).name()));
        }
        return new JSONObject()
                .put("id", show.id())
                .put("movie", new JSONObject().put("title", show.movie().title()).put("runtimeMinutes", show.movie().runtime().toMinutes()))
                .put("theater", new JSONObject().put("name", show.screen().theater().name()).put("city", show.screen().theater().city().name())
                        .put("zone", show.screen().theater().zone().getId()))
                .put("startsAt", show.startsAt().toString())
                .put("seats", seats);
    }

    static JSONObject booking(Booking booking) {
        JSONArray lines = new JSONArray();
        for (PriceLine line : booking.price().lines()) lines.put(new JSONObject().put("label", line.label()).put("amountPaise", line.amountPaise()));
        return new JSONObject()
                .put("id", booking.id())
                .put("status", booking.status().name())
                .put("showId", booking.show().id())
                .put("seatIds", new JSONArray(booking.seatIds()))
                .put("price", new JSONObject().put("lines", lines).put("totalPaise", booking.price().totalPaise()))
                .put("holdExpiresAt", booking.holdExpiresAt().toString())
                .put("discountCode", booking.discountCode().<Object>map(code -> code).orElse(JSONObject.NULL))
                .put("refundedPaise", booking.refundedPaise())
                .put("checkout", new JSONObject().put("orderId", booking.checkout().orderId())
                        .put("amountPaise", booking.checkout().amountPaise()).put("currency", booking.checkout().currency())
                        .put("keyId", booking.checkout().publicKeyId()));
    }

    static JSONObject error(String code, String message) {
        return new JSONObject().put("error", code).put("message", message);
    }
}
