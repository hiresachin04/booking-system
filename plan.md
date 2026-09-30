# Movie Ticket Booking — Plain Java LLD

## Context
The draft `plan.md` in the repo describes a Spring Boot + DB REST app. You chose a **plain Java 27 in-memory low-level design** instead. It follows the project CLAUDE.md: no build tool, JUnit jar only, no new dependencies. Your three requirements:
1. Pricing uses the **Decorator** pattern.
2. **No two users can book the same seat.**
3. Other patterns go only where they fit.

`plan.md` will be rewritten to match this plan (Spring, DB and REST sections dropped). The saved HTML under `src/` stays where it is: `test.sh` only compiles `*.java`, so it does no harm.

## Package layout (`src/booking/...`, tests mirror in `test/booking/...`)
- `model`: `City`, `Theater` (has a `ZoneId`), `Screen`, `Seat(id, row, number, SeatTier)`, `Movie`, `Show(id, movie, screen, startsAt, Map<SeatTier,Long> tierPricePaise)`, `User`. These are records where the type is immutable.
- `pricing`: the Decorator chain.
- `inventory`: seat state per show. This is where double-booking is prevented.
- `booking`: `Booking`, `BookingStatus` (State), `BookingService` (Facade).
- `payment`: `PaymentGateway` (Strategy/port) and `PaymentResult`.
- `discount`: `DiscountCode` and `DiscountRegistry`.
- `notify`: `BookingListener` (Observer).

Money is always a `long` in paise. Percentages are `int` basis points. Time comes from an injected `java.time.Clock`.

## 1. Pricing: Decorator
```java
interface PriceComponent { long totalPaise(); List<PriceLine> lines(); }
record SeatBasePrice(Show show, List<Seat> seats) implements PriceComponent   // concrete component
abstract class PriceDecorator implements PriceComponent { protected final PriceComponent inner; }
class WeekendSurcharge extends PriceDecorator    // +bps of inner total
class PercentDiscount  extends PriceDecorator    // -bps of inner, optional max cap
class FlatDiscount     extends PriceDecorator    // -fixed paise, never below 0
```
- Each decorator adds one `PriceLine` to its inner component's lines. The breakdown shown to the user comes from this.
- Rounding is half-up and happens once per decorator: `(amount * bps + 5_000) / 10_000`. The multiply uses `Math.multiplyExact` so an overflow throws instead of wrapping.
- **Weak spot of Decorator: stacking order changes the result.** A 10% discount applied before a weekend surcharge gives a different total than after it. Callers therefore never stack decorators themselves. `PriceQuoteBuilder` (Builder) owns the order: base → weekend (only if the show's local date in the theater's zone is Sat/Sun) → discount (at most one code). The individual decorators are package-private.
- Weekend is a surcharge on top of any seat tier (this answers the old Q3). Seat tiers are `REGULAR` and `PREMIUM`.
- The price is quoted and stored on the `Booking` at hold time. Confirm authorizes and captures exactly that amount.

## 2. No double-booking: `ShowInventory`
- There is one `ShowInventory` per show, holding `Map<SeatId, SeatSlot>`. A slot is `AVAILABLE`, `HELD(holdId, expiresAt)` or `BOOKED(bookingId)`.
- **One `ReentrantLock` per show** guards every state change. I chose this over per-seat locks because a multi-seat hold has to be all-or-nothing. With per-seat locks, every caller would need to take locks in a sorted order to avoid deadlock. Contention only happens between users on the same show, which is exactly where we want them serialised.
- `hold(seatIds, holdId, ttl)`: under the lock, check **every** seat first. A seat can be taken if it is `AVAILABLE` or `HELD` with `expiresAt <= now`. Only then mark all of them. If any seat can't be taken, throw `SeatsUnavailableException` listing the conflicting seats; nothing is modified.
- `confirm(holdId, bookingId)`: under the lock, every seat must still be `HELD` by this `holdId` and not expired. Then all move to `BOOKED`. Otherwise throw `HoldExpiredException`.
- `release(holdId)` and `cancel(bookingId)` put the seats back to `AVAILABLE`.
- Expiry is **lazy**. An expired hold counts as available wherever it's read, so correctness never depends on a sweeper thread.
- Payment is **two-phase** and the slow step runs **outside** the lock: hold (lock) → authorize funds (no lock) → confirm seats (lock) → capture.
  - Hold expired during authorization: the authorization is voided and the booking marked `EXPIRED`. No money was taken, so a failed void only delays releasing the customer's funds (an uncaptured authorization lapses on its own). The void failure is attached to the thrown `HoldExpiredException` as suppressed.
  - Capture fails after the seats were booked: the seats are freed, the discount use is returned, the authorization is voided, and the booking goes to `PAYMENT_FAILED`. The capture error propagates.
  - Not handled: a capture that *succeeds* at the processor but times out on our side. Settling that needs a payment-status lookup (reconciliation), which the gateway interface doesn't offer yet.

