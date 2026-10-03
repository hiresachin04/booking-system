#!/usr/bin/env bash
# Builds src/ and starts the booking service on http://localhost:${PORT:-8080}; Ctrl-C stops it.
# Storage comes from config/application.properties; Razorpay test keys from secrets/razorpay.env (test keys only).
set -euo pipefail
cd "$(dirname "$0")"
source scripts/common.sh

load_razorpay_test_keys run.sh
OUT=build/run
rm -rf "$OUT"
mkdir -p "$OUT"
javac -d "$OUT" -cp "$LIBS" $(find src -name '*.java')
exec java -cp "$OUT:$LIBS" booking.app.Main
