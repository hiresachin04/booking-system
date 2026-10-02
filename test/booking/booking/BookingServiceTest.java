package booking.booking;

/** BookingService on in-memory storage. */
class BookingServiceTest extends BookingServiceContract {
    @Override
    protected Storage newStorage() {
        return new InMemoryStorage(clock);
    }
}
