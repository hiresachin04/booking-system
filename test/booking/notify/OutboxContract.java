package booking.notify;

import booking.booking.Booking;
import booking.retry.RetryPolicy;
import booking.support.MutableClock;
import booking.support.Race;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every Outbox must guarantee, run against each implementation by a subclass. */
abstract class OutboxContract {
    protected static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    protected static final Duration BACKOFF = Duration.ofSeconds(30);
    protected static final RetryPolicy RETRY = new RetryPolicy(3, BACKOFF);
    protected static final Duration RETENTION = Duration.ofDays(30);

    protected final MutableClock clock = new MutableClock(NOW);

    /** One delivery (an event for one listener) as both implementations can report it. */
    protected record Delivery(String eventId, String listener, DeliveryStatus status, int attempts, Instant nextAttemptAt,
                              Optional<String> lastError) {}

    protected abstract Outbox newOutbox(List<BookingListener> listeners, RetryPolicy retry, Duration sentRetention);

    protected abstract List<Delivery> deliveries(Outbox outbox, DeliveryStatus status);

    /** A booking the outbox can refer to (and, for a database outbox, load). */
    protected abstract Booking savedBooking();

    /** Fails its first {@code failures} calls, then records what it was sent. */
    protected static final class Recorder implements BookingListener {
        private final String name;
        private int failuresLeft;
        final List<String> received = Collections.synchronizedList(new ArrayList<>());
        final List<String> eventIds = Collections.synchronizedList(new ArrayList<>());

        Recorder(String name, int failures) {
            this.name = name;
            this.failuresLeft = failures;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public synchronized void onConfirmed(BookingEvent event) {
            eventIds.add(event.id());
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new RuntimeException(name + " down");
            }
            received.add("confirmed " + event.booking().id());
        }

        @Override
        public void onCancelled(BookingEvent event) {
            received.add("cancelled " + event.booking().id());
        }
    }

    @Test
    void publishRecordsWithoutDelivering() {
        Recorder email = new Recorder("email", 0);
        Outbox outbox = newOutbox(List.of(email), RETRY, RETENTION);

        outbox.publish(BookingEventType.CONFIRMED, savedBooking());

        assertEquals(List.of(), email.received);
        assertEquals(1, deliveries(outbox, DeliveryStatus.PENDING).size());
    }

    @Test
    void deliversEachEventTypeToItsCallback() {
        Recorder email = new Recorder("email", 0);
        Outbox outbox = newOutbox(List.of(email), RETRY, RETENTION);
        Booking booking = savedBooking();
        outbox.publish(BookingEventType.CONFIRMED, booking);
        outbox.publish(BookingEventType.CANCELLED, booking);

        assertEquals(new DispatchReport(2, 0, 0, 0), outbox.dispatchDue());

        assertEquals(List.of("confirmed " + booking.id(), "cancelled " + booking.id()), email.received);
    }

    @Test
    void neverRedeliversASentEntry() {
        Recorder email = new Recorder("email", 0);
        Outbox outbox = newOutbox(List.of(email), RETRY, RETENTION);
        outbox.publish(BookingEventType.CONFIRMED, savedBooking());

        outbox.dispatchDue();
        clock.advance(Duration.ofDays(1));
        outbox.dispatchDue();

        assertEquals(1, email.received.size());
    }

    @Test
    void retriesExactlyWhenTheBackoffEndsWithTheSameEventId() {
        Recorder email = new Recorder("email", 1);
        Outbox outbox = newOutbox(List.of(email), RETRY, RETENTION);
        outbox.publish(BookingEventType.CONFIRMED, savedBooking());
        outbox.dispatchDue();

        clock.advance(BACKOFF.minusNanos(1));
        outbox.dispatchDue();
        assertEquals(1, email.eventIds.size());

        clock.advance(Duration.ofNanos(1));
        outbox.dispatchDue();
        assertEquals(2, email.eventIds.size());
        assertEquals(email.eventIds.get(0), email.eventIds.get(1));
        assertEquals(1, deliveries(outbox, DeliveryStatus.SENT).size());
    }

