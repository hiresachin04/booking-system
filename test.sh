#!/usr/bin/env bash
# Compile src/ and test/, then run all JUnit tests.
set -euo pipefail
cd "$(dirname "$0")"

JUNIT=lib/junit-platform-console-standalone-6.1.3.jar
# Main-code dependencies, each verified against Maven Central's SHA-1 when added: org.json for the Razorpay
# adapter; the PostgreSQL JDBC driver and checker-qual, the only jar it needs at runtime; HikariCP for connection
# pooling, with slf4j-api and slf4j-jdk14 so its warnings reach java.util.logging instead of being discarded.
LIBS=lib/json-20260814.jar:lib/postgresql-42.7.13.jar:lib/checker-qual-3.55.1.jar:lib/HikariCP-7.1.0.jar:lib/slf4j-api-2.0.17.jar:lib/slf4j-jdk14-2.0.17.jar
BUILD=build

# --live loads Razorpay test keys from secrets/razorpay.env so RazorpayLiveTest runs against Razorpay's test mode.
# Opt-in so the normal suite never needs the network.
ARGS=()
for arg in "$@"; do
  if [[ "$arg" == --postgres ]]; then
    # Runs the PostgreSQL tests against postgres.url/postgres.user in config/application.properties, each in a
    # throwaway schema. Opt-in, because it needs a running server.
    export POSTGRES_TESTS=1
  elif [[ "$arg" == --live ]]; then
    if [[ ! -f secrets/razorpay.env ]]; then echo "--live needs secrets/razorpay.env (see CLAUDE.md)" >&2; exit 1; fi
    set -a; source secrets/razorpay.env; set +a
    if [[ "${RAZORPAY_KEY_ID:-}" != rzp_test_* ]]; then echo "refusing --live: RAZORPAY_KEY_ID is not a test key" >&2; exit 1; fi
  else
    ARGS+=("$arg")
  fi
done

rm -rf "$BUILD"
mkdir -p "$BUILD/main" "$BUILD/test"
# javac fails when given no files, so an empty src/ or test/ is skipped rather than treated as an error.
MAIN_SOURCES=$(find src -name '*.java')
TEST_SOURCES=$(find test -name '*.java')
if [[ -n "$MAIN_SOURCES" ]]; then javac -d "$BUILD/main" -cp "$LIBS" $MAIN_SOURCES; fi
if [[ -n "$TEST_SOURCES" ]]; then javac -d "$BUILD/test" -cp "$BUILD/main:$LIBS:$JUNIT" $TEST_SOURCES; fi
# JUnit rejects --scan-classpath combined with explicit selectors, so scan only when none are given.
SELECT=(--scan-classpath)
for arg in "${ARGS[@]+"${ARGS[@]}"}"; do
  if [[ "$arg" == --select* ]]; then SELECT=(); fi
done
java -jar "$JUNIT" execute -cp "$BUILD/main:$BUILD/test:$LIBS" --disable-banner "${SELECT[@]+"${SELECT[@]}"}" "${ARGS[@]+"${ARGS[@]}"}"
