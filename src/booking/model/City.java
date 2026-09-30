package booking.model;

import java.util.Objects;

public record City(String id, String name) {
    public City {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
