package booking.booking;

import java.util.List;
import java.util.Objects;

/** What one reconciliation run did. Bookings listed in {@code failures} are retried on the next run. */
public record ReconciliationReport(List<String> settledBookingIds, List<Failure> failures) {
    public record Failure(String bookingId, RuntimeException error) {
        public Failure {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(error, "error");
        }
    }

    public ReconciliationReport {
        settledBookingIds = List.copyOf(settledBookingIds);
        failures = List.copyOf(failures);
    }
}
