package booking.notify;

import booking.booking.Booking;
import booking.booking.PostgresBookingRepository;
import booking.booking.PostgresShowRepository;
import booking.booking.TestBookings;
import booking.retry.RetryPolicy;
import booking.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgresNotificationOutbox against the Outbox contract, plus what only a database outbox can do. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class PostgresNotificationOutboxTest extends OutboxContract {
    private final PostgresTestDatabase test = PostgresTestDatabase.create();
    private final PostgresShowRepository shows = new PostgresShowRepository(test.database(), clock);
    private final PostgresBookingRepository bookings = new PostgresBookingRepository(test.database());

    @AfterEach
    void drop() {
        test.close();
    }

    @Override
    protected PostgresNotificationOutbox newOutbox(List<BookingListener> listeners, RetryPolicy retry, Duration sentRetention) {
        return new PostgresNotificationOutbox(test.database(), bookings, listeners, retry, sentRetention, clock);
    }

    @Override
    protected List<Delivery> deliveries(Outbox outbox, DeliveryStatus status) {
        return ((PostgresNotificationOutbox) outbox).entries(status).stream()
                .map(e -> new Delivery(e.eventId(), e.listener(), e.status(), e.attempts(), e.nextAttemptAt(), e.lastError()))
                .toList();
    }

    @Override
    protected Booking savedBooking() {
        return TestBookings.saveConfirmed(shows, bookings, clock);
    }

    @Test
    void anEventPublishedInARolledBackTransactionIsNeverDelivered() {
        Recorder email = new Recorder("email", 0);
        PostgresNotificationOutbox outbox = newOutbox(List.of(email), RETRY, RETENTION);
        Booking booking = savedBooking();

        assertThrows(RuntimeException.class, () -> test.database().inTransaction(() -> {
            outbox.publish(BookingEventType.CONFIRMED, booking);
            throw new RuntimeException("booking change failed");
        }));
        outbox.dispatchDue();

        assertEquals(List.of(), email.eventIds);
        assertEquals(List.of(), outbox.entries(DeliveryStatus.PENDING));
    }

    @Test
    void anEntryClaimedByADispatcherThatDiedIsRetriedWhenItsLeaseEnds() {
        BookingListener crashes = new BookingListener() {
            @Override
            public String name() {
                return "email";
            }

            @Override
            public void onConfirmed(BookingEvent event) {
                throw new StackOverflowError("dispatcher thread died mid-delivery");
            }
        };
        Booking booking = savedBooking();
        PostgresNotificationOutbox dying = newOutbox(List.of(crashes), RETRY, RETENTION);
        dying.publish(BookingEventType.CONFIRMED, booking);
        assertThrows(StackOverflowError.class, dying::dispatchDue);

        Recorder email = new Recorder("email", 0);
        PostgresNotificationOutbox restarted = newOutbox(List.of(email), RETRY, RETENTION);
        clock.advance(PostgresNotificationOutbox.LEASE.minusNanos(1));
        restarted.dispatchDue();
        assertEquals(List.of(), email.received);

        clock.advance(Duration.ofNanos(1));
        restarted.dispatchDue();
        assertEquals(List.of("confirmed " + booking.id()), email.received);
    }

    @Test
    void anEntryForAListenerNoLongerRegisteredEndsUpFailedNotStuck() {
        PostgresNotificationOutbox before = newOutbox(List.of(new Recorder("sms", 0)), RETRY, RETENTION);
        before.publish(BookingEventType.CONFIRMED, savedBooking());
        PostgresNotificationOutbox after = newOutbox(List.of(new Recorder("email", 0)), new RetryPolicy(1, BACKOFF), RETENTION);

        after.dispatchDue();

        PostgresNotificationOutbox.Entry failed = after.entries(DeliveryStatus.FAILED).getFirst();
        assertEquals("sms", failed.listener());
        assertTrue(failed.lastError().orElseThrow().contains("no listener named sms"));
    }

    @Test
    void rejectsTwoListenersWithTheSameName() {
        assertThrows(IllegalArgumentException.class,
                () -> newOutbox(List.of(new Recorder("email", 0), new Recorder("email", 0)), RETRY, RETENTION));
    }
}
