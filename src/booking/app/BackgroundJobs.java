package booking.app;

import booking.booking.BookingService;
import booking.booking.ReconciliationReport;
import booking.notify.DispatchReport;
import booking.notify.Outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs notification dispatch and payment reconciliation on a schedule. Fixed delay rather than fixed rate, so a
 * slow run is followed by a pause instead of a backlog of runs. The executor belongs to the caller.
 */
public final class BackgroundJobs implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final Duration dispatchEvery;
    private final Duration reconcileEvery;
    private final ScheduledJob<DispatchReport> dispatch;
    private final ScheduledJob<ReconciliationReport> reconcile;
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();

    public BackgroundJobs(ScheduledExecutorService scheduler, BookingService bookings, Outbox outbox,
                          Duration dispatchEvery, Duration reconcileEvery) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.dispatchEvery = requirePositive(dispatchEvery, "dispatchEvery");
        this.reconcileEvery = requirePositive(reconcileEvery, "reconcileEvery");
        this.dispatch = new ScheduledJob<>("notification-dispatch", outbox::dispatchDue);
        this.reconcile = new ScheduledJob<>("payment-reconciliation", bookings::reconcilePendingPayments);
    }

    public synchronized void start() {
        if (!scheduled.isEmpty()) throw new IllegalStateException("background jobs already started");
        scheduled.add(schedule(dispatch, dispatchEvery));
        scheduled.add(schedule(reconcile, reconcileEvery));
    }

    public ScheduledJob<DispatchReport> dispatch() {
        return dispatch;
    }

    public ScheduledJob<ReconciliationReport> reconcile() {
        return reconcile;
    }

    /** Stops future runs; a run already in progress is allowed to finish. */
    @Override
    public synchronized void close() {
        for (ScheduledFuture<?> future : scheduled) future.cancel(false);
    }

    private ScheduledFuture<?> schedule(Runnable job, Duration every) {
        long nanos = every.toNanos();
        return scheduler.scheduleWithFixedDelay(job, nanos, nanos, TimeUnit.NANOSECONDS);
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return d;
    }
}
