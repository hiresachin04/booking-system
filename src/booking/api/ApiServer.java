package booking.api;

import booking.booking.Booking;
import booking.booking.BookingService;
import booking.booking.IllegalBookingStateException;
import booking.booking.PaymentDeclinedException;
import booking.booking.PaymentPendingException;
import booking.booking.ShowAlreadyStartedException;
import booking.booking.ShowStore;
import booking.discount.DiscountRejectedException;
import booking.inventory.HoldExpiredException;
import booking.inventory.SeatsUnavailableException;
import booking.model.User;
import booking.payment.CheckoutResult;
import booking.payment.PaymentRejectedException;
import booking.payment.PaymentVerificationException;
import booking.payment.RazorpayUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The booking HTTP API and the checkout page, on the JDK's HTTP server.
 * <p>
 * Demo only, and unauthenticated: the caller names itself in {@code X-User-Id} and is trusted. That is why the
 * server binds to the loopback address, so nothing outside this machine can reach it.
 */
public final class ApiServer implements AutoCloseable {
    private static final Logger LOG = System.getLogger(ApiServer.class.getName());
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final HttpServer server;
    private final ExecutorService executor;
    private final BookingService bookings;
    private final ShowStore shows;
    private final Path page;

    private ApiServer(HttpServer server, ExecutorService executor, BookingService bookings, ShowStore shows, Path page) {
        this.server = server;
        this.executor = executor;
        this.bookings = bookings;
        this.shows = shows;
        this.page = page;
    }

    /** {@code port} 0 picks a free port; see {@link #port()}. */
    public static ApiServer start(BookingService bookings, ShowStore shows, Path page, int port) throws IOException {
        Objects.requireNonNull(bookings, "bookings");
        Objects.requireNonNull(shows, "shows");
        if (!Files.isRegularFile(page)) throw new IllegalStateException("checkout page not found: " + page.toAbsolutePath());
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        // Handlers block on the database and the payment gateway; virtual threads make that cheap.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        ApiServer api = new ApiServer(server, executor, bookings, shows, page);
        server.createContext("/", api::handle);
        server.start();
        return api;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public InetAddress address() {
        return server.getAddress().getAddress();
    }

    /** Stops accepting requests and gives those in progress a second to finish. */
    @Override
    public void close() {
        server.stop(1);
        executor.shutdown();
    }

    private record Response(int status, String contentType, String body) {
        static Response json(int status, JSONObject body) {
            return new Response(status, "application/json; charset=utf-8", body.toString());
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        Response response;
        try {
            response = route(exchange);
        } catch (RuntimeException e) {
            response = error(exchange, e);
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private Response route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            requireMethod(method, "GET");
            return new Response(200, "text/html; charset=utf-8", Files.readString(page, StandardCharsets.UTF_8));
        }
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        if (parts.length == 3 && parts[0].equals("api") && parts[1].equals("shows")) {
            requireMethod(method, "GET");
            return Response.json(200, ApiJson.show(shows.find(parts[2]).orElseThrow(() -> new NoSuchElementException("no show " + parts[2]))));
        }
        if (parts.length >= 2 && parts[0].equals("api") && parts[1].equals("bookings")) {
            User user = user(exchange);
            if (parts.length == 2) {
                requireMethod(method, "POST");
                return hold(user, body(exchange));
            }
            String bookingId = parts[2];
            if (parts.length == 3) {
                requireMethod(method, "GET");
                return Response.json(200, ApiJson.booking(bookings.find(user, bookingId)));
            }
            if (parts.length == 4) {
                requireMethod(method, "POST");
                return switch (parts[3]) {
                    case "confirm" -> confirm(user, bookingId, body(exchange));
                    case "release" -> Response.json(200, ApiJson.booking(bookings.release(user, bookingId)));
                    case "cancel" -> Response.json(200, ApiJson.booking(bookings.cancel(user, bookingId)));
                    default -> throw new ApiException(404, "NOT_FOUND", "no route " + path);
                };
            }
        }
        throw new ApiException(404, "NOT_FOUND", "no route " + path);
    }

    private Response hold(User user, JSONObject body) {
        String showId = requiredString(body, "showId");
        JSONArray seatArray = body.optJSONArray("seatIds");
        if (seatArray == null || seatArray.isEmpty()) throw new ApiException(400, "BAD_REQUEST", "seatIds must be a non-empty array");
        List<String> seatIds = new ArrayList<>(seatArray.length());
        for (int i = 0; i < seatArray.length(); i++) {
            if (!(seatArray.get(i) instanceof String seat)) throw new ApiException(400, "BAD_REQUEST", "seatIds must contain strings");
            seatIds.add(seat);
        }
        Booking booking = body.has("discountCode") && !body.isNull("discountCode")
                ? bookings.holdSeats(user, showId, seatIds, requiredString(body, "discountCode"))
                : bookings.holdSeats(user, showId, seatIds);
        return Response.json(201, ApiJson.booking(booking));
    }

    // The field names are what Razorpay Checkout's handler receives, so the page can post them through unchanged.
    private Response confirm(User user, String bookingId, JSONObject body) {
        CheckoutResult result = new CheckoutResult(requiredString(body, "razorpay_order_id"),
                requiredString(body, "razorpay_payment_id"), requiredString(body, "razorpay_signature"));
        try {
            return Response.json(200, ApiJson.booking(bookings.confirm(user, bookingId, result)));
        } catch (PaymentPendingException pending) {
            // Not a failure: the seats are kept while reconciliation settles the payment; the page polls the booking.
            return Response.json(202, ApiJson.booking(bookings.find(user, bookingId)));
        }
    }

    private static User user(HttpExchange exchange) {
        String id = exchange.getRequestHeaders().getFirst("X-User-Id");
        if (id == null || id.isBlank()) throw new ApiException(401, "UNAUTHENTICATED", "send your user id in X-User-Id");
        if (!USER_ID.matcher(id).matches()) throw new ApiException(400, "BAD_REQUEST", "X-User-Id must be 1-64 letters, digits, - or _");
        return new User(id, id);
    }

    private static JSONObject body(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) throw new ApiException(413, "TOO_LARGE", "request body over " + MAX_BODY_BYTES + " bytes");
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new ApiException(400, "BAD_REQUEST", "body must be a JSON object");
        }
    }

