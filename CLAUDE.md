# booking-system

## This project

- Plain Java 27, no build tool. JUnit 6 via the standalone jar in `lib/`. The only main-code dependency is org.json (`lib/json-20260814.jar`), used by the Razorpay adapter.
- Main code in `src/`, tests in `test/`.
- Run tests: `./test.sh` (one class: `./test.sh --select-class ClassName`, one method: `./test.sh --select-method ClassName#methodName`)
- Live Razorpay test-mode checks: `./test.sh --live` loads keys from `secrets/razorpay.env` (git-ignored; `RAZORPAY_KEY_ID=rzp_test_…` and `RAZORPAY_KEY_SECRET=…`). Without `--live`, `RazorpayLiveTest` is skipped.
