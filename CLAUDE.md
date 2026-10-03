# booking-system

## This project

- Plain Java 27, no build tool. JUnit 6 via the standalone jar in `lib/`. Main-code dependencies: org.json (`lib/json-20260814.jar`, the Razorpay adapter) the PostgreSQL JDBC driver (`lib/postgresql-42.7.13.jar` + `lib/checker-qual-3.55.1.jar`), and HikariCP (`lib/HikariCP-7.1.0.jar` + `lib/slf4j-api-2.0.17.jar` + `lib/slf4j-jdk14-2.0.17.jar`) for connection pooling.
- Storage is chosen by `storage=` in `config/application.properties` (`in-memory` or `postgres`). PostgreSQL tests run only with `./test.sh --postgres` and need a running server.
- Main code in `src/`, tests in `test/`.
- Run the service: `./run.sh` (needs `secrets/razorpay.env` with test keys; prints the page URL, port from `PORT`, default 8080). Storage follows `config/application.properties`.
- Run tests: `./test.sh` (one class: `./test.sh --select-class ClassName`, one method: `./test.sh --select-method ClassName#methodName`)
- Live Razorpay test-mode checks: `./test.sh --live` loads keys from `secrets/razorpay.env` (git-ignored; `RAZORPAY_KEY_ID=rzp_test_…` and `RAZORPAY_KEY_SECRET=…`). Without `--live`, `RazorpayLiveTest` is skipped.
