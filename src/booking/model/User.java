package booking.model;

import java.util.Objects;

public record User(String id, String name) {
    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
