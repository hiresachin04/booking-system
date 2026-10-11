# Movie Seat Booking

A small movie-ticket booking service you run on your own computer. Pick seats on a seat map, pay with Razorpay, and
the seats are yours. Nobody else can take them while you pay, and two people can never end up with the same seat.

It runs in **test mode**: payments go through Razorpay's test system with test cards, and **no real money moves**.

---

## 1. One-time setup

You need three things. Each only needs doing once.

**a) Java 27**

Open the Terminal app and type:

```
java -version
```

You should see `java version "27"`. If not, install Java 27 from [oracle.com/java](https://www.oracle.com/java/technologies/downloads/).

**b) Razorpay test keys**

1. Sign up (free) at [dashboard.razorpay.com](https://dashboard.razorpay.com) and switch to **Test** mode.
2. Go to **Account & Settings → API Keys → Generate Key**, and download the key (the secret is shown only once).
3. In the project folder, create the file `secrets/razorpay.env` containing your two values:

RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxx
RAZORPAY_KEY_SECRET=xxxxxxxxxxxxxxxxxxxx

Use a Razorpay test card to pay (see section 3).

The key id must start with `rzp_test_`. The service refuses to start with live keys. This file is never uploaded
   anywhere and is ignored by git.

**c) Optional: a database, so bookings are kept**

Out of the box, bookings are kept in memory and **disappear when you stop the service**. To keep them:

