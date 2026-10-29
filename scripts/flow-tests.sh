#!/usr/bin/env bash
# The instrumented flow suite's runner: a plain connectedDebugAndroidTest
# run, plus the artefact harvest. The flow tests drive the app's built-in
# demo (`FixtureRule` sends the `demo` + `fast` launch extras), so no
# fixture engine or adb reverse is needed (#63).
#
# Usage (an API 36 emulator or device must be attached):
#   scripts/flow-tests.sh
#
# FLOW_INCLUDE_TOUR=1 also runs the @Tour motion recordings (the device
# filters live in the app's ZeronTestRunner).

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ARTIFACTS="app/build/flow-artifacts"
mkdir -p "$ARTIFACTS"

harvest() {
  # Every adb call is bounded: a device lost mid-suite would otherwise
  # block until the job's timeout.
  timeout 15 adb devices -l >"$ARTIFACTS/adb-devices.txt" 2>&1 || true
  timeout 60 adb logcat -d >"$ARTIFACTS/logcat.txt" 2>/dev/null || true
  # The tests' PNG/mp4/JSON artefacts live in the app's external files dir.
  timeout 120 adb pull /sdcard/Android/data/sh.zeron.android/files "$ARTIFACTS/app-files" >/dev/null 2>&1 || true
  timeout 30 adb shell ls /sdcard/zeron-tour-*.mp4 >/dev/null 2>&1 && \
    timeout 120 adb pull /sdcard/zeron-tour-*.mp4 "$ARTIFACTS/" >/dev/null 2>&1 || true
}
trap harvest EXIT

TOUR_ARGS=()
if [[ "${FLOW_INCLUDE_TOUR:-0}" == "1" ]]; then
  TOUR_ARGS=("-Pandroid.testInstrumentationRunnerArguments.flowIncludeTour=true")
fi

./gradlew :app:connectedDebugAndroidTest "${TOUR_ARGS[@]}"
status=$?

echo "flow-tests: connectedDebugAndroidTest exit $status (artefacts in $ARTIFACTS)"
exit $status
