package booking.app;

import booking.booking.Booking;
import booking.notify.BookingEvent;
import booking.notify.BookingListener;

import java.io.PrintStream;
import java.util.Objects;

/** Prints booking notifications to the terminal; where a deployment would plug in email or SMS. */
public final class ConsoleNotifier implements BookingListener {
    private final PrintStream out;

    public ConsoleNotifier(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public String name() {
        return "console";
    }

    @Override
    public void onConfirmed(BookingEvent event) {
        print("confirmed", event, "seats " + String.join(", ", event.booking().seatIds()) + ", paid " + rupees(event.booking().price().totalPaise()));
    }

    @Override
    public void onCancelled(BookingEvent event) {
        print("cancelled", event, "refund " + rupees(event.booking().refundedPaise()));
    }

    @Override
    public void onPaymentFailed(BookingEvent event) {
        print("payment failed", event, "no seats; any amount held is returned");
    }

    @Override
    public void onPaymentRefunded(BookingEvent event) {
        print("refunded", event, "seats were lost, refunded " + rupees(event.booking().refundedPaise()));
    }

    private void print(String what, BookingEvent event, String detail) {
        Booking booking = event.booking();
        out.println("[notify] " + what + ": booking " + booking.id() + " for " + booking.user().id() + ", " + detail
                + " (event " + event.id() + ")");
    }

    private static String rupees(long paise) {
        return "₹" + paise / 100 + "." + String.format("%02d", paise % 100);
    }
}
