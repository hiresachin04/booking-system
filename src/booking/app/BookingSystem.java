package booking.app;

import booking.booking.BookingConfig;
import booking.booking.BookingRepository;
import booking.booking.BookingService;
import booking.booking.RefundPolicy;
import booking.booking.ShowRepository;
import booking.discount.DiscountRegistry;
import booking.notify.BookingListener;
import booking.notify.NotificationOutbox;
import booking.retry.RetryPolicy;
import booking.payment.PaymentGateway;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Composition root: wires every component by constructor and runs the background jobs. The payment gateway and
 * listeners are parameters because they are the process boundaries a deployment chooses.
 */
public final class BookingSystem implements AutoCloseable {
    public record Settings(BookingConfig booking, RefundPolicy refundPolicy, RetryPolicy notificationRetry,
                           Duration sentRetention, Duration dispatchEvery, Duration reconcileEvery, Duration shutdownGrace) {
        public Settings {
            Objects.requireNonNull(booking, "booking");
            Objects.requireNonNull(refundPolicy, "refundPolicy");
            Objects.requireNonNull(notificationRetry, "notificationRetry");
            Objects.requireNonNull(sentRetention, "sentRetention");
            Objects.requireNonNull(dispatchEvery, "dispatchEvery");
            Objects.requireNonNull(reconcileEvery, "reconcileEvery");
            Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        }
    }

    private static final int JOB_THREADS = 2;

    private final ShowRepository shows;
    private final BookingRepository bookings;
    private final DiscountRegistry discounts;
    private final NotificationOutbox outbox;
    private final BookingService bookingService;
    private final ScheduledExecutorService scheduler;
    private final BackgroundJobs jobs;
    private final Duration shutdownGrace;

    private BookingSystem(Settings settings, PaymentGateway payments, List<BookingListener> listeners, Clock clock) {
        this.shows = new ShowRepository(clock);
        this.bookings = new BookingRepository();
        this.discounts = new DiscountRegistry();
        this.outbox = new NotificationOutbox(listeners, settings.notificationRetry(), settings.sentRetention(), clock);
        this.bookingService = new BookingService(shows, bookings, discounts, payments, settings.refundPolicy(), settings.booking(), clock, outbox);
        // One thread per job: a slow listener holding up dispatch must not also delay payment reconciliation.
        this.scheduler = Executors.newScheduledThreadPool(JOB_THREADS, r -> {
            Thread t = new Thread(r, "booking-jobs");
            t.setDaemon(true);
            return t;
        });
        this.jobs = new BackgroundJobs(scheduler, bookingService, outbox, settings.dispatchEvery(), settings.reconcileEvery());
        this.shutdownGrace = settings.shutdownGrace();
    }

    public static BookingSystem start(Settings settings, PaymentGateway payments, List<BookingListener> listeners, Clock clock) {
        BookingSystem system = new BookingSystem(settings, payments, listeners, clock);
        system.jobs.start();
        return system;
    }

    public BookingService bookingService() {
        return bookingService;
    }

    public ShowRepository shows() {
        return shows;
    }

    public BookingRepository bookings() {
        return bookings;
    }

    public DiscountRegistry discounts() {
        return discounts;
    }

    public NotificationOutbox outbox() {
        return outbox;
    }

    public BackgroundJobs jobs() {
        return jobs;
    }

    /** Stops the jobs and waits up to {@code shutdownGrace} for a run in progress; interrupts it after that. */
    @Override
    public void close() {
        jobs.close();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(shutdownGrace.toNanos(), TimeUnit.NANOSECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isTerminated() {
        return scheduler.isTerminated();
    }
}
