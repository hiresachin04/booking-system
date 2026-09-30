package booking.app;

import booking.booking.Booking;
import booking.booking.BookingConfig;
import booking.booking.BookingStatus;
import booking.booking.PaymentPendingException;
import booking.booking.TieredRefundPolicy;
import booking.model.User;
import booking.notify.BookingEvent;
import booking.notify.BookingListener;
import booking.retry.RetryPolicy;
import booking.support.FakePaymentGateway;
import booking.support.Fixtures;
import booking.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real scheduler with millisecond intervals; waits are bounded so a broken schedule fails, not hangs. */
class BookingSystemTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");
    private static final Duration TICK = Duration.ofMillis(5);
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final MutableClock clock = new MutableClock(NOW);
    private final FakePaymentGateway payments = new FakePaymentGateway();
    // Written by the job thread, read by the test thread.
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final BookingListener recorder = new BookingListener() {
        @Override
        public void onConfirmed(BookingEvent event) {
            delivered.add("confirmed " + event.booking().id());
        }
    };
    private final BookingSystem system = BookingSystem.start(new BookingSystem.Settings(
                    new BookingConfig(Duration.ofMinutes(10), 10, 0, Duration.ofMinutes(15)),
                    new TieredRefundPolicy(List.of()), new RetryPolicy(3, TICK), Duration.ofDays(1), TICK, TICK, WAIT),
            payments, List.of(recorder), clock);
    private final User alice = new User("u1", "Alice");

    @AfterEach
    void stop() {
        system.close();
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(1);
        }
    }

    @Test
    void deliversConfirmationsWithoutAnyManualDispatch() throws Exception {
        system.shows().add(Fixtures.show("sh", NOW.plus(Duration.ofHours(8))));
        Booking booking = system.bookingService().holdSeats(alice, "sh", List.of("A1"));

        system.bookingService().confirm(alice, booking.id());

        awaitTrue(() -> !delivered.isEmpty(), "scheduled dispatch");
        assertEquals(List.of("confirmed " + booking.id()), delivered);
    }

    @Test
    void settlesAPendingPaymentWithoutAnyManualReconciliation() throws Exception {
        system.shows().add(Fixtures.show("sh", NOW.plus(Duration.ofHours(8))));
        Booking booking = system.bookingService().holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(1);
        assertThrows(PaymentPendingException.class, () -> system.bookingService().confirm(alice, booking.id()));

        awaitTrue(() -> booking.status() == BookingStatus.CONFIRMED, "scheduled reconciliation");
        awaitTrue(() -> !delivered.isEmpty(), "confirmation after reconciliation");
    }

    @Test
    void reconciliationKeepsRunningThroughAProcessorOutage() throws Exception {
        system.shows().add(Fixtures.show("sh", NOW.plus(Duration.ofHours(8))));
        Booking booking = system.bookingService().holdSeats(alice, "sh", List.of("A1"));
        payments.loseNextCaptureResponse(new RuntimeException("read timeout"));
        payments.failStatusLookups(4);
        assertThrows(PaymentPendingException.class, () -> system.bookingService().confirm(alice, booking.id()));

        awaitTrue(() -> booking.status() == BookingStatus.CONFIRMED, "reconciliation after outage");
        assertTrue(system.jobs().reconcile().runs() >= 4);
    }

    @Test
    void closeStopsTheJobsAndTheirThreads() throws Exception {
        awaitTrue(() -> system.jobs().dispatch().runs() > 0, "first dispatch run");

        system.close();

        assertTrue(system.isTerminated());
        int runsAtClose = system.jobs().dispatch().runs();
        Thread.sleep(TICK.toMillis() * 10);
        assertEquals(runsAtClose, system.jobs().dispatch().runs());
    }
}
