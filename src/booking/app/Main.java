package booking.app;

import booking.api.ApiServer;
import booking.booking.BookingConfig;
import booking.booking.Storage;
import booking.booking.TieredRefundPolicy;
import booking.payment.RazorpayPaymentGateway;

import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Starts the booking service: storage from config/application.properties, Razorpay keys from the environment,
 * the demo show, the background jobs and the HTTP API. Ctrl-C stops it cleanly.
 */
public final class Main {
    private static final Path CONFIG = Path.of("config/application.properties");
    private static final Path PAGE = Path.of("web/index.html");
    private static final int DEFAULT_PORT = 8080;

    /** A started service; closing it stops the API, then the jobs and storage, then the HTTP client. */
    public record Running(ApiServer api, BookingSystem system, HttpClient http, String showId) implements AutoCloseable {
        public String url() {
            return "http://localhost:" + api.port() + "/?show=" + showId;
        }

        @Override
        public void close() {
            try {
                api.close();
            } finally {
                try {
                    system.close();
                } finally {
                    http.close();
                }
            }
        }
    }

    private Main() {}

    public static void main(String[] args) throws IOException {
        Map<String, String> env = System.getenv();
        int port = env.containsKey("PORT") ? Integer.parseInt(env.get("PORT").strip()) : DEFAULT_PORT;
        Properties config = StorageFactory.load(CONFIG);
        Running running = start(config, env, Clock.systemUTC(), port, System.out);
        Runtime.getRuntime().addShutdownHook(new Thread(running::close, "shutdown"));
        System.out.println("Booking demo (storage=" + config.getProperty(StorageFactory.STORAGE_KEY).strip() + ") at " + running.url());
        System.out.println("Discount code to try: " + DemoCatalog.DISCOUNT_CODE + ". Ctrl-C to stop.");
    }

    /** Everything main does except reading the process environment, so tests can start the real thing. */
    public static Running start(Properties config, Map<String, String> env, Clock clock, int port, PrintStream notifications) throws IOException {
        RazorpayPaymentGateway.Config razorpay = RazorpayPaymentGateway.Config.fromEnvironment(env);
        HttpClient http = HttpClient.newHttpClient();
        Storage storage;
        try {
            storage = StorageFactory.create(config, env, clock);
        } catch (RuntimeException e) {
            http.close();
            throw e;
        }
        BookingConfig booking = new BookingConfig(Duration.ofMinutes(10), 10, 1_000, Duration.ofMinutes(15), BookingConfig.DEFAULT_CAPTURE_RETRY);
        TieredRefundPolicy refunds = new TieredRefundPolicy(List.of(
                new TieredRefundPolicy.Tier(Duration.ofHours(24), 10_000),
                new TieredRefundPolicy.Tier(Duration.ofHours(4), 5_000)));
        BookingSystem system;
        try {
            system = BookingSystem.start(BookingSystem.Settings.withDefaults(booking, refunds), storage,
                    new RazorpayPaymentGateway(http, razorpay), List.of(new ConsoleNotifier(notifications)), clock);
        } catch (RuntimeException e) {
            storage.close();
            http.close();
            throw e;
        }
        try {
            String showId = DemoCatalog.seed(system.shows(), system.discounts(), clock);
            return new Running(ApiServer.start(system.bookingService(), system.shows(), PAGE, port), system, http, showId);
        } catch (IOException | RuntimeException e) {
            system.close();
            http.close();
            throw e;
        }
    }
}
