#!/usr/bin/env bash
# Compile src/ and test/, then run all JUnit tests.
set -euo pipefail
cd "$(dirname "$0")"

source scripts/common.sh
JUNIT=lib/junit-platform-console-standalone-6.1.3.jar
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
    load_razorpay_test_keys --live
  else
    ARGS+=("$arg")
  fi
done

# Only the test build is cleared: run.sh builds into $BUILD/run, which a running server is still loading classes from.
rm -rf "$BUILD/main" "$BUILD/test"
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
