package booking.notify;

import booking.booking.Booking;
import booking.retry.RetryPolicy;
import booking.booking.TestBookings;

import java.time.Duration;
import java.util.List;

/** NotificationOutbox against the Outbox contract. */
class InMemoryOutboxContractTest extends OutboxContract {
    @Override
    protected Outbox newOutbox(List<BookingListener> listeners, RetryPolicy retry, Duration sentRetention) {
        return new NotificationOutbox(listeners, retry, sentRetention, clock);
    }

    @Override
    protected List<Delivery> deliveries(Outbox outbox, DeliveryStatus status) {
        return ((NotificationOutbox) outbox).entries(status).stream()
                .map(e -> new Delivery(e.event().id(), e.listener(), e.status(), e.attempts(), e.nextAttemptAt(),
                        e.lastError().map(Throwable::toString)))
                .toList();
    }

    @Override
    protected Booking savedBooking() {
        return TestBookings.confirmed(clock);
    }
}
