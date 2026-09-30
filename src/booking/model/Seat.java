package booking.model;

import java.util.Objects;

public record Seat(String row, int number, SeatTier tier) {
    public Seat {
        Objects.requireNonNull(tier, "tier");
        // Letters-only rows keep id() unambiguous: "A1"+"1" and "A"+"11" would both read "A11".
        if (row == null || row.isEmpty() || !row.chars().allMatch(Character::isLetter)) {
            throw new IllegalArgumentException("row must be letters only: " + row);
        }
        if (number <= 0) throw new IllegalArgumentException("seat number must be positive: " + number);
    }

    public String id() {
        return row + number;
    }
}
