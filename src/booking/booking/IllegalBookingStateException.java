package booking.booking;

public final class IllegalBookingStateException extends RuntimeException {
    public IllegalBookingStateException(String bookingId, BookingStatus from, BookingStatus to) {
        super("booking " + bookingId + " cannot go from " + from + " to " + to);
    }
}
