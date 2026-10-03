package booking.booking;

import java.util.List;
import java.util.Objects;

/**
 * What one reconciliation run did. Bookings listed in {@code failures} are retried on the next run.
 * {@code confirmedWithoutBrowserBookingIds} were paid but never confirmed by the browser, and were confirmed from the
 * processor's record. {@code expiredHoldBookingIds} were holds that ran out, paid too late or not paid at all.
 * {@code seatsReleasedBookingIds} stopped retrying their capture and gave their seats back for resale.
 * {@code awaitingProcessorExpiryBookingIds} have released seats and an authorization the processor can't void; they
 * settle once the processor's expiry refunds it, and are not failures.
 */
public record ReconciliationReport(List<String> settledBookingIds, List<String> seatsReleasedBookingIds,
                                   List<String> awaitingProcessorExpiryBookingIds, List<String> confirmedWithoutBrowserBookingIds,
                                   List<String> expiredHoldBookingIds, List<Failure> failures) {
    public record Failure(String bookingId, RuntimeException error) {
        public Failure {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(error, "error");
        }
    }

    public ReconciliationReport {
        settledBookingIds = List.copyOf(settledBookingIds);
        seatsReleasedBookingIds = List.copyOf(seatsReleasedBookingIds);
        awaitingProcessorExpiryBookingIds = List.copyOf(awaitingProcessorExpiryBookingIds);
        confirmedWithoutBrowserBookingIds = List.copyOf(confirmedWithoutBrowserBookingIds);
        expiredHoldBookingIds = List.copyOf(expiredHoldBookingIds);
        failures = List.copyOf(failures);
    }
}
