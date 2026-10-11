# Skills used in this project

What went into building the booking system, grouped by area, with where each one shows up in the code.

---

## 1. Problem framing and design
- **Turning requirements into invariants** that must never break: no seat sold twice; no payment without seats, and
  no seats without payment; survive timeouts, closed tabs and crashes.
- **Writing assumptions down up front:** money in paise, a 10-seat limit, a 10-minute hold, the weekend surcharge
  judged in the theater's time zone, one discount per booking, the refund tiers. See `plan.md`.
- **Low-level design over framework-first:** moved from a Spring Boot plan to plain Java so the design stays visible.
- **Building in layers,** each working and tested before the next: domain → payments → failure handling →
  persistence → API and UI.
- **Back-of-the-envelope estimation:** booking rates, storage growth, payment-latency bottlenecks, connection limits,
  polling cost. See `docs/video-walkthrough.md`.

## 2. Object-oriented design and patterns
- **Decorator** for pricing, and a **Builder** that fixes the decorator order (`src/booking/pricing`).
- **State** for the booking lifecycle, with every transition rule in one place (`BookingStatus`).
- **Strategy** for the payment gateway, refund policy and storage implementations.
- **Adapter** for Razorpay (`RazorpayPaymentGateway`).
- **Facade:** `BookingService` as the single entry point.
- **Observer + Transactional Outbox** for notifications (`src/booking/notify`).
- **Repository and Factory** for switchable storage (`Storage`, `StorageFactory`).
- **Composition root and constructor injection** (`BookingSystem`, `Main`); no singletons.
- **Modern Java modelling:** records, sealed interfaces, pattern-matching `switch`, `Optional` only for return values.

## 3. Concurrency
- **All-or-nothing seat holds** under one lock per show (`ShowInventory`).
- **A per-booking lock,** so a double-clicked confirm can't charge twice (`BookingLocks`).
- **Lock-free compare-and-set** for discount usage limits (`DiscountCode`).
- **Deadlock avoidance:** rows locked in a fixed order, and separate connection pools for locks and for queries.
- **Virtual threads** for request handlers that block on a database or the payment API.
- **Race testing:** 20 threads released from a single latch at the same moment (`Race`).

## 4. Reliability and distributed-systems techniques
- **Two-phase payment:** authorize, secure the seats, then capture.
- **Idempotency keys** for payments and refunds, and idempotent confirms.
- **Settling unknown outcomes from the source of truth:** after a timeout, ask the processor instead of guessing.
- **Retries with exponential backoff and a cap,** treating permanent failures differently from transient ones.
- **Crash recovery by checkpoint:** a `PAYMENT_PENDING` booking is saved before anything irreversible, and the
  reconciler finishes it.
- **Reconciliation jobs:** pending payments, plus payments the browser never reported (closed-tab recovery).
- **A transactional outbox** with at-least-once delivery, stable event ids for deduplication, leases and
  `SKIP LOCKED`.
- **Scheduled jobs that survive their own failures** (`ScheduledJob`).

## 5. Payments domain
- **Razorpay Orders, Checkout, signature verification (HMAC-SHA256), manual capture, idempotent refunds,** and test
  mode.
- **Designing around processor quirks:** no void, and auto-refund after an expiry window.
- **Money as integer paise,** with basis-point maths rounded half-up and overflow-checked.
- **A tiered refund policy, and a pricing breakdown** shown to the customer.

## 6. Databases (PostgreSQL)
- **Schema design where constraints enforce the rules:** one row per seat, `CHECK` constraints on usage limits and
  status consistency.
- **Pessimistic locking** (`SELECT … FOR UPDATE` in a fixed order), **conditional updates**, **advisory locks** and
  **`SKIP LOCKED` queues**.
- **Transactions joined across stores,** so a booking change and its notification commit together.
- **A hand-written migration runner:** ordered, transactional, guarded against concurrent runs, and refusing to
  run old code against a newer schema (`SchemaMigrator`).
