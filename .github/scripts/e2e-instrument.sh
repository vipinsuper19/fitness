#!/usr/bin/env bash
# Runs the real-app E2E instrumentation with credentials read from a file (never echoed).
set -euo pipefail
E="$(sed -n 1p "$1")"; P="$(sed -n 2p "$1")"
out="$(adb shell am instrument -w -r -e e2eEmail "$E" -e e2ePassword "$P" \
  -e class com.vipin.fitness.e2e.RealAppE2eTest com.vipin.fitness.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
printf '%s\n' "$out" | grep -vF -- "$P"
printf '%s\n' "$out" | grep -q "^OK (" 
