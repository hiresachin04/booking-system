package booking.db;

import booking.payment.CheckoutSession;
import booking.pricing.PriceComponent;
import booking.pricing.PriceLine;
import booking.pricing.RecordedPrice;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The JSON columns of the bookings table: the price breakdown and the checkout session. */
public final class BookingJson {
    private BookingJson() {}

    public static String encodePrice(PriceComponent price) {
        JSONArray lines = new JSONArray();
        for (PriceLine line : price.lines()) lines.put(new JSONObject().put("label", line.label()).put("amountPaise", line.amountPaise()));
        return lines.toString();
    }

    public static RecordedPrice decodePrice(String json) {
        JSONArray array = new JSONArray(json);
        List<PriceLine> lines = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject line = array.getJSONObject(i);
            lines.add(new PriceLine(line.getString("label"), line.getLong("amountPaise")));
        }
        return new RecordedPrice(lines);
    }

    public static String encodeCheckout(CheckoutSession checkout) {
        return new JSONObject().put("orderId", checkout.orderId()).put("amountPaise", checkout.amountPaise())
                .put("currency", checkout.currency()).put("publicKeyId", checkout.publicKeyId()).toString();
    }

    public static CheckoutSession decodeCheckout(String json) {
        JSONObject c = new JSONObject(json);
        return new CheckoutSession(c.getString("orderId"), c.getLong("amountPaise"), c.getString("currency"), c.getString("publicKeyId"));
    }
}