## 3. Other patterns (only where they earn it)
| Pattern | Where | Why |
|---|---|---|
| State | `BookingStatus` enum with `canTransitionTo`: HELD→CONFIRMED/RELEASED/EXPIRED/PAYMENT_FAILED, CONFIRMED→CANCELLED | All illegal transitions are rejected in one place (`IllegalBookingStateException`). |
| Strategy | `PaymentGateway`, `RefundPolicy` (tiered by hours before show) | Swappable. `FakePaymentGateway` in `test/` is the only fake, because payment is the process boundary. |
| Facade | `BookingService`: `holdSeats`, `confirm`, `release`, `cancel` | Callers see one API. Inventory, pricing, payment, discounts and listeners stay behind it. |
| Observer | `BookingListener` (`onConfirmed`, `onCancelled`) registered on `BookingService` | Booking code doesn't know notifications exist. If a listener throws, the exception propagates *after* the state change is committed. It is not swallowed. |
| Builder | `PriceQuoteBuilder` | Fixes the decorator order (see §1). |
| Repository | In-memory `ShowRepository`, `BookingRepository` on `ConcurrentHashMap` | One concrete class each. No interface until a second implementation exists. |

Not used: Singleton (CLAUDE.md forbids it; everything is wired by constructor), a Factory per entity, Visitor.

Discount codes: each code has a validity window, a minimum order, and a total usage limit. Usage is consumed at **confirm** with a CAS loop on an `AtomicInteger` (`used < limit`), so two bookings can't both take the last use.

## 4. Steps (each ends with `./test.sh` green)
| Step | What | Tests that must fail if the code is wrong |
|---|---|---|
| 0 | Rewrite `plan.md`; add model records | Rejects a show with no price for a tier present in the screen |
| 1 | Pricing decorators + `PriceQuoteBuilder` | Weekend is decided by the theater's zone, not UTC (Sat 01:00 IST = Fri UTC); half-up rounding boundary (exact .5 paise); percent discount is capped; flat discount never goes below 0; the builder applies surcharge before discount (asserted total) |
| 2 | `ShowInventory` | Held seat → second hold fails; partial overlap [A1,A2] vs [A2,A3] → loser holds **nothing**; expired hold can be re-held; confirm after expiry fails; confirm with the wrong holdId fails; **20 threads with a `CountDownLatch` start gate race for one seat → exactly 1 succeeds**; overlapping multi-seat races → no seat ends up with two owners |
| 3 | `BookingStatus` + `BookingService` + `FakePaymentGateway` | Declined payment → booking stays HELD and seats stay held; authorization succeeds after hold expiry → voided, never captured, booking EXPIRED; capture failure → seats freed, PAYMENT_FAILED; confirm twice → rejected; hold on a show that has started → rejected; >10 seats → rejected |
| 4 | Discount codes | Expired code / below minimum order → rejected at hold; last use raced by 2 confirms → exactly one gets it |
| 5 | Cancel + `RefundPolicy` + `BookingListener` | Refund-tier boundary exactly at 24h; cancel after show start → rejected; cancelled seats can be held again; throwing listener → exception propagates and booking is still CONFIRMED |

## Verification
- `./test.sh` runs the full suite. Use `./test.sh --select-class booking.inventory.ShowInventoryTest` while iterating on the concurrency work.
- The concurrency tests loop the race (e.g. 200 iterations) to rule out a lucky pass. Mutation check before finishing: remove the lock in `ShowInventory.hold` and confirm the race test fails.