1. Install [Postgres.app](https://postgresapp.com), open it, click **Initialize**, then **Start**.
2. In Terminal, create the database (once):

   ```
   /Applications/Postgres.app/Contents/Versions/latest/bin/psql -h localhost -d postgres -c "CREATE ROLE booking LOGIN" -c "CREATE DATABASE booking OWNER booking"
   ```

3. Open `config/application.properties` and change `storage=in-memory` to `storage=postgres`.

---

## 2. Start the service

In Terminal, go to the project folder and run:

```
./run.sh
```

After a few seconds you'll see something like:

```
Booking demo (storage=in-memory) at http://localhost:8080/?show=demo-2026-10-04
Discount code to try: WELCOME50. Ctrl-C to stop.
```

Open that link in your browser. Leave the Terminal window open: the service runs as long as it does, and it's where
booking confirmations appear.

> The service only accepts connections from your own computer. Other people on your network can't reach it.

---

## 3. Book seats

1. **Pick seats** on the seat map by clicking them; click again to unselect. Gold-bordered seats are premium. Grey
   seats are taken or being paid for by someone else.
2. **Discount (optional):** type `WELCOME50` in the discount box for ₹50 off.
3. Click **Hold & pay**. Your seats are now held for you for **10 minutes**, and the page shows the price breakdown.
4. The **Razorpay** payment window opens. Pay with a test card:

   | Field | What to enter |
   |---|---|
   | Card number | `4100 2800 0000 1007` (Visa) or `5555 5100 0008 1006` (Mastercard) |
   | Expiry | any future date |
   | CVV | any 3 digits |
   | OTP, if asked | any 4–10 digits (fewer than 4 digits makes the payment fail, if you want to try that) |

5. When the page says **"Booked: …"**, you're done. The Terminal window shows a `[notify] confirmed` line: that's the
   confirmation a real system would email you.

If you close the Razorpay window without paying, your seats are released straight away.

---

## 4. Prices and rules

| | |
|---|---|
| Regular seat (rows A–C) | ₹200 |
| Premium seat (rows D–E) | ₹350 |
| Weekend shows (Saturday or Sunday) | +10% |
| `WELCOME50` discount | ₹50 off; up to 100 uses |
| Seats per booking | up to 10 |
| Hold while you pay | 10 minutes |

There's one demo show: tomorrow at 7:30 pm (India time), *Dune: Part Two* at PVR Forum, Bengaluru. Starting the
service on another day adds that day's "tomorrow" show.

### Cancelling and refunds

A booked ticket has a **Cancel booking** button. How much you get back depends on how long before the show you cancel:

| Cancelled | Refund |
|---|---|
| 24 hours or more before the show | 100% |
| 4 to 24 hours before | 50% |
| Less than 4 hours before | nothing |
| After the show has started | can't cancel |

For example, a ₹440 booking cancelled 22 hours before the show gets ₹220 back.

---

## 5. What if…

**…I closed the tab, or my phone went to sleep, right after paying?**
Nothing is lost. Within about 1–2 minutes the service notices the payment with Razorpay and books your seats anyway.
The confirmation still appears in the Terminal window.

**…I paid, but only after my 10 minutes ran out?**
The seats may be someone else's by then, so the payment is never taken: you're not charged, and any amount your
bank set aside is returned. A "payment failed" notice appears in Terminal.

**…the page says "Confirming your payment…" and waits?**
Razorpay didn't answer straight away. Your seats are kept for you while the service checks, and the page updates by
itself. You don't need to do anything.

**…a seat is grey?**
Someone else has booked it, or is paying for it right now. A seat being paid for frees up again if that person
doesn't finish within 10 minutes.

**…two of us are testing?**
Each browser counts as a different customer (the page remembers "you" in that browser). To act as a second person,
use a different browser or a private window.

**…I want to see what's in the database?**
With the database set up (step 1c), run:

```
/Applications/Postgres.app/Contents/Versions/latest/bin/psql -h localhost -U booking -d booking -f db/queries.sql
```

It lists shows, seats, bookings with totals and refunds, discount usage and notifications. It only reads; never edit
rows by hand.

---

## 6. Stop the service

Click the Terminal window running it and press **Ctrl-C**. It finishes what it's doing and shuts down cleanly.

---

## For developers

**Layout**

| Folder | What's there |
|---|---|
| `src/booking/model` | Shows, screens, seats, movies (immutable records) |
| `src/booking/pricing` | Prices as a chain of decorators: seat price → weekend surcharge → discount |
| `src/booking/inventory` | Seat state per show: the no-double-booking rules (in-memory and PostgreSQL) |
| `src/booking/booking` | `BookingService` (hold, confirm, release, cancel, reconcile), booking states, storage |
| `src/booking/payment` | `PaymentGateway` and the Razorpay adapter (two-phase: authorize, then capture) |
| `src/booking/notify` | Notification outbox, with retries and backoff |
| `src/booking/db` | PostgreSQL connections (HikariCP), transactions, advisory locks, migrations |
| `src/booking/api` | HTTP API on the JDK's HTTP server |
| `src/booking/app` | `Main`, background jobs, storage factory, demo data |
| `web/index.html` | The booking page |
| `db/migrations` | Database schema, applied automatically at startup |
| `test/` | Tests, incl. a local fake of Razorpay's API (`FakeRazorpayServer`) |

**Commands**

```
./test.sh              # all tests (no network, no database needed)
./test.sh --postgres   # also the PostgreSQL tests (needs the database from step 1c)
./test.sh --live       # also checks against Razorpay test mode (needs secrets/razorpay.env)
./run.sh               # build and start the service (PORT=9000 ./run.sh for another port)
```

**Configuration** is in `config/application.properties`: `storage` (`in-memory` or `postgres`), the PostgreSQL URL
and user, and the two connection-pool sizes. Secrets never go there: the Razorpay keys come from
`secrets/razorpay.env`, and a database password, if you set one, from the `POSTGRES_PASSWORD` environment variable.

**Design notes** and known limitations are in [`plan.md`](plan.md). In short:
- **Seats:** a seat can never have two owners, even with several copies of the service running.
- **Money:** it's taken only after the seats are secured.
- **Unknown outcomes:** every network call whose outcome is unknown is settled later from Razorpay's own records.
- **Crashes:** the service recovers from a crash at any point during a payment.

**Not built yet:** real logins (the page identifies you with an `X-User-Id` header, which is why the service only
listens on your own computer), an admin screen for adding shows, and Razorpay webhooks.
