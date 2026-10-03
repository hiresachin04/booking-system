-- Read-only queries for looking at the booking database.
--
--   /Applications/Postgres.app/Contents/Versions/latest/bin/psql -h localhost -U booking -d booking -f db/queries.sql
--
-- or paste one at a time into psql. Times are bigint nanoseconds since the epoch, shown here in IST; money is in
-- paise, shown in rupees. Only read: changing rows by hand bypasses the locks and rules the service relies on.
-- Not a migration: the migrator only reads db/migrations/.

\pset pager off

\echo '== 1. Shows'
SELECT id, definition->'movie'->>'title' AS movie, definition->'screen'->'theater'->>'name' AS theater,
       to_timestamp(starts_at / 1e9) AT TIME ZONE 'Asia/Kolkata' AS starts_at_ist
FROM shows ORDER BY starts_at;

\echo '== 2. Seat counts per show (an expired hold counts as available)'
SELECT show_id,
       count(*) FILTER (WHERE status = 'AVAILABLE'
                        OR (status = 'HELD' AND hold_expires_at <= extract(epoch FROM now()) * 1e9)) AS available,
       count(*) FILTER (WHERE status = 'HELD' AND hold_expires_at > extract(epoch FROM now()) * 1e9) AS held,
       count(*) FILTER (WHERE status = 'BOOKED') AS booked
FROM show_seats GROUP BY show_id;

\echo '== 3. Seats that are not available, and which booking has them'
SELECT show_id, seat_id, status, booking_id,
       to_timestamp(hold_expires_at / 1e9) AT TIME ZONE 'Asia/Kolkata' AS hold_expires_ist
FROM show_seats WHERE status <> 'AVAILABLE' ORDER BY show_id, seat_id;

\echo '== 4. Bookings, newest first'
SELECT b.id, b.user_id, b.status, array_to_string(b.seat_ids, ',') AS seats,
       round((SELECT sum((line->>'amountPaise')::bigint) FROM jsonb_array_elements(b.price) AS line) / 100.0, 2) AS total_rupees,
       b.discount_code, round(b.refunded_paise / 100.0, 2) AS refunded_rupees,
       b.payment_id, b.checkout->>'orderId' AS razorpay_order,
       to_timestamp(b.hold_expires_at / 1e9) AT TIME ZONE 'Asia/Kolkata' AS hold_expires_ist
FROM bookings b ORDER BY b.hold_expires_at DESC;

\echo '== 5. Price breakdown of the newest booking (swap in an id to see another)'
SELECT line->>'label' AS line, round((line->>'amountPaise')::bigint / 100.0, 2) AS rupees
FROM bookings, jsonb_array_elements(price) AS line
WHERE id = (SELECT id FROM bookings ORDER BY hold_expires_at DESC LIMIT 1);

\echo '== 6. Bookings whose payment is still being settled'
SELECT id, status, payment_id, capture_attempts, capture_given_up,
       to_timestamp(next_capture_at / 1e9) AT TIME ZONE 'Asia/Kolkata' AS next_capture_ist,
       to_timestamp(payment_pending_since / 1e9) AT TIME ZONE 'Asia/Kolkata' AS pending_since_ist
FROM bookings WHERE status IN ('PAYMENT_PENDING', 'PAYMENT_UNRESOLVED');

\echo '== 7. Bookings by status'
SELECT status, count(*) FROM bookings GROUP BY status ORDER BY count(*) DESC;

\echo '== 8. Revenue per show (cancelled bookings count in full; subtract refunds for net)'
SELECT b.show_id,
       count(*) FILTER (WHERE b.status = 'CONFIRMED') AS confirmed,
       round(sum(t.total) FILTER (WHERE b.status IN ('CONFIRMED', 'CANCELLED')) / 100.0, 2) AS taken_rupees,
       round(sum(b.refunded_paise) / 100.0, 2) AS refunded_rupees
FROM bookings b
CROSS JOIN LATERAL (SELECT sum((line->>'amountPaise')::bigint) AS total FROM jsonb_array_elements(b.price) AS line) t
GROUP BY b.show_id;

\echo '== 9. Discount codes and usage'
SELECT code, discount, used, usage_limit, round(min_order_paise / 100.0, 2) AS min_order_rupees,
       to_timestamp(valid_until / 1e9) AT TIME ZONE 'Asia/Kolkata' AS valid_until_ist
FROM discount_codes;

\echo '== 10. Notification outbox, pending and failed first'
SELECT id, event_type, booking_id, listener, status, attempts,
       to_timestamp(next_attempt_at / 1e9) AT TIME ZONE 'Asia/Kolkata' AS next_attempt_ist, last_error
FROM notification_outbox ORDER BY (status = 'SENT'), id;

\echo '== 11. Schema migrations applied'
SELECT version, name, applied_at FROM schema_version ORDER BY version;
