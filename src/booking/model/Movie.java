package booking.model;

import java.time.Duration;
import java.util.Objects;

public record Movie(String id, String title, Duration runtime) {
    public Movie {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        if (runtime == null || runtime.isNegative() || runtime.isZero()) {
            throw new IllegalArgumentException("runtime must be positive: " + runtime);
        }
    }
}
