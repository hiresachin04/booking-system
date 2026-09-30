# booking-system

## This project

- Plain Java 27, no build tool. JUnit 6 via the standalone jar in `lib/`. The only main-code dependency is org.json (`lib/json-20260814.jar`), used by the Razorpay adapter.
- Main code in `src/`, tests in `test/`.
- Run tests: `./test.sh` (one class: `./test.sh --select-class ClassName`, one method: `./test.sh --select-method ClassName#methodName`)
- Live Razorpay test-mode checks: `./test.sh --live` decrypts `secrets/razorpay.enc.env` with sops + age (committed encrypted; recipients in `.sops.yaml`). Needs `sops` on PATH or in `~/.local/bin`, and your age key at `~/Library/Application Support/sops/age/keys.txt`. Change keys with `sops edit secrets/razorpay.enc.env`; plaintext files in `secrets/` are git-ignored. Without `--live`, `RazorpayLiveTest` is skipped.
