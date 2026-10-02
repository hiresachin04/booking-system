package booking.booking;

/** In memory every write lands immediately and nothing can roll back, so there is nothing to group. */
public final class InMemoryTransactions implements Transactions {
    @Override
    public void inTransaction(Runnable work) {
        work.run();
    }
}
