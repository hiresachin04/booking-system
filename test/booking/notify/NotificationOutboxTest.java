package booking.notify;

import booking.booking.Booking;
import booking.booking.BookingConfig;
import booking.booking.BookingService;
import booking.booking.InMemoryStorage;
import booking.booking.TieredRefundPolicy;
import booking.model.User;
import booking.retry.RetryPolicy;
import booking.support.FakePaymentGateway;
import booking.support.Fixtures;
import booking.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NotificationOutboxTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    private static final Duration BACKOFF = Duration.ofSeconds(30);
    private static final RetryPolicy RETRY = new RetryPolicy(3, BACKOFF);
    private static final Duration RETENTION = Duration.ofDays(30);

    private final MutableClock clock = new MutableClock(NOW);
    private final Booking booking = confirmedBooking();

    /** Listener that throws for its first {@code failures} calls, then records deliveries. */
    private static final class FlakyListener implements BookingListener {
        private final RuntimeException error;
        private int failuresLeft;
        private int calls;
        private final List<String> delivered = new ArrayList<>();

        FlakyListener(int failures, RuntimeException error) {
            this.failuresLeft = failures;
            this.error = error;
        }

        @Override
        public void onConfirmed(BookingEvent e) {
            calls++;
            if (failuresLeft > 0) {
                failuresLeft--;
                throw error;
            }
            delivered.add("confirmed " + e.booking().id());
        }

        @Override
        public void onCancelled(BookingEvent e) {
            calls++;
            delivered.add("cancelled " + e.booking().id());
        }
    }

    // A real booking from the real service; the outbox under test is separate from the one the service publishes to.
    private Booking confirmedBooking() {
        InMemoryStorage storage = new InMemoryStorage(clock);
        storage.shows().add(Fixtures.show("sh", NOW.plus(Duration.ofHours(8))));
        FakePaymentGateway payments = new FakePaymentGateway();
        BookingService service = new BookingService(storage, payments,
                new TieredRefundPolicy(List.of()), new BookingConfig(Duration.ofMinutes(10), 10, 0, Duration.ofMinutes(15), BookingConfig.DEFAULT_CAPTURE_RETRY), clock,
                new NotificationOutbox(List.of(), RETRY, RETENTION, clock));
        User alice = new User("u1", "Alice");
        Booking b = service.holdSeats(alice, "sh", List.of("A1"));
        return service.confirm(alice, b.id(), payments.simulateCheckout(b.checkout()));
    }

    @Test
    void publishRecordsWithoutCallingTheListener() {
        FlakyListener listener = new FlakyListener(0, null);
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, RETENTION, clock);

        outbox.publish(BookingEventType.CONFIRMED, booking);

        assertEquals(0, listener.calls);
        assertEquals(1, outbox.entries(DeliveryStatus.PENDING).size());
    }

    @Test
    void deliversEachEventTypeToTheMatchingCallback() {
        FlakyListener listener = new FlakyListener(0, null);
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);
        outbox.publish(BookingEventType.CANCELLED, booking);

        outbox.dispatchDue();

        assertEquals(List.of("confirmed " + booking.id(), "cancelled " + booking.id()), listener.delivered);
        assertEquals(2, outbox.entries(DeliveryStatus.SENT).size());
    }

    @Test
    void neverRedeliversASentEntry() {
        FlakyListener listener = new FlakyListener(0, null);
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();
        clock.advance(Duration.ofDays(1));
        outbox.dispatchDue();

        assertEquals(1, listener.calls);
    }

    @Test
    void retriesOnlyOnceTheBackoffHasElapsed() {
        FlakyListener listener = new FlakyListener(1, new RuntimeException("smtp 421"));
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);
        outbox.dispatchDue();

        clock.advance(BACKOFF.minusNanos(1));
        outbox.dispatchDue();
        assertEquals(1, listener.calls);

        clock.advance(Duration.ofNanos(1));
        outbox.dispatchDue();
        assertEquals(2, listener.calls);
        assertEquals(DeliveryStatus.SENT, outbox.entries(DeliveryStatus.SENT).getFirst().status());
    }

    @Test
    void doublesTheBackoffAfterEachFailure() {
        FlakyListener listener = new FlakyListener(2, new RuntimeException("smtp 421"));
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);
        outbox.dispatchDue();
        clock.advance(BACKOFF);
        outbox.dispatchDue();

        OutboxEntry entry = outbox.entries(DeliveryStatus.PENDING).getFirst();
        assertEquals(clock.instant().plus(BACKOFF.multipliedBy(2)), entry.nextAttemptAt());
    }

    @Test
    void deliversOnTheThirdAttemptAfterTwoFailures() {
        FlakyListener listener = new FlakyListener(2, new RuntimeException("smtp 421"));
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();
        clock.advance(BACKOFF);
        outbox.dispatchDue();
        clock.advance(BACKOFF.multipliedBy(2));
        outbox.dispatchDue();

        OutboxEntry entry = outbox.entries(DeliveryStatus.SENT).getFirst();
        assertEquals(3, entry.attempts());
        assertEquals(Optional.empty(), entry.lastError());
        assertEquals(List.of("confirmed " + booking.id()), listener.delivered);
    }

    @Test
    void parksTheEntryAsFailedWithItsErrorAfterMaxAttempts() {
        RuntimeException down = new RuntimeException("mailer down");
        FlakyListener listener = new FlakyListener(Integer.MAX_VALUE, down);
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        for (int i = 0; i < 10; i++) {
            outbox.dispatchDue();
            clock.advance(Duration.ofHours(1));
        }

        OutboxEntry entry = outbox.entries(DeliveryStatus.FAILED).getFirst();
        assertEquals(RETRY.maxAttempts(), listener.calls);
        assertEquals(RETRY.maxAttempts(), entry.attempts());
        assertSame(down, entry.lastError().orElseThrow());
    }

    @Test
    void retryingOneListenerNeverResendsToAnotherThatAlreadySucceeded() {
        FlakyListener email = new FlakyListener(1, new RuntimeException("smtp 421"));
        FlakyListener sms = new FlakyListener(0, null);
        NotificationOutbox outbox = new NotificationOutbox(List.of(email, sms), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();
        clock.advance(BACKOFF);
        outbox.dispatchDue();

        assertEquals(1, sms.calls);
        assertEquals(2, email.calls);
        assertEquals(2, outbox.entries(DeliveryStatus.SENT).size());
    }

    @Test
    void oneFailingListenerDoesNotStopDeliveryToTheRest() {
        FlakyListener broken = new FlakyListener(Integer.MAX_VALUE, new RuntimeException("mailer down"));
        FlakyListener sms = new FlakyListener(0, null);
        NotificationOutbox outbox = new NotificationOutbox(List.of(broken, sms), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();

        assertEquals(List.of("confirmed " + booking.id()), sms.delivered);
    }

    /** Records the event id of every call, failing the first {@code failures} of them. */
    private static final class IdRecorder implements BookingListener {
        private final List<String> seenIds = new ArrayList<>();
        private int failuresLeft;

        IdRecorder(int failures) {
            this.failuresLeft = failures;
        }

        @Override
        public void onConfirmed(BookingEvent event) {
            seenIds.add(event.id());
            if (failuresLeft-- > 0) throw new RuntimeException("smtp 421");
        }
    }

    @Test
    void retriesCarryTheSameEventId() {
        IdRecorder listener = new IdRecorder(1);
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();
        clock.advance(BACKOFF);
        outbox.dispatchDue();

        assertEquals(2, listener.seenIds.size());
        assertEquals(listener.seenIds.get(0), listener.seenIds.get(1));
    }

    @Test
    void everyPublishGetsItsOwnIdSharedByAllListeners() {
        IdRecorder email = new IdRecorder(0);
        IdRecorder sms = new IdRecorder(0);
        NotificationOutbox outbox = new NotificationOutbox(List.of(email, sms), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();

        assertEquals(email.seenIds, sms.seenIds);
        assertNotEquals(email.seenIds.get(0), email.seenIds.get(1));
    }

    /** Stands in for an email API that dedupes on an idempotency key, as SES/SendGrid-style APIs do. */
    private static final class FakeEmailProvider {
        private final Set<String> seenKeys = new HashSet<>();
        private final List<String> sent = new ArrayList<>();

        void send(String idempotencyKey, String body) {
            if (seenKeys.add(idempotencyKey)) sent.add(body);
        }
    }

    @Test
    void listenerKeyedOnEventIdSendsOneEmailEvenWhenItsSuccessWasReportedAsFailure() {
        FakeEmailProvider provider = new FakeEmailProvider();
        BookingListener emailer = new BookingListener() {
            private boolean ackLost = true;

            @Override
            public void onConfirmed(BookingEvent event) {
                provider.send(event.id(), "Booking " + event.booking().id() + " confirmed");
                if (ackLost) {
                    ackLost = false;
                    throw new RuntimeException("email sent but response timed out");
                }
            }
        };
        NotificationOutbox outbox = new NotificationOutbox(List.of(emailer), RETRY, RETENTION, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();
        clock.advance(BACKOFF);
        outbox.dispatchDue();

        assertEquals(1, provider.sent.size());
        assertEquals(1, outbox.entries(DeliveryStatus.SENT).size());
    }

    @Test
    void purgesASentEntryExactlyWhenItsRetentionEnds() {
        Duration retention = Duration.ofHours(1);
        NotificationOutbox outbox = new NotificationOutbox(List.of(new FlakyListener(0, null)), RETRY, retention, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);
        assertEquals(new DispatchReport(1, 0, 0, 0), outbox.dispatchDue());

        clock.advance(retention.minusNanos(1));
        assertEquals(0, outbox.dispatchDue().purged());
        assertEquals(1, outbox.entries(DeliveryStatus.SENT).size());

        clock.advance(Duration.ofNanos(1));
        assertEquals(1, outbox.dispatchDue().purged());
        assertEquals(List.of(), outbox.entries(DeliveryStatus.SENT));
    }

    @Test
    void neverPurgesPendingOrFailedEntries() {
        FlakyListener broken = new FlakyListener(Integer.MAX_VALUE, new RuntimeException("mailer down"));
        FlakyListener flaky = new FlakyListener(Integer.MAX_VALUE, new RuntimeException("smtp 421"));
        NotificationOutbox parkedOutbox = new NotificationOutbox(List.of(broken), new RetryPolicy(1, BACKOFF), Duration.ZERO, clock);
        NotificationOutbox retryingOutbox = new NotificationOutbox(List.of(flaky), new RetryPolicy(100, Duration.ofDays(365)), Duration.ZERO, clock);
        parkedOutbox.publish(BookingEventType.CONFIRMED, booking);
        retryingOutbox.publish(BookingEventType.CONFIRMED, booking);

        assertEquals(new DispatchReport(0, 1, 1, 0), parkedOutbox.dispatchDue());
        retryingOutbox.dispatchDue();
        clock.advance(Duration.ofDays(30));
        parkedOutbox.dispatchDue();
        retryingOutbox.dispatchDue();

        assertEquals(1, parkedOutbox.entries(DeliveryStatus.FAILED).size());
        assertEquals(1, retryingOutbox.entries(DeliveryStatus.PENDING).size());
    }

    @Test
    void zeroRetentionPurgesSentEntriesInTheSameRun() {
        NotificationOutbox outbox = new NotificationOutbox(List.of(new FlakyListener(0, null)), RETRY, Duration.ZERO, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        assertEquals(new DispatchReport(1, 0, 0, 1), outbox.dispatchDue());
    }
}
