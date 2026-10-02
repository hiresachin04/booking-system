package booking.db;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface SqlWork<T> {
    T apply(Connection connection) throws SQLException;
}
