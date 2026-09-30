package booking.support;

import booking.payment.CheckoutResult;
import booking.payment.CheckoutSession;
import booking.payment.RazorpaySignature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local, stateful stand-in for Razorpay's v1 API: the endpoints RazorpayPaymentGateway uses, with Razorpay's
 * rules for auth, manual capture, manual-expiry auto-refund, capture amounts and refund idempotency. Razorpay
 * publishes no mock server, so this is what the adapter's HTTP behaviour is tested against.
 */
public final class FakeRazorpayServer implements AutoCloseable {
    public static final String KEY_ID = "rzp_test_localfake";
    public static final String KEY_SECRET = "local_fake_secret";
    private static final long DEFAULT_MANUAL_EXPIRY_MINUTES = 7200;

    public record Recorded(String method, String path, Map<String, String> headers, String body) {}

    public record Refund(String paymentId, long amountPaise, String idempotencyKey) {}

    private static final class Payment {
        final String id;
        final String orderId;
        final long amount;
        final String currency;
        final Instant authorizedAt;
        final Duration manualExpiry;
        String status;
        boolean captured;
        long amountRefunded;
        String errorDescription;

        Payment(String id, String orderId, long amount, String currency, Instant authorizedAt, Duration manualExpiry, String status) {
            this.id = id;
            this.orderId = orderId;
            this.amount = amount;
            this.currency = currency;
            this.authorizedAt = authorizedAt;
            this.manualExpiry = manualExpiry;
            this.status = status;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject().put("id", id).put("entity", "payment").put("amount", amount)
                    .put("currency", currency).put("status", status).put("order_id", orderId).put("captured", captured)
                    .put("amount_refunded", amountRefunded);
            if (errorDescription != null) json.put("error_code", "BAD_REQUEST_ERROR").put("error_description", errorDescription);
            return json;
        }
    }

    private record Canned(String method, String pathPrefix, int status, String body) {}

    private record Stall(String method, String pathPrefix, Duration delay, boolean applyFirst) {}

    private record StoredRefund(String requestBody, JSONObject response) {}

    private final Clock clock;
    private final HttpServer server;
    private final ExecutorService executor;
    private final String expectedAuthorization;
    private final Map<String, JSONObject> orders = new HashMap<>();
    private final Map<String, Payment> payments = new HashMap<>();
    private final Map<String, String> authorizedPaymentByOrder = new HashMap<>();
    private final Map<String, StoredRefund> refundsByKey = new HashMap<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final List<Recorded> requests = new ArrayList<>();
    private int captures;
    private Canned cannedNext;
    private Stall stallNext;
    private String declineNextWith;

    public FakeRazorpayServer(Clock clock) throws IOException {
        this.clock = clock;
        this.expectedAuthorization = "Basic " + Base64.getEncoder()
                .encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // One thread per request: a stalled response (simulated timeout) must not hold up the client's next call.
        this.executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/", this::handle);
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/v1/");
    }

    /** The next request matching {@code method} and {@code pathPrefix} gets this response and has no effect. */
    public synchronized void respondNext(String method, String pathPrefix, int status, String body) {
        cannedNext = new Canned(method, pathPrefix, status, body);
    }

    /**
     * The next matching request is answered only after {@code delay}, long enough for the client to time out.
     * With {@code applyFirst} the request takes effect before the stall, so the effect happens but its response is lost.
     */
    public synchronized void stallNext(String method, String pathPrefix, Duration delay, boolean applyFirst) {
        stallNext = new Stall(method, pathPrefix, delay, applyFirst);
    }

    public synchronized void declineNextPayment(String reason) {
        declineNextWith = reason;
    }

    /** Plays the customer's browser in Razorpay Checkout and returns the signed result the handler receives. */
    public synchronized CheckoutResult simulateCheckout(CheckoutSession session) {
        JSONObject order = orders.get(session.orderId());
        if (order == null) throw new IllegalArgumentException("unknown order " + session.orderId());
        String paymentId = authorizedPaymentByOrder.get(session.orderId());
        if (paymentId == null) {
            paymentId = "pay_" + (payments.size() + 1);
            Payment payment = new Payment(paymentId, session.orderId(), order.getLong("amount"), order.getString("currency"),
                    clock.instant(), manualExpiry(order), declineNextWith == null ? "authorized" : "failed");
            if (declineNextWith != null) {
                payment.errorDescription = declineNextWith;
                declineNextWith = null;
            } else {
                authorizedPaymentByOrder.put(session.orderId(), paymentId);
            }
            payments.put(paymentId, payment);
        }
        return new CheckoutResult(session.orderId(), paymentId, RazorpaySignature.sign(KEY_SECRET, session.orderId(), paymentId));
    }

    public synchronized int captureCount() {
        return captures;
    }

    public synchronized List<Refund> refunds() {
        return List.copyOf(refunds);
    }

    public synchronized List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public synchronized String paymentStatus(String paymentId) {
        return current(payments.get(paymentId)).status;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
        Canned canned;
        Stall stall;
        synchronized (this) {
            requests.add(new Recorded(method, path, Map.copyOf(headers), body));
            canned = take(cannedNext, method, path) ? cannedNext : null;
            if (canned != null) cannedNext = null;
            stall = take(stallNext, method, path) ? stallNext : null;
            if (stall != null) stallNext = null;
        }
        if (canned != null) {
            respond(exchange, canned.status(), canned.body());
            return;
        }
        if (stall != null && !stall.applyFirst()) sleep(stall.delay());
        Response response;
        synchronized (this) {
            response = route(method, path, headers, body);
        }
        if (stall != null && stall.applyFirst()) sleep(stall.delay());
        respond(exchange, response.status, response.body.toString());
    }

