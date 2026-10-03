# Shared by test.sh and run.sh; sourced, not run.

# Main-code dependencies, each verified against Maven Central's SHA-1 when added: org.json for the Razorpay
# adapter; the PostgreSQL JDBC driver and checker-qual, the only jar it needs at runtime; HikariCP for connection
# pooling, with slf4j-api and slf4j-jdk14 so its warnings reach java.util.logging instead of being discarded.
LIBS=lib/json-20260814.jar:lib/postgresql-42.7.13.jar:lib/checker-qual-3.55.1.jar:lib/HikariCP-7.1.0.jar:lib/slf4j-api-2.0.17.jar:lib/slf4j-jdk14-2.0.17.jar

# Exports the Razorpay keys from secrets/razorpay.env, refusing anything but a test-mode key so a live key in that
# file can never be used by tests or the demo. $1 names the caller in error messages.
load_razorpay_test_keys() {
  if [[ ! -f secrets/razorpay.env ]]; then echo "$1 needs secrets/razorpay.env (see CLAUDE.md)" >&2; exit 1; fi
  set -a; source secrets/razorpay.env; set +a
  if [[ "${RAZORPAY_KEY_ID:-}" != rzp_test_* ]]; then echo "refusing $1: RAZORPAY_KEY_ID is not a test key" >&2; exit 1; fi
}
