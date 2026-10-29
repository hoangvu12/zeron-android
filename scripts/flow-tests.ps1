# The instrumented flow suite's runner on Windows: the same as
# flow-tests.sh, a plain connectedDebugAndroidTest run plus the artefact
# harvest. The flow tests drive the app's built-in demo, so no fixture
# engine or adb reverse is needed (#63).
#
# Prerequisites: an API 36 emulator (WHPX) or device attached, e.g.
#   sdkmanager "emulator" "platform-tools" "system-images;android-36;google_apis;x86_64"
#   avdmanager create avd -n zeron36 -k "system-images;android-36;google_apis;x86_64" -d pixel_7
#   emulator -avd zeron36 -no-snapshot-save
#
# Usage:
#   scripts\flow-tests.ps1            # $env:FLOW_INCLUDE_TOUR = '1' adds the @Tour recordings

$ErrorActionPreference = 'Stop'

$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $Root

$Artifacts = 'app\build\flow-artifacts'
New-Item -ItemType Directory -Force -Path $Artifacts | Out-Null

$gradleArgs = @(':app:connectedDebugAndroidTest')
if ($env:FLOW_INCLUDE_TOUR -eq '1') {
  $gradleArgs += '-Pandroid.testInstrumentationRunnerArguments.flowIncludeTour=true'
}

try {
  .\gradlew.bat @gradleArgs
  $status = $LASTEXITCODE
  Write-Output "flow-tests: connectedDebugAndroidTest exit $status (artefacts in $Artifacts)"
  exit $status
}
finally {
  # Best-effort harvest: under 'Stop', Windows PowerShell 5.1 turns a native
  # command's stderr line (no tour mp4 on a normal run) into a terminating
  # error that would skip the rest and replace the Gradle exit code.
  $ErrorActionPreference = 'Continue'
  adb logcat -d > (Join-Path $Artifacts 'logcat.txt') 2>$null
  adb pull /sdcard/Android/data/sh.zeron.android/files (Join-Path $Artifacts 'app-files') 2>$null
  adb shell "ls /sdcard/zeron-tour-*.mp4" 2>$null
  if ($LASTEXITCODE -eq 0) {
    adb pull /sdcard/zeron-tour-*.mp4 $Artifacts 2>$null
  }
}
