package booking.payment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * {@link PaymentGateway} over Razorpay's REST API (v1), with orders created for manual capture.
 * Error mapping, which decides between "retry" and "give up" upstream:
 * <ul>
 *   <li>400: Razorpay refused the request, so {@link PaymentRejectedException} (a verify call treats it as a bad result).</li>
 *   <li>409, 429, 5xx, timeouts, I/O errors: the outcome is unknown, so {@link RazorpayUnavailableException}.</li>
 *   <li>401: our credentials are wrong, so {@link IllegalStateException}; no retry fixes configuration.</li>
 * </ul>
 */
public final class RazorpayPaymentGateway implements PaymentGateway {
    /** {@code manualExpiry}: how long an uncaptured payment stays open before Razorpay refunds it itself. */
    public record Config(URI baseUrl, String keyId, String keySecret, String currency, Duration manualExpiry, Duration requestTimeout) {
        public static final URI LIVE_API = URI.create("https://api.razorpay.com/v1/");
        private static final long MAX_MANUAL_EXPIRY_MINUTES = 7200;

        public Config {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(keySecret, "keySecret");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(manualExpiry, "manualExpiry");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (!baseUrl.getPath().endsWith("/")) throw new IllegalArgumentException("baseUrl must end with /: " + baseUrl);
            long minutes = manualExpiry.toMinutes();
            if (minutes < 1 || minutes > MAX_MANUAL_EXPIRY_MINUTES || !manualExpiry.equals(Duration.ofMinutes(minutes))) {
                throw new IllegalArgumentException("manualExpiry must be whole minutes in 1.." + MAX_MANUAL_EXPIRY_MINUTES + ": " + manualExpiry);
            }
        }

        /**
         * Reads {@code RAZORPAY_KEY_ID} and {@code RAZORPAY_KEY_SECRET}, and optionally {@code RAZORPAY_API_URL} (default:
         * Razorpay's API; tests point it at a local fake). The 20-minute expiry outlasts the default 15-minute pending
         * window, so capture retries finish before Razorpay gives the money back.
         */
        public static Config fromEnvironment(Map<String, String> env) {
            String api = env.get("RAZORPAY_API_URL");
            URI baseUrl = api == null || api.isBlank() ? LIVE_API : URI.create(api.strip());
            return new Config(baseUrl, require(env, "RAZORPAY_KEY_ID"), require(env, "RAZORPAY_KEY_SECRET"), "INR",
                    Duration.ofMinutes(20), Duration.ofSeconds(10));
        }

        private static String require(Map<String, String> env, String name) {
            String value = env.get(name);
            if (value == null || value.isBlank()) throw new IllegalStateException(name + " is not set");
            return value;
        }

        // The generated record toString would print the secret into any log line that includes the config.
        @Override
        public String toString() {
            return "Config[baseUrl=" + baseUrl + ", keyId=" + keyId + ", keySecret=***, currency=" + currency
                    + ", manualExpiry=" + manualExpiry + ", requestTimeout=" + requestTimeout + "]";
        }
    }

    private final HttpClient http;
    private final Config config;
    private final String authorization;

