#!/usr/bin/env bash
# Applies core-patches/*.patch to the zeron/ submodule's working tree, in
# name order. The submodule stays pinned to an upstream tag; these patches
# are what this app's core carries on top of it (see README, "Core patches").
#
#   bash scripts/core-patches.sh          apply (a patch already applied is skipped)
#   bash scripts/core-patches.sh --check  fail unless every patch applies or is applied
#   bash scripts/core-patches.sh --revert take them back out (clean submodule tree)
#
# Run it before zeron/scripts/android/build-core.sh or any cargo build of the
# core; scripts/build-core.sh does both.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
zeron="$root/zeron"
mode=${1:-apply}

shopt -s nullglob
patches=("$root"/core-patches/*.patch)
if [ ${#patches[@]} -eq 0 ]; then
  echo "core-patches: none"
  exit 0
fi

order=("${patches[@]}")
if [ "$mode" = "--revert" ]; then
  order=()
  for (( i=${#patches[@]}-1; i>=0; i-- )); do order+=("${patches[$i]}"); done
fi

for patch in "${order[@]}"; do
  name=$(basename "$patch")
  # --ignore-whitespace: a Windows checkout with core.autocrlf=true has CRLF
  # sources while the patch has LF.
  if git -C "$zeron" apply --ignore-whitespace --reverse --check "$patch" 2>/dev/null; then
    applied=1
  else
    applied=0
  fi
  case "$mode" in
    apply)
      if [ $applied = 1 ]; then
        echo "core-patches: $name already applied"
      else
        git -C "$zeron" apply --ignore-whitespace "$patch"
        echo "core-patches: applied $name"
      fi
      ;;
    --check)
      if [ $applied = 1 ]; then
        echo "core-patches: $name applied"
      else
        git -C "$zeron" apply --ignore-whitespace --check "$patch"
        echo "core-patches: $name applies"
      fi
      ;;
    --revert)
      if [ $applied = 1 ]; then
        git -C "$zeron" apply --ignore-whitespace --reverse "$patch"
        echo "core-patches: reverted $name"
      else
        echo "core-patches: $name not applied"
      fi
      ;;
    *)
      echo "usage: $0 [--check|--revert]" >&2
      exit 2
      ;;
  esac
done
