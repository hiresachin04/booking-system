package booking.api;

import booking.booking.BookingConfig;
import booking.booking.BookingService;
import booking.booking.InMemoryStorage;
import booking.booking.TieredRefundPolicy;
import booking.notify.NotificationOutbox;
import booking.payment.CheckoutResult;
import booking.payment.CheckoutSession;
import booking.payment.RazorpayPaymentGateway;
import booking.retry.RetryPolicy;
import booking.support.FakeRazorpayServer;
import booking.support.Fixtures;
import booking.support.MutableClock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The API over real HTTP, wired to the real Razorpay adapter talking HTTP to FakeRazorpayServer. */
class ApiServerTest {
    private static final Instant NOW = Instant.parse("2026-10-01T06:30:00Z");

    private record Reply(int status, String contentType, String body) {
        JSONObject json() {
            return new JSONObject(body);
        }
    }

    private final MutableClock clock = new MutableClock(NOW);
    private final FakeRazorpayServer razorpay = startRazorpay();
    private final HttpClient http = HttpClient.newHttpClient();
    private final InMemoryStorage storage = new InMemoryStorage(clock);
    private final ApiServer api = startApi();

    private FakeRazorpayServer startRazorpay() {
        try {
            return new FakeRazorpayServer(clock);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private ApiServer startApi() {
        storage.shows().add(Fixtures.show("sh", NOW.plus(Duration.ofHours(8))));
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway(http, new RazorpayPaymentGateway.Config(
                razorpay.baseUrl(), FakeRazorpayServer.KEY_ID, FakeRazorpayServer.KEY_SECRET, "INR", Duration.ofMinutes(20), Duration.ofMillis(500)));
        BookingService service = new BookingService(storage, gateway, new TieredRefundPolicy(List.of(new TieredRefundPolicy.Tier(Duration.ofHours(4), 5_000))),
                new BookingConfig(Duration.ofMinutes(10), 10, 0, Duration.ofMinutes(15), BookingConfig.DEFAULT_CAPTURE_RETRY), clock,
                new NotificationOutbox(List.of(), new RetryPolicy(3, Duration.ofSeconds(30)), Duration.ofDays(1), clock));
        try {
            return ApiServer.start(service, storage.shows(), Path.of("web/index.html"), 0);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @AfterEach
    void stop() {
        api.close();
        http.close();
        razorpay.close();
    }

    private Reply call(String method, String path, String user, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) request.header("X-User-Id", user);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body());
        } catch (IOException | InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private JSONObject hold(String user, String... seats) {
        Reply reply = call("POST", "/api/bookings", user, new JSONObject().put("showId", "sh").put("seatIds", new JSONArray(List.of(seats))).toString());
        assertEquals(201, reply.status(), reply.body());
        return reply.json();
    }

    private static CheckoutSession session(JSONObject booking) {
        JSONObject c = booking.getJSONObject("checkout");
        return new CheckoutSession(c.getString("orderId"), c.getLong("amountPaise"), c.getString("currency"), c.getString("keyId"));
    }

    private static String confirmBody(CheckoutResult paid) {
        return new JSONObject().put("razorpay_order_id", paid.orderId()).put("razorpay_payment_id", paid.paymentId())
                .put("razorpay_signature", paid.signature()).toString();
    }

    private String seatStatus(String seatId) {
        JSONArray seats = call("GET", "/api/shows/sh", null, null).json().getJSONArray("seats");
        for (int i = 0; i < seats.length(); i++) {
            if (seats.getJSONObject(i).getString("id").equals(seatId)) return seats.getJSONObject(i).getString("status");
        }
        throw new AssertionError("no seat " + seatId);
    }

    // --- shows and the page ---

    @Test
    void servesTheSeatMapWithPricesAndLiveStatus() {
        Reply reply = call("GET", "/api/shows/sh", null, null);

        assertEquals(200, reply.status());
        assertTrue(reply.contentType().startsWith("application/json"));
        JSONObject show = reply.json();
        assertEquals("Dune", show.getJSONObject("movie").getString("title"));
        assertEquals(12, show.getJSONArray("seats").length());
        JSONObject a1 = show.getJSONArray("seats").getJSONObject(0);
        assertEquals("A1", a1.getString("id"));
        assertEquals(20_000, a1.getLong("pricePaise"));
        assertEquals("AVAILABLE", a1.getString("status"));
    }

    @Test
    void unknownShowIs404() {
        Reply reply = call("GET", "/api/shows/nope", null, null);
        assertEquals(404, reply.status());
        assertEquals("NOT_FOUND", reply.json().getString("error"));
    }

    @Test
    void servesTheCheckoutPageWithoutAnyKeySecret() {
        Reply reply = call("GET", "/", null, null);

        assertEquals(200, reply.status());
        assertTrue(reply.contentType().startsWith("text/html"));
        assertTrue(reply.body().contains("checkout.razorpay.com/v1/checkout.js"));
        assertFalse(reply.body().contains(FakeRazorpayServer.KEY_SECRET));
    }

    @Test
    void listensOnLoopbackOnlyBecauseTheUserHeaderIsTrusted() {
        assertTrue(api.address().isLoopbackAddress(), api.address().toString());
    }

    // --- the booking flow ---

    @Test
    void holdReturnsTheBookingWithItsCheckoutButNeverTheSecret() {
        JSONObject booking = hold("alice", "A1", "B1");

        assertEquals("HELD", booking.getString("status"));
        assertEquals(55_000, booking.getJSONObject("price").getLong("totalPaise"));
        assertEquals(FakeRazorpayServer.KEY_ID, booking.getJSONObject("checkout").getString("keyId"));
        assertFalse(booking.toString().contains(FakeRazorpayServer.KEY_SECRET));
        assertEquals("HELD", seatStatus("A1"));
    }

    @Test
    void secondUserHoldingTheSameSeatGets409NamingTheSeat() {
        hold("alice", "A1");

        Reply reply = call("POST", "/api/bookings", "bob", "{\"showId\":\"sh\",\"seatIds\":[\"A1\",\"A2\"]}");

        assertEquals(409, reply.status());
        assertEquals("SEATS_UNAVAILABLE", reply.json().getString("error"));
        assertEquals(List.of("A1"), reply.json().getJSONArray("seatIds").toList());
        assertEquals("AVAILABLE", seatStatus("A2"));
    }

    @Test
    void confirmWithThePaidCheckoutBooksTheSeats() {
        JSONObject booking = hold("alice", "A1");
        CheckoutResult paid = razorpay.simulateCheckout(session(booking));

        Reply reply = call("POST", "/api/bookings/" + booking.getString("id") + "/confirm", "alice", confirmBody(paid));

        assertEquals(200, reply.status(), reply.body());
        assertEquals("CONFIRMED", reply.json().getString("status"));
        assertEquals("BOOKED", seatStatus("A1"));
        assertEquals(1, razorpay.captureCount());
    }

    @Test
    void forgedSignatureIs400AndTheHoldIsKept() {
        JSONObject booking = hold("alice", "A1");
        CheckoutResult paid = razorpay.simulateCheckout(session(booking));
        CheckoutResult forged = new CheckoutResult(paid.orderId(), paid.paymentId(), "0".repeat(64));

        Reply reply = call("POST", "/api/bookings/" + booking.getString("id") + "/confirm", "alice", confirmBody(forged));

        assertEquals(400, reply.status());
        assertEquals("PAYMENT_NOT_VERIFIED", reply.json().getString("error"));
        assertEquals("HELD", call("GET", "/api/bookings/" + booking.getString("id"), "alice", null).json().getString("status"));
    }

    @Test
    void lostCaptureResponseStillConfirms() {
        JSONObject booking = hold("alice", "A1");
        CheckoutResult paid = razorpay.simulateCheckout(session(booking));
        razorpay.stallNext("POST", "/capture", Duration.ofSeconds(2), true);

        Reply reply = call("POST", "/api/bookings/" + booking.getString("id") + "/confirm", "alice", confirmBody(paid));

        assertEquals(200, reply.status(), reply.body());
        assertEquals("CONFIRMED", reply.json().getString("status"));
    }

    @Test
    void unknownCaptureOutcomeIs202WithTheSeatsStillHeld() {
        JSONObject booking = hold("alice", "A1");
        CheckoutResult paid = razorpay.simulateCheckout(session(booking));
        razorpay.respondNext("POST", "/v1/payments/" + paid.paymentId() + "/capture", 503, "{\"error\":{\"code\":\"SERVER_ERROR\"}}");

        Reply reply = call("POST", "/api/bookings/" + booking.getString("id") + "/confirm", "alice", confirmBody(paid));

        assertEquals(202, reply.status(), reply.body());
        assertEquals("PAYMENT_PENDING", reply.json().getString("status"));
        assertEquals("BOOKED", seatStatus("A1"));
    }

    @Test
    void releaseFreesTheSeats() {
        JSONObject booking = hold("alice", "A1");

        Reply reply = call("POST", "/api/bookings/" + booking.getString("id") + "/release", "alice", null);

        assertEquals(200, reply.status());
        assertEquals("RELEASED", reply.json().getString("status"));
        assertEquals("AVAILABLE", seatStatus("A1"));
    }

    @Test
    void cancelRefundsByPolicy() {
        JSONObject booking = hold("alice", "A1");
        call("POST", "/api/bookings/" + booking.getString("id") + "/confirm", "alice", confirmBody(razorpay.simulateCheckout(session(booking))));

        Reply reply = call("POST", "/api/bookings/" + booking.getString("id") + "/cancel", "alice", null);

        assertEquals(200, reply.status(), reply.body());
        assertEquals("CANCELLED", reply.json().getString("status"));
        assertEquals(10_000, reply.json().getLong("refundedPaise"));
        assertEquals(1, razorpay.refunds().size());
    }

    @Test
    void wrongStateIs409() {
        JSONObject booking = hold("alice", "A1");

        Reply reply = call("POST", "/api/bookings/" + booking.getString("id") + "/cancel", "alice", null);

        assertEquals(409, reply.status());
        assertEquals("INVALID_STATE", reply.json().getString("error"));
    }

    // --- who may do what ---

    @Test
    void anotherUsersBookingReadsAsNotFound() {
        JSONObject booking = hold("alice", "A1");

        assertEquals(404, call("GET", "/api/bookings/" + booking.getString("id"), "bob", null).status());
        assertEquals(404, call("POST", "/api/bookings/" + booking.getString("id") + "/release", "bob", null).status());
        assertEquals("HELD", seatStatus("A1"));
    }

    @Test
    void bookingCallsWithoutAUserAre401() {
        Reply reply = call("POST", "/api/bookings", null, "{\"showId\":\"sh\",\"seatIds\":[\"A1\"]}");

        assertEquals(401, reply.status());
        assertEquals("UNAUTHENTICATED", reply.json().getString("error"));
    }

    @Test
    void rejectsAUserIdThatIsNotAPlainToken() {
        assertEquals(400, call("GET", "/api/bookings/x", "alice smith", null).status());
    }

    // --- malformed requests ---

    @Test
    void malformedJsonIs400() {
        Reply reply = call("POST", "/api/bookings", "alice", "{not json");
        assertEquals(400, reply.status());
        assertEquals("BAD_REQUEST", reply.json().getString("error"));
    }

    @Test
    void missingOrEmptySeatsIs400() {
        assertEquals(400, call("POST", "/api/bookings", "alice", "{\"showId\":\"sh\"}").status());
        assertEquals(400, call("POST", "/api/bookings", "alice", "{\"showId\":\"sh\",\"seatIds\":[]}").status());
        assertEquals(400, call("POST", "/api/bookings", "alice", "{\"showId\":\"sh\",\"seatIds\":[1]}").status());
    }

    @Test
    void seatNotInTheShowIs400() {
        Reply reply = call("POST", "/api/bookings", "alice", "{\"showId\":\"sh\",\"seatIds\":[\"Z9\"]}");
        assertEquals(400, reply.status());
    }

    @Test
    void oversizedBodyIs413() {
        Reply reply = call("POST", "/api/bookings", "alice", "{\"pad\":\"" + "x".repeat(70_000) + "\"}");
        assertEquals(413, reply.status());
    }

    @Test
    void wrongMethodIs405() {
        assertEquals(405, call("DELETE", "/api/shows/sh", null, null).status());
        assertEquals(405, call("GET", "/api/bookings", "alice", null).status());
    }

    @Test
    void unknownRouteIs404() {
        assertEquals(404, call("GET", "/api/nope", null, null).status());
        assertEquals(404, call("POST", "/api/bookings/x/explode", "alice", null).status());
    }

    @Test
    void unexpectedFailureIs500WithoutLeakingDetails() {
        razorpay.respondNext("POST", "/v1/orders", 401, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Authentication failed\"}}");

        Reply reply = call("POST", "/api/bookings", "alice", "{\"showId\":\"sh\",\"seatIds\":[\"A1\"]}");

        assertEquals(500, reply.status());
        assertEquals(new JSONObject().put("error", "INTERNAL_ERROR").put("message", "internal error").toString(), reply.json().toString());
        assertEquals("AVAILABLE", seatStatus("A1"));
    }
}
