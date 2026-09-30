package booking.pricing;

import java.util.List;

public interface PriceComponent {
    long totalPaise();

    List<PriceLine> lines();
}
