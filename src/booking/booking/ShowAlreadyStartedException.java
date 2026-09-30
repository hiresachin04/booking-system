package booking.booking;

public final class ShowAlreadyStartedException extends RuntimeException {
    public ShowAlreadyStartedException(String showId) {
        super("show " + showId + " has already started");
    }
}