    @Test
    void doublesTheBackoffAfterEachFailure() {
        Recorder email = new Recorder("email", 2);
        Outbox outbox = newOutbox(List.of(email), RETRY, RETENTION);
        outbox.publish(BookingEventType.CONFIRMED, savedBooking());
        outbox.dispatchDue();
        clock.advance(BACKOFF);
        outbox.dispatchDue();

        Delivery pending = deliveries(outbox, DeliveryStatus.PENDING).getFirst();
        assertEquals(2, pending.attempts());
        assertEquals(clock.instant().plus(BACKOFF.multipliedBy(2)), pending.nextAttemptAt());
    }

    @Test
    void parksAnEntryAsFailedWithItsErrorAfterTheLastAttempt() {
        Recorder email = new Recorder("email", Integer.MAX_VALUE);
        Outbox outbox = newOutbox(List.of(email), RETRY, RETENTION);
        outbox.publish(BookingEventType.CONFIRMED, savedBooking());

        for (int i = 0; i < 10; i++) {
            outbox.dispatchDue();
            clock.advance(Duration.ofHours(1));
        }

        Delivery failed = deliveries(outbox, DeliveryStatus.FAILED).getFirst();
        assertEquals(RETRY.maxAttempts(), failed.attempts());
        assertEquals(RETRY.maxAttempts(), email.eventIds.size());
        assertTrue(failed.lastError().orElseThrow().contains("email down"), failed.lastError().toString());
    }

    @Test
    void retryingOneListenerNeverResendsToAnother() {
        Recorder email = new Recorder("email", 1);
        Recorder sms = new Recorder("sms", 0);
        Outbox outbox = newOutbox(List.of(email, sms), RETRY, RETENTION);
        outbox.publish(BookingEventType.CONFIRMED, savedBooking());

        outbox.dispatchDue();
        clock.advance(BACKOFF);
        outbox.dispatchDue();

        assertEquals(1, sms.eventIds.size());
        assertEquals(2, email.eventIds.size());
        assertEquals(sms.eventIds.getFirst(), email.eventIds.getFirst());
    }

    @Test
    void everyPublishGetsItsOwnEventId() {
        Recorder email = new Recorder("email", 0);
        Outbox outbox = newOutbox(List.of(email), RETRY, RETENTION);
        Booking booking = savedBooking();
        outbox.publish(BookingEventType.CONFIRMED, booking);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();

        assertNotEquals(email.eventIds.get(0), email.eventIds.get(1));
    }

    @Test
    void purgesASentEntryExactlyWhenItsRetentionEnds() {
        Duration retention = Duration.ofHours(1);
        Outbox outbox = newOutbox(List.of(new Recorder("email", 0)), RETRY, retention);
        outbox.publish(BookingEventType.CONFIRMED, savedBooking());
        outbox.dispatchDue();

        clock.advance(retention.minusNanos(1));
        assertEquals(0, outbox.dispatchDue().purged());
        clock.advance(Duration.ofNanos(1));
        assertEquals(1, outbox.dispatchDue().purged());
        assertEquals(List.of(), deliveries(outbox, DeliveryStatus.SENT));
    }

    @Test
    void neverPurgesPendingOrFailedEntries() {
        Outbox outbox = newOutbox(List.of(new Recorder("email", Integer.MAX_VALUE)), new RetryPolicy(1, BACKOFF), Duration.ZERO);
        outbox.publish(BookingEventType.CONFIRMED, savedBooking());

        outbox.dispatchDue();
        clock.advance(Duration.ofDays(30));
        outbox.dispatchDue();

        assertEquals(1, deliveries(outbox, DeliveryStatus.FAILED).size());
    }

    @Test
    void concurrentDispatchersDeliverEachEntryExactlyOnce() {
        Recorder email = new Recorder("email", 0);
        Outbox outbox = newOutbox(List.of(email), RETRY, RETENTION);
        Booking booking = savedBooking();
        for (int i = 0; i < 20; i++) outbox.publish(BookingEventType.CONFIRMED, booking);

        Race.run(4, i -> {
            outbox.dispatchDue();
            return true;
        });

        assertEquals(20, email.eventIds.size());
        assertEquals(20, email.eventIds.stream().distinct().count());
    }
}
