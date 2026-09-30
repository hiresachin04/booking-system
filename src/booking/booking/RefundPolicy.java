package booking.booking;

import java.time.Duration;

public interface RefundPolicy {
    long refundPaise(long paidPaise, Duration noticeBeforeShow);
}
