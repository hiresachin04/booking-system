#!/usr/bin/env bash
# Compile src/ and test/, then run all JUnit tests.
set -euo pipefail
cd "$(dirname "$0")"

JUNIT=lib/junit-platform-console-standalone-6.1.3.jar
# Main code's only dependency (the Razorpay adapter's JSON); verified against Maven Central's SHA-1 when added.
JSON=lib/json-20260814.jar
BUILD=build

SECRETS=secrets/razorpay.enc.env
SOPS=$(command -v sops || echo "$HOME/.local/bin/sops")

# Anything named *.enc.env is committable, so each one must really be sops-encrypted, never plaintext.
for f in secrets/*.enc.env; do
  if [[ -f "$f" ]] && ! grep -q '^sops_mac=' "$f"; then echo "$f is not sops-encrypted; refusing to continue" >&2; exit 1; fi
done

# --live decrypts the Razorpay test keys so RazorpayLiveTest runs against Razorpay's test mode.
# Opt-in so the normal suite never needs the network or the age key.
ARGS=()
for arg in "$@"; do
  if [[ "$arg" == --live ]]; then
    if [[ ! -x "$SOPS" ]]; then echo "--live needs sops (see CLAUDE.md)" >&2; exit 1; fi
    DECRYPTED=$("$SOPS" decrypt "$SECRETS")
    # Exported line by line rather than sourced, so nothing in a value is ever run as shell.
    while IFS='=' read -r name value; do
      if [[ -z "$name" || "$name" == \#* ]]; then continue; fi
      export "$name=$value"
    done <<< "$DECRYPTED"
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
if [[ -n "$MAIN_SOURCES" ]]; then javac -d "$BUILD/main" -cp "$JSON" $MAIN_SOURCES; fi
if [[ -n "$TEST_SOURCES" ]]; then javac -d "$BUILD/test" -cp "$BUILD/main:$JSON:$JUNIT" $TEST_SOURCES; fi
# JUnit rejects --scan-classpath combined with explicit selectors, so scan only when none are given.
SELECT=(--scan-classpath)
for arg in "${ARGS[@]+"${ARGS[@]}"}"; do
  if [[ "$arg" == --select* ]]; then SELECT=(); fi
done
java -jar "$JUNIT" execute -cp "$BUILD/main:$BUILD/test:$JSON" --disable-banner "${SELECT[@]+"${SELECT[@]}"}" "${ARGS[@]+"${ARGS[@]}"}"