- **Connection pooling with HikariCP,** sized and split to avoid starving lock holders.
- **Exact timestamps** stored as epoch nanoseconds, and **JSONB** for catalog documents.
- **Operational SQL** for inspecting shows, bookings, payments and the outbox (`db/queries.sql`).

## 7. API and web
- **A REST API on the JDK's HTTP server,** with domain errors mapped to HTTP statuses and stable error codes.
- **Input validation,** request-size limits, and generic 500s that don't leak internal details.
- **A checkout page in plain JavaScript** integrated with Razorpay Checkout, rendering untrusted data safely as text,
  never raw HTML.
- **A thin, testable entry point** (`Main.start`) and run scripts (`run.sh`).

## 8. Security
- **Secrets kept out of code and git:** git-ignored `secrets/`, environment variables, a guard that accepts only
  test keys, and secrets masked in `toString`.
- **Secret scanning before every commit and push,** which caught real keys in an uncommitted README edit.
- **Evaluated encrypted secrets in the repo** (sops + age), then reverted at the owner's request.
- **Constant-time signature comparison,** and never trusting the browser for payment status or amount.
- **Ownership checks** that answer 404, not 403, so booking ids can't be probed.
- **Binding to loopback only** while authentication is a demo header.
- **Supply chain:** every dependency jar checked against Maven Central's SHA-1, and the dependency count kept
  minimal.

## 9. Testing
- **Contract tests:** the same tests run against in-memory and PostgreSQL storage, and against three payment
  gateways.
- **Fakes only at the process boundary,** including a local, stateful fake of Razorpay's HTTP API
  (`FakeRazorpayServer`).
- **A controllable clock** for exact time boundaries (`MutableClock`).
- **Crash simulation** (`SimulatedCrash`), race tests, and throwaway database schemas per test.
- **Mutation testing by hand:** removing each guarantee and confirming a test fails. This found tests that couldn't
  fail, and one real bug.
- **Layered verification:** offline suite → `--postgres` → `--live` against Razorpay test mode → a manual end-to-end
  payment in the browser.

## 10. Tooling and workflow
- **A plain-Java build** with `javac` and bash scripts (`test.sh`, `run.sh`, `scripts/common.sh`), with no build tool.
- **IntelliJ module configuration** kept in step with the jars.
- **Git:** a feature branch, one commit per tested step with messages explaining *why*, fast-forward to `main`, and
  a pre-push history scan for secrets.
- **Local operations:** Postgres.app, `psql`, `curl` against the Razorpay API, and inspecting processes and ports.

## 11. Documentation and communication
- **A README for non-technical users** (setup, booking, rules, "what if…"), plus a developer section.
- **Design notes and known limitations,** kept current in `plan.md`.
- **A timed video script** for presenting the project (`docs/video-walkthrough.md`).

---

## AI-assisted development (Claude Code)

**How it was used**
- **A working agreement in `CLAUDE.md`:**
  - plan before large changes and wait for approval;
  - no dependency without asking;
  - integer money;
  - no mocking of own types;
  - every test must be able to fail.
- **Plan mode and written plans,** approved before implementation. Product decisions stayed with the developer:
  refund rules, retry counts, releasing seats after a timeout, payment provider, dependencies.
- **Clarifying questions** when two readings of a request were possible: build tool, storage, connection pool,
  secrets handling, what to publish.
- **Web research before building:** Razorpay's API docs (no void, capture requirements, refund idempotency rules,
  fetching an order's payments, test cards), and whether stripe-mock would help. It wouldn't: it's stateless.
- **Verification habits:** full test runs before each commit, deliberate mutations to prove the tests, and secret
  scans before publishing.

**Tools used:** shell commands, reading and editing files, web search and fetch, and questions for decisions. No
packaged Claude Code skills (such as `/code-review` or `/run-tests`) were invoked in this project.
