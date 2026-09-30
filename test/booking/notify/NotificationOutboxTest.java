package booking.notify;

import booking.booking.Booking;
import booking.booking.BookingConfig;
import booking.booking.BookingRepository;
import booking.booking.BookingService;
import booking.booking.ShowRepository;
import booking.booking.TieredRefundPolicy;
import booking.discount.DiscountRegistry;
import booking.model.User;
import booking.support.FakePaymentGateway;
import booking.support.Fixtures;
import booking.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NotificationOutboxTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    private static final Duration BACKOFF = Duration.ofSeconds(30);
    private static final RetryPolicy RETRY = new RetryPolicy(3, BACKOFF);

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
        public void onConfirmed(Booking b) {
            calls++;
            if (failuresLeft > 0) {
                failuresLeft--;
                throw error;
            }
            delivered.add("confirmed " + b.id());
        }

        @Override
        public void onCancelled(Booking b) {
            calls++;
            delivered.add("cancelled " + b.id());
        }
    }

    // A real booking from the real service; the outbox under test is separate from the one the service publishes to.
    private Booking confirmedBooking() {
        ShowRepository shows = new ShowRepository(clock);
        shows.add(Fixtures.show("sh", NOW.plus(Duration.ofHours(8))));
        BookingService service = new BookingService(shows, new BookingRepository(), new DiscountRegistry(), new FakePaymentGateway(),
                new TieredRefundPolicy(List.of()), new BookingConfig(Duration.ofMinutes(10), 10, 0, Duration.ofMinutes(15)), clock,
                new NotificationOutbox(List.of(), RETRY, clock));
        User alice = new User("u1", "Alice");
        Booking b = service.holdSeats(alice, "sh", List.of("A1"));
        return service.confirm(alice, b.id());
    }

    @Test
    void publishRecordsWithoutCallingTheListener() {
        FlakyListener listener = new FlakyListener(0, null);
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, clock);

        outbox.publish(BookingEventType.CONFIRMED, booking);

        assertEquals(0, listener.calls);
        assertEquals(1, outbox.entries(DeliveryStatus.PENDING).size());
    }

    @Test
    void deliversEachEventTypeToTheMatchingCallback() {
        FlakyListener listener = new FlakyListener(0, null);
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);
        outbox.publish(BookingEventType.CANCELLED, booking);

        outbox.dispatchDue();

        assertEquals(List.of("confirmed " + booking.id(), "cancelled " + booking.id()), listener.delivered);
        assertEquals(2, outbox.entries(DeliveryStatus.SENT).size());
    }

    @Test
    void neverRedeliversASentEntry() {
        FlakyListener listener = new FlakyListener(0, null);
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();
        clock.advance(Duration.ofDays(1));
        outbox.dispatchDue();

        assertEquals(1, listener.calls);
    }

    @Test
    void retriesOnlyOnceTheBackoffHasElapsed() {
        FlakyListener listener = new FlakyListener(1, new RuntimeException("smtp 421"));
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, clock);
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
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, clock);
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
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, clock);
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
        NotificationOutbox outbox = new NotificationOutbox(List.of(listener), RETRY, clock);
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
        NotificationOutbox outbox = new NotificationOutbox(List.of(email, sms), RETRY, clock);
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
        NotificationOutbox outbox = new NotificationOutbox(List.of(broken, sms), RETRY, clock);
        outbox.publish(BookingEventType.CONFIRMED, booking);

        outbox.dispatchDue();

        assertEquals(List.of("confirmed " + booking.id()), sms.delivered);
    }

    @Test
    void backoffShiftIsCappedSoLargeAttemptCountsDoNotOverflow() {
        RetryPolicy many = new RetryPolicy(100, Duration.ofMillis(1));
        assertEquals(Duration.ofMillis(1L << 20), many.backoffAfter(99));
    }
}
