package booking.api;

/** A request the API refuses before it reaches the booking service, with the status and code to answer with. */
final class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
