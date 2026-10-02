package booking.db;

import booking.pricing.Discount;
import org.json.JSONObject;

/** A Discount as JSON for the discount_codes.discount column. */
public final class DiscountJson {
    private DiscountJson() {}

    public static JSONObject encode(Discount discount) {
        return switch (discount) {
            case Discount.Percent p -> new JSONObject().put("type", "percent").put("bps", p.bps()).put("capPaise", p.capPaise());
            case Discount.Flat f -> new JSONObject().put("type", "flat").put("paise", f.paise());
        };
    }

    public static Discount decode(String json) {
        JSONObject d = new JSONObject(json);
        return switch (d.getString("type")) {
            case "percent" -> new Discount.Percent(d.getInt("bps"), d.getLong("capPaise"));
            case "flat" -> new Discount.Flat(d.getLong("paise"));
            default -> throw new IllegalStateException("unknown discount type " + d.getString("type"));
        };
    }
}
