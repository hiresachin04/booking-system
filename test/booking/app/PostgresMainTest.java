package booking.app;

import booking.support.FakeRazorpayServer;
import booking.support.MutableClock;
import booking.support.PostgresTestDatabase;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The whole service on PostgreSQL: what it was doing survives a restart. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "1")
class PostgresMainTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T06:30:00Z"));
    private final PostgresTestDatabase database = PostgresTestDatabase.empty();
    private final FakeRazorpayServer razorpay = start();
    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<String, String> env = Map.of("RAZORPAY_KEY_ID", FakeRazorpayServer.KEY_ID,
            "RAZORPAY_KEY_SECRET", FakeRazorpayServer.KEY_SECRET, "RAZORPAY_API_URL", razorpay.baseUrl().toString());

    private FakeRazorpayServer start() {
        try {
            return new FakeRazorpayServer(clock);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @AfterEach
    void stop() {
        client.close();
        razorpay.close();
        database.close();
    }

    private Properties postgres() {
        Properties properties = StorageFactory.load(Path.of("config/application.properties"));
        properties.setProperty("storage", "postgres");
        properties.setProperty("postgres.url", database.database().settings().url());
        return properties;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aHoldMadeBeforeARestartIsStillThereAfterIt() throws Exception {
        String bookingId;
        String showId;
        try (Main.Running first = Main.start(postgres(), env, clock, 0, System.out)) {
            showId = first.showId();
            HttpResponse<String> hold = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + first.api().port() + "/api/bookings"))
                    .header("X-User-Id", "alice")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"showId\":\"" + showId + "\",\"seatIds\":[\"C3\"]}")).build());
            assertEquals(201, hold.statusCode(), hold.body());
            bookingId = new JSONObject(hold.body()).getString("id");
        }

        try (Main.Running second = Main.start(postgres(), env, clock, 0, System.out)) {
            assertEquals(showId, second.showId());
            HttpResponse<String> booking = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + second.api().port() + "/api/bookings/" + bookingId))
                    .header("X-User-Id", "alice").build());
            assertEquals(200, booking.statusCode(), booking.body());
            assertEquals("HELD", new JSONObject(booking.body()).getString("status"));
            HttpResponse<String> taken = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + second.api().port() + "/api/bookings"))
                    .header("X-User-Id", "bob")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"showId\":\"" + showId + "\",\"seatIds\":[\"C3\"]}")).build());
            assertEquals(409, taken.statusCode());
        }
    }
}
