package booking.model;

import java.time.ZoneId;
import java.util.Objects;

public record Theater(String id, String name, City city, ZoneId zone) {
    public Theater {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(zone, "zone");
    }
}
