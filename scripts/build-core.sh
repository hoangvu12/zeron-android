#!/usr/bin/env bash
# Builds the Zeron core the way the Core workflow does: core-patches/ applied
# to zeron/, then zeron's own scripts/android/build-core.sh into <out>.
#
#   bash scripts/build-core.sh [out-dir] [--host]
#
# --host also builds the host library the JVM tests load (Linux runners:
# <out>/host/libzeron_mobile.so). Needs the NDK (ANDROID_NDK_HOME), cargo-ndk
# and the aarch64-linux-android target, like upstream's script.
# ZERON_ANDROID_PROFILE / ZERON_ANDROID_ABIS are passed through.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-$root/core-out}
host=0
[ "${2:-}" = "--host" ] && host=1

bash "$root/scripts/core-patches.sh"
bash "$root/zeron/scripts/android/build-core.sh" "$out"

if [ $host = 1 ]; then
  (cd "$root/zeron" && cargo build --locked -p zeron-mobile --lib --profile mobile)
  mkdir -p "$out/host"
  cp "$root/zeron/target/mobile/libzeron_mobile.so" "$out/host/"
fi