    private record Response(int status, JSONObject body) {}

    private static boolean take(Object rule, String method, String path) {
        return switch (rule) {
            case Canned c -> c.method().equals(method) && path.startsWith(c.pathPrefix());
            case Stall s -> s.method().equals(method) && path.startsWith(s.pathPrefix());
            case null -> false;
            default -> throw new IllegalStateException("unknown rule " + rule);
        };
    }

    private Response route(String method, String path, Map<String, String> headers, String body) {
        if (!expectedAuthorization.equals(headers.get("authorization"))) {
            return error(401, "BAD_REQUEST_ERROR", "Authentication failed");
        }
        String[] parts = path.substring("/v1/".length()).split("/");
        if (method.equals("POST") && parts.length == 1 && parts[0].equals("orders")) return createOrder(new JSONObject(body));
        if (parts.length >= 2 && parts[0].equals("payments")) {
            Payment payment = current(payments.get(parts[1]));
            if (payment == null) return error(400, "BAD_REQUEST_ERROR", "The id provided does not exist");
            if (method.equals("GET") && parts.length == 2) return new Response(200, payment.toJson());
            if (method.equals("POST") && parts.length == 3 && parts[2].equals("capture")) return capture(payment, new JSONObject(body));
            if (method.equals("POST") && parts.length == 3 && parts[2].equals("refund")) {
                return refund(payment, headers.get("x-refund-idempotency"), body);
            }
        }
        return error(404, "NOT_FOUND", "no route for " + method + " " + path);
    }

    private Response createOrder(JSONObject request) {
        if (!request.has("amount") || !request.has("currency")) return error(400, "BAD_REQUEST_ERROR", "amount and currency are required");
        if (request.optString("receipt").length() > 40) return error(400, "BAD_REQUEST_ERROR", "receipt may not be greater than 40 characters");
        String id = "order_" + (orders.size() + 1);
        JSONObject order = new JSONObject(request.toString()).put("id", id).put("entity", "order").put("status", "created");
        orders.put(id, order);
        return new Response(200, order);
    }

    private Response capture(Payment payment, JSONObject request) {
        if (request.optLong("amount", -1) != payment.amount || !payment.currency.equals(request.optString("currency"))) {
            return error(400, "BAD_REQUEST_ERROR", "Capture amount must be equal to the amount authorized");
        }
        if (!payment.status.equals("authorized")) {
            return error(400, "BAD_REQUEST_ERROR", "This payment has already been captured or voided");
        }
        payment.status = "captured";
        payment.captured = true;
        captures++;
        return new Response(200, payment.toJson());
    }

    private Response refund(Payment payment, String key, String body) {
        if (key != null && !key.matches("[A-Za-z0-9_-]{10,}")) return error(400, "BAD_REQUEST_ERROR", "invalid idempotency key");
        if (key != null && refundsByKey.containsKey(key)) {
            StoredRefund earlier = refundsByKey.get(key);
            if (!earlier.requestBody().equals(body)) {
                return error(409, "BAD_REQUEST_ERROR", "Different request with the same idempotency key has already been processed");
            }
            return new Response(200, earlier.response());
        }
        if (!payment.captured) return error(400, "BAD_REQUEST_ERROR", "The payment has not been captured");
        long amount = new JSONObject(body).optLong("amount", payment.amount - payment.amountRefunded);
        if (amount <= 0 || amount > payment.amount - payment.amountRefunded) {
            return error(400, "BAD_REQUEST_ERROR", "The refund amount is greater than the refundable amount");
        }
        payment.amountRefunded += amount;
        if (payment.amountRefunded == payment.amount) payment.status = "refunded";
        JSONObject response = new JSONObject().put("id", "rfnd_" + (refunds.size() + 1)).put("entity", "refund")
                .put("payment_id", payment.id).put("amount", amount).put("status", "processed");
        refunds.add(new Refund(payment.id, amount, key));
        if (key != null) refundsByKey.put(key, new StoredRefund(body, response));
        return new Response(200, response);
    }

    // Razorpay refunds an authorized payment itself once the order's manual_expiry_period has passed.
    private Payment current(Payment payment) {
        if (payment != null && payment.status.equals("authorized")
                && !clock.instant().isBefore(payment.authorizedAt.plus(payment.manualExpiry))) {
            payment.status = "refunded";
        }
        return payment;
    }

    private static Duration manualExpiry(JSONObject order) {
        JSONObject options = order.optJSONObject("payment") == null ? null : order.getJSONObject("payment").optJSONObject("capture_options");
        long minutes = options == null ? DEFAULT_MANUAL_EXPIRY_MINUTES : options.optLong("manual_expiry_period", DEFAULT_MANUAL_EXPIRY_MINUTES);
        return Duration.ofMinutes(minutes);
    }

    private static Response error(int status, String code, String description) {
        return new Response(status, new JSONObject().put("error", new JSONObject().put("code", code).put("description", description)));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            // Server shutting down (shutdownNow): stop stalling and let the exchange close.
            Thread.currentThread().interrupt();
        }
    }
}
