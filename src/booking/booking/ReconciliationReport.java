package booking.booking;

import java.util.List;
import java.util.Objects;

/**
 * What one reconciliation run did. Bookings listed in {@code failures} are retried on the next run;
 * {@code seatsReleasedBookingIds} hit their pending deadline and gave their seats back for resale.
 */
public record ReconciliationReport(List<String> settledBookingIds, List<String> seatsReleasedBookingIds, List<Failure> failures) {
    public record Failure(String bookingId, RuntimeException error) {
        public Failure {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(error, "error");
        }
    }

    public ReconciliationReport {
        settledBookingIds = List.copyOf(settledBookingIds);
        seatsReleasedBookingIds = List.copyOf(seatsReleasedBookingIds);
        failures = List.copyOf(failures);
    }
}
