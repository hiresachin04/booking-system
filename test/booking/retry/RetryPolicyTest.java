package booking.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyTest {
    @Test
    void doublesTheWaitAfterEachFailedAttempt() {
        RetryPolicy retry = new RetryPolicy(3, Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), retry.backoffAfter(1));
        assertEquals(Duration.ofSeconds(60), retry.backoffAfter(2));
    }

    @Test
    void backoffShiftIsCappedSoLargeAttemptCountsDoNotOverflow() {
        RetryPolicy many = new RetryPolicy(100, Duration.ofMillis(1));
        assertEquals(Duration.ofMillis(1L << 20), many.backoffAfter(99));
    }

    @Test
    void rejectsZeroAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ofSeconds(1)));
    }
}
