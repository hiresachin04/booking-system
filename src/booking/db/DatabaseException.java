package booking.db;

/** A database call failed. Unchecked so stores can implement the storage interfaces, which don't know about JDBC. */
public final class DatabaseException extends RuntimeException {
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
