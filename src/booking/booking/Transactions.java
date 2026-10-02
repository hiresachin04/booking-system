package booking.booking;

/**
 * Makes a group of store writes all-or-nothing, e.g. a booking's new status together with its outbox event.
 * Never wrap a network call (payment gateway) in one: it would hold the transaction open for the call's duration.
 */
public interface Transactions {
    void inTransaction(Runnable work);
}