    private static String requiredString(JSONObject body, String field) {
        if (!(body.opt(field) instanceof String value) || value.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", field + " must be a non-empty string");
        }
        return value;
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) throw new ApiException(405, "METHOD_NOT_ALLOWED", "use " + expected);
    }

    private static Response error(HttpExchange exchange, RuntimeException e) {
        return switch (e) {
            case ApiException a -> Response.json(a.status(), ApiJson.error(a.code(), a.getMessage()));
            case SeatsUnavailableException s -> Response.json(409,
                    ApiJson.error("SEATS_UNAVAILABLE", s.getMessage()).put("seatIds", new JSONArray(s.seatIds())));
            case HoldExpiredException h -> Response.json(410, ApiJson.error("HOLD_EXPIRED", h.getMessage()));
            case PaymentVerificationException v -> Response.json(400, ApiJson.error("PAYMENT_NOT_VERIFIED", v.getMessage()));
            case PaymentDeclinedException d -> Response.json(402, ApiJson.error("PAYMENT_DECLINED", d.getMessage()));
            case PaymentRejectedException r -> Response.json(402, ApiJson.error("PAYMENT_FAILED", "the payment could not be completed"));
            case RazorpayUnavailableException u -> Response.json(503, ApiJson.error("PAYMENT_PROVIDER_UNAVAILABLE", "the payment provider can't be reached; try again"));
            case IllegalBookingStateException s -> Response.json(409, ApiJson.error("INVALID_STATE", s.getMessage()));
            case ShowAlreadyStartedException s -> Response.json(409, ApiJson.error("SHOW_STARTED", s.getMessage()));
            case DiscountRejectedException d -> Response.json(422, ApiJson.error("DISCOUNT_REJECTED", d.getMessage()));
            case NoSuchElementException n -> Response.json(404, ApiJson.error("NOT_FOUND", n.getMessage()));
            case IllegalArgumentException i -> Response.json(400, ApiJson.error("BAD_REQUEST", i.getMessage()));
            default -> {
                // Unexpected: full detail goes to the log, none of it to the caller.
                LOG.log(Logger.Level.ERROR, exchange.getRequestMethod() + " " + exchange.getRequestURI() + " failed", e);
                yield Response.json(500, ApiJson.error("INTERNAL_ERROR", "internal error"));
            }
        };
    }
}
