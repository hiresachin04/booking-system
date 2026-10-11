# Booking System: How I Built It (video script)

About 1,400 words, roughly 9½ minutes at a normal speaking pace. Lines in *[brackets]* are optional on-screen cues,
not things to say.

---

### [0:00] Intro (~20s)
Hi. In the next few minutes I'll walk through how I approached this booking-system assignment, the tech stack and why
I chose it, how I worked with an AI coding assistant, and how I tested it.

---

### 1. Approach and high-level solution (~2m30s)

**[0:20] Starting point**

The assignment was a movie ticket booking system: customers pick seats, pay, and get tickets. Before writing any code I
pinned down the three things that must never go wrong:
1. **No seat is ever sold twice**, however many people click at once.
2. **Nobody pays without getting seats**, and nobody gets seats without paying.
3. **The system survives real failures:** a payment provider that times out, a customer who closes the tab mid-payment,
   a server that crashes halfway through.

**[1:00] How I worked through it**

My first plan was a full Spring Boot app with a database. I changed course to a plain-Java low-level design, because
the interesting part of this problem is the design itself: locking, payment ordering, failure recovery. I didn't want
a framework hiding that.

Then I built it in layers, each one working and tested before the next:
1. **The domain:** pricing, plus seat holds that are all-or-nothing.
2. **Payments**, built around one rule: **reserve the money, secure the seats, then take the money.** The customer
   authorizes in Razorpay Checkout, we verify, we book the seats, and only then do we capture. If the seats are lost in
   between, nothing was charged.
3. **Failure handling.** If a capture times out, we don't guess; we ask Razorpay what happened. If the server crashes
   mid-payment, a checkpoint lets a background reconciler finish the job. If the customer pays and closes the tab, the
   reconciler finds the payment and confirms it anyway.
4. **Persistence.** Everything sits behind storage interfaces, so one config line switches between **in-memory and
   PostgreSQL**.
5. **A small HTTP API and a checkout page**, so it can be used end to end.