    public RazorpayPaymentGateway(HttpClient http, Config config) {
        this.http = Objects.requireNonNull(http, "http");
        this.config = Objects.requireNonNull(config, "config");
        String credentials = config.keyId() + ":" + config.keySecret();
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CheckoutSession startPayment(String reference, String userId, long amountPaise) {
        JSONObject body = new JSONObject()
                .put("amount", amountPaise)
                .put("currency", config.currency())
                .put("receipt", reference)
                .put("notes", new JSONObject().put("user_id", userId))
                .put("payment", new JSONObject()
                        .put("capture", "manual")
                        .put("capture_options", new JSONObject()
                                .put("manual_expiry_period", config.manualExpiry().toMinutes())
                                .put("refund_speed", "normal")));
        JSONObject order = send(post("orders", body));
        return new CheckoutSession(order.getString("id"), order.getLong("amount"), order.getString("currency"), config.keyId());
    }

    @Override
    public PaymentResult verifyCheckout(CheckoutSession session, CheckoutResult result) {
        // Checked locally first: a forged result never costs a network call or reveals anything about real payments.
        if (!RazorpaySignature.matches(config.keySecret(), result)) throw new PaymentVerificationException("signature mismatch");
        if (!result.orderId().equals(session.orderId())) {
            throw new PaymentVerificationException("result is for order " + result.orderId() + ", not " + session.orderId());
        }
        JSONObject payment;
        try {
            payment = send(get("payments/" + result.paymentId()));
        } catch (PaymentRejectedException unknownPayment) {
            throw new PaymentVerificationException("no such payment " + result.paymentId() + ": " + unknownPayment.getMessage());
        }
        if (!session.orderId().equals(payment.optString("order_id"))) {
            throw new PaymentVerificationException("payment " + result.paymentId() + " is not for order " + session.orderId());
        }
        if (payment.getLong("amount") != session.amountPaise() || !session.currency().equals(payment.getString("currency"))) {
            throw new PaymentVerificationException("paid " + payment.getLong("amount") + " " + payment.getString("currency")
                    + ", expected " + session.amountPaise() + " " + session.currency());
        }
        return switch (payment.getString("status")) {
            case "authorized", "captured" -> new PaymentResult.Approved(result.paymentId());
            case "failed" -> new PaymentResult.Declined(payment.optString("error_description", "payment failed"));
            case "refunded" -> new PaymentResult.Declined("authorization expired and was refunded");
            case "created" -> new PaymentResult.Declined("payment not completed");
            default -> throw new IllegalStateException("unknown Razorpay payment status " + payment.getString("status"));
        };
    }

    @Override
    public void capture(String authorizationId, long amountPaise) {
        send(post("payments/" + authorizationId + "/capture",
                new JSONObject().put("amount", amountPaise).put("currency", config.currency())));
    }

    /** Razorpay has no void; an uncaptured payment is refunded by Razorpay after {@link Config#manualExpiry()}. */
    @Override
    public boolean tryVoid(String authorizationId) {
        return false;
    }

    @Override
    public PaymentStatus status(String authorizationId) {
        JSONObject payment = send(get("payments/" + authorizationId));
        return switch (payment.getString("status")) {
            case "authorized" -> PaymentStatus.AUTHORIZED;
            case "captured" -> PaymentStatus.CAPTURED;
            // Fully refunded: it was captured if Razorpay says so; otherwise the manual expiry returned the money.
            case "refunded" -> payment.getBoolean("captured") ? PaymentStatus.CAPTURED : PaymentStatus.RELEASED;
            case "failed" -> PaymentStatus.RELEASED;
            default -> throw new IllegalStateException("payment " + authorizationId + " is " + payment.getString("status")
                    + ", which a verified authorization can't be");
        };
    }

    @Override
    public void refund(String idempotencyKey, String paymentId, long amountPaise) {
        HttpRequest request = request("payments/" + paymentId + "/refund")
                .header("X-Refund-Idempotency", idempotencyKey)
                .POST(json(new JSONObject().put("amount", amountPaise).put("speed", "normal")))
                .build();
        send(request);
    }

    private HttpRequest post(String path, JSONObject body) {
        return request(path).POST(json(body)).build();
    }

    private HttpRequest get(String path) {
        return request(path).GET().build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(config.baseUrl().resolve(path))
                .timeout(config.requestTimeout())
                .header("Authorization", authorization)
                .header("Content-Type", "application/json");
    }

    private static HttpRequest.BodyPublisher json(JSONObject body) {
        return HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
    }

    private JSONObject send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RazorpayUnavailableException(request.method() + " " + request.uri().getPath() + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RazorpayUnavailableException(request.method() + " " + request.uri().getPath() + " interrupted", e);
        }
        int code = response.statusCode();
        if (code >= 200 && code < 300) return parse(request, response.body());
        String error = describeError(response.body());
        String where = request.method() + " " + request.uri().getPath() + " -> " + code + " " + error;
        if (code == 400) throw new PaymentRejectedException(where);
        if (code == 401) throw new IllegalStateException("Razorpay rejected our API credentials: " + where);
        throw new RazorpayUnavailableException(where, null);
    }

    private static JSONObject parse(HttpRequest request, String body) {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            // A 2xx we can't read: the call may well have taken effect, so this is "outcome unknown", not a rejection.
            throw new RazorpayUnavailableException("unreadable response to " + request.method() + " " + request.uri().getPath(), e);
        }
    }

    private static String describeError(String body) {
        try {
            JSONObject error = new JSONObject(body).getJSONObject("error");
            return error.optString("code") + ": " + error.optString("description");
        } catch (JSONException e) {
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        }
    }
}
