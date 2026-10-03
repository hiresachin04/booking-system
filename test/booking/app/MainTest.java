package booking.app;

import booking.booking.Booking;
import booking.booking.TestBookings;
import booking.notify.BookingEvent;
import booking.notify.BookingEventType;
import booking.support.FakeRazorpayServer;
import booking.support.MutableClock;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole service as run.sh starts it, on in-memory storage and a local fake of Razorpay. */
class MainTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T06:30:00Z"));
    private final FakeRazorpayServer razorpay = start();
    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<String, String> env = Map.of("RAZORPAY_KEY_ID", FakeRazorpayServer.KEY_ID,
            "RAZORPAY_KEY_SECRET", FakeRazorpayServer.KEY_SECRET, "RAZORPAY_API_URL", razorpay.baseUrl().toString());
    private Main.Running running;

    private FakeRazorpayServer start() {
        try {
            return new FakeRazorpayServer(clock);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @AfterEach
    void stop() {
        if (running != null) running.close();
        client.close();
        razorpay.close();
    }

    private static Properties inMemory() {
        return StorageFactory.load(Path.of("config/application.properties"));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesThePageAndTheSeededDemoShow() throws Exception {
        running = Main.start(inMemory(), env, clock, 0, System.out);

        assertEquals("demo-2026-10-02", running.showId());
        assertTrue(running.url().endsWith(":" + running.api().port() + "/?show=demo-2026-10-02"), running.url());
        assertEquals(200, send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.api().port() + "/"))).statusCode());
        HttpResponse<String> show = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.api().port() + "/api/shows/" + running.showId())));
        assertEquals(200, show.statusCode());
        assertEquals(50, new JSONObject(show.body()).getJSONArray("seats").length());
    }

    @Test
    void theAdvertisedDiscountCodeWorksOnTheDemoShow() throws Exception {
        running = Main.start(inMemory(), env, clock, 0, System.out);

        HttpResponse<String> hold = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.api().port() + "/api/bookings"))
                .header("X-User-Id", "alice")
                .POST(HttpRequest.BodyPublishers.ofString("{\"showId\":\"" + running.showId() + "\",\"seatIds\":[\"A1\"],\"discountCode\":\"WELCOME50\"}")));

        assertEquals(201, hold.statusCode(), hold.body());
        assertEquals(15_000, new JSONObject(hold.body()).getJSONObject("price").getLong("totalPaise"));
    }

    @Test
    void seedingTwiceKeepsTheExistingShowAndCode() throws Exception {
        running = Main.start(inMemory(), env, clock, 0, System.out);

        assertEquals(running.showId(), DemoCatalog.seed(running.system().shows(), running.system().discounts(), clock));
    }

    @Test
    void refusesToStartWithoutRazorpayKeys() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Main.start(inMemory(), Map.of(), clock, 0, System.out));
        assertEquals("RAZORPAY_KEY_ID is not set", e.getMessage());
    }

    @Test
    void consoleNotifierPrintsTheBookingTheSeatsAndTheAmount() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConsoleNotifier notifier = new ConsoleNotifier(new PrintStream(out, true, StandardCharsets.UTF_8));
        Booking confirmed = TestBookings.confirmed(clock);

        notifier.onConfirmed(new BookingEvent("evt-1", BookingEventType.CONFIRMED, confirmed));

        String line = out.toString(StandardCharsets.UTF_8);
        assertTrue(line.startsWith("[notify] confirmed: booking " + confirmed.id() + " for u1, seats A1, paid ₹200.00"), line);
        assertTrue(line.contains("(event evt-1)"), line);
    }
}
