package booking.discount;

import booking.db.DiscountJson;
import booking.db.EpochNanos;
import booking.db.PostgresDatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Discount codes in the discount_codes table. A use is taken by one conditional UPDATE, so the database checks
 * the limit and increments in one step: racing confirms, on any number of instances, can't overshoot it.
 */
public final class PostgresDiscountRegistry implements DiscountStore {
    private static final String UNIQUE_VIOLATION = "23505";

    private final PostgresDatabase database;

    public PostgresDiscountRegistry(PostgresDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void add(DiscountCode code) {
        try {
            database.withConnection(connection -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO discount_codes (code, discount, valid_from, valid_until, min_order_paise, usage_limit, used) "
                                + "VALUES (?, ?::jsonb, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, code.code());
                    insert.setString(2, DiscountJson.encode(code.discount()).toString());
                    insert.setLong(3, EpochNanos.of(code.validFrom()));
                    insert.setLong(4, EpochNanos.of(code.validUntil()));
                    insert.setLong(5, code.minOrderPaise());
                    insert.setInt(6, code.usageLimit());
                    insert.setInt(7, code.used());
                    insert.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) throw new IllegalArgumentException("discount code already exists: " + code.code(), e);
            throw e;
        }
    }

    @Override
    public Optional<DiscountCode> find(String code) {
        return database.withConnection(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT discount, valid_from, valid_until, min_order_paise, usage_limit, used FROM discount_codes WHERE code = ?")) {
                select.setString(1, code);
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) return Optional.empty();
                    return Optional.of(new DiscountCode(code, DiscountJson.decode(row.getString(1)),
                            EpochNanos.toInstant(row.getLong(2)), EpochNanos.toInstant(row.getLong(3)),
                            row.getLong(4), row.getInt(5), row.getInt(6)));
                }
            }
        });
    }

    @Override
    public boolean tryConsume(String code, Instant now) {
        long at = EpochNanos.of(now);
        return database.withConnection(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE discount_codes SET used = used + 1 "
                            + "WHERE code = ? AND used < usage_limit AND valid_from <= ? AND ? < valid_until")) {
                update.setString(1, code);
                update.setLong(2, at);
                update.setLong(3, at);
                return update.executeUpdate() == 1;
            }
        });
    }

    @Override
    public void releaseUse(String code) {
        boolean released = database.withConnection(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE discount_codes SET used = used - 1 WHERE code = ? AND used > 0")) {
                update.setString(1, code);
                return update.executeUpdate() == 1;
            }
        });
        if (released) return;
        if (find(code).isEmpty()) throw new IllegalStateException("no discount code " + code + " to release a use of");
        throw new IllegalStateException("discount code " + code + " has no uses to release");
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
