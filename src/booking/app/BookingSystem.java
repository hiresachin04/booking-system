package booking.app;

import booking.booking.BookingConfig;
import booking.booking.BookingStore;
import booking.booking.BookingService;
import booking.booking.RefundPolicy;
import booking.booking.ShowStore;
import booking.booking.Storage;
import booking.discount.DiscountStore;
import booking.notify.BookingListener;
import booking.notify.Outbox;
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

        /**
         * Reconciliation runs every 30s. Each booking's capture retries have their own backoff, so a frequent run
         * doesn't mean a capture attempt every run: a retry lands up to one interval after it falls due.
         */
        public static Settings withDefaults(BookingConfig booking, RefundPolicy refundPolicy) {
            return new Settings(booking, refundPolicy, new RetryPolicy(5, Duration.ofSeconds(30)), Duration.ofDays(7),
                    Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10));
        }
    }

    private static final int JOB_THREADS = 2;

    private final Storage storage;
    private final Outbox outbox;
    private final BookingService bookingService;
    private final ScheduledExecutorService scheduler;
    private final BackgroundJobs jobs;
    private final Duration shutdownGrace;

    private BookingSystem(Settings settings, Storage storage, PaymentGateway payments, List<BookingListener> listeners, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.outbox = storage.outbox(listeners, settings.notificationRetry(), settings.sentRetention(), clock);
        this.bookingService = new BookingService(storage, payments, settings.refundPolicy(), settings.booking(), clock, outbox);
        // One thread per job: a slow listener holding up dispatch must not also delay payment reconciliation.
        this.scheduler = Executors.newScheduledThreadPool(JOB_THREADS, r -> {
            Thread t = new Thread(r, "booking-jobs");
            t.setDaemon(true);
            return t;
        });
        this.jobs = new BackgroundJobs(scheduler, bookingService, outbox, settings.dispatchEvery(), settings.reconcileEvery());
        this.shutdownGrace = settings.shutdownGrace();
    }

    /** {@code storage} usually comes from {@link StorageFactory#create}; BookingSystem doesn't choose it. */
    public static BookingSystem start(Settings settings, Storage storage, PaymentGateway payments, List<BookingListener> listeners,
                                      Clock clock) {
        BookingSystem system = new BookingSystem(settings, storage, payments, listeners, clock);
        system.jobs.start();
        return system;
    }

    public BookingService bookingService() {
        return bookingService;
    }

    public ShowStore shows() {
        return storage.shows();
    }

    public BookingStore bookings() {
        return storage.bookings();
    }

    public DiscountStore discounts() {
        return storage.discounts();
    }

    public Outbox outbox() {
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
