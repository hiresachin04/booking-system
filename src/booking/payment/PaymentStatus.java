package booking.payment;

/** {@code RELEASED}: can never be captured, and the reserved money is being returned (voided, or expired unused). */
public enum PaymentStatus { AUTHORIZED, CAPTURED, RELEASED }