*[show the README's "What if…" section]*

The guiding principle throughout: **never trust the browser, and treat the payment processor as the source of truth.**

---

### 2. Tech stack and reasoning (~2m)

**[2:50]**

*[show the `lib/` folder]*

**Java 27, plain, with no framework and no build tool:** just `javac` and a test script. Modern Java gave me records,
sealed interfaces and pattern matching for a clean domain model, plus **virtual threads**, which are ideal for request
handlers that block on a database or a payment API.

**JUnit 6**, as a single standalone jar.

**The JDK's built-in HTTP server and HTTP client** for the API and for calling Razorpay. No web framework.

**PostgreSQL 18**, locally through Postgres.app, with the official **JDBC driver** and **HikariCP** for connection
pooling. PostgreSQL gives me the guarantees the domain needs: row locks for seats, conditional updates for discount
limits, advisory locks for bookings, and `SKIP LOCKED` for the notification queue.

**Razorpay in test mode** for payments. I picked it over Stripe because the target is India, with rupees and UPI.
Razorpay has quirks I had to design for: there's no way to cancel an authorization, and uncaptured payments are
refunded automatically after an expiry window.

**org.json** for JSON, and **plain JavaScript** for the checkout page.

**The reasoning behind the stack:**
- **Every dependency had to justify itself.** The whole project has six runtime jars, and each was checked against
  Maven Central's checksum before it went in.
- **I chose what to write myself.** I wrote a small schema migrator instead of using Flyway. And Razorpay doesn't
  publish a mock server, so I wrote a fake one for tests.
- **The trade-off:** more hand-written plumbing than with Spring, in exchange for code where every lock, transaction
  and retry is visible.

---

### 3. AI workflow (~2m45s)

**[4:50]**

I built this with **Claude Code** as a pair programmer. The workflow mattered more than the tool, so let me describe it.

**A written working agreement.** I keep a `CLAUDE.md` file with rules the AI must follow:
- write a plan before any change touching more than three files, and wait for my approval;
- never add a dependency without asking;
- store money as integers, never floating point;
- don't mock types I own, only external systems like the payment provider;
- every test must be able to fail. If there's no way to break the code that makes the test fail, the test is
  worthless.

**Plans first, decisions mine.** For every significant feature the AI wrote a plan, and I approved or changed it. The
product decisions were mine:
- what the refund policy is;
- whether seats are released after a payment has been stuck for 15 minutes (I chose yes);
- how many capture retries (three);
- which dependencies to allow, and which payment provider to use.

**Research before building.** Before the Razorpay integration, the AI read Razorpay's API docs and found things that
changed the design: there's no void, captures must include the amount, and refund idempotency keys only allow certain
characters. That last one meant the keys I was already using would have been rejected in production. It also checked
whether a Stripe mock server would help, found it's stateless, and so wouldn't exercise any of our failure handling.

**One commit per verified step.** Every change was committed only after the full test suite passed. The history is
about forty small commits, each explaining *why*, not just what.

**Checking the AI's work.** This is the part I'd emphasise. Because of the "every test must be able to fail" rule, the
AI routinely broke its own code on purpose to prove the tests caught it, and several times they didn't:
- a concurrency test had quietly stopped testing the lock after an earlier refactor;
- one assertion it wrote could never have passed. It caught that before running it;
- a planned set of tests missed a real bug: a payment found *after the show had started* would have been charged. A
  surviving mutation exposed it, and it was fixed.

The AI writes code fast. Plans, rules and deliberate code-breaking are what made it trustworthy.

---

### 4. Testing approach (~2m)

**[7:35]**

There are about **450 tests**, built on a few principles.

**Contract tests, not copies.** The same tests run against every implementation: in-memory and PostgreSQL storage, and
three payment gateways. One of those is the **real Razorpay adapter talking real HTTP to a fake Razorpay server** that
follows Razorpay's documented rules.

**Fake only at the process boundary.** The payment provider and notifications are faked. Everything I own runs for
real, including PostgreSQL, where each test class gets a fresh schema that's dropped afterwards.

**Controlled time.** A test clock lets tests jump forward to exact boundaries: a hold expires at exactly ten minutes, a
refund tier starts at exactly 24 hours. Times are stored to the nanosecond for that reason.

**Concurrency and crashes.** Race tests send 20 threads at one seat. Crash tests throw a fatal error mid-capture to
simulate the process dying, then check that recovery finishes the booking.

**Proving the tests can fail.** For every important guarantee I removed the code that provides it, and the tests caught
it:
- remove the seat row lock, and **six customers got the same seat**;
- remove `SKIP LOCKED` from the outbox, and **20 notifications were delivered 80 times**;
- remove the payment checkpoint, and a crash **leaves a charged customer's booking stuck in `HELD`**.

**Layers of verification:**
- `./test.sh` runs with no network and no database;
- `--postgres` adds the database tests;
- `--live` runs a few checks against Razorpay's real test environment;
- finally, a manual end-to-end test: I booked seats in the browser, paid with a Razorpay test card, then cancelled 22
  hours before the show and got exactly 50% back, as the policy says.

---

### [9:35] Close (~15s)
To sum up: a small system with strong guarantees around seats and money, a deliberately minimal stack, an AI workflow
built on plans and verification, and tests that are proven to fail when they should. Thanks for watching.

---

## Recording tips
- **If you run long**, trim the stack list in section 2 to Java, PostgreSQL and Razorpay. That saves about 30 seconds.
- **Commit and test counts:** "about forty commits" and "about 450 tests" match the repo as of writing
  (`git rev-list --count main..booking-lld` and `./test.sh --postgres`). Re-check them before recording if anything
  changes.
- **Likely follow-up: "what would you change for production?"** Razorpay webhooks instead of polling, real
  authentication, an admin API for shows, and PgBouncer in front of PostgreSQL.
