package booking.notify;

/** What one dispatch run did; {@code parked} entries exhausted their attempts and are now FAILED. */
public record DispatchReport(int sent, int failedAttempts, int parked, int purged) {}
