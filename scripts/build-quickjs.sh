#!/usr/bin/env bash
#
# Build QuickJS (quickjs-ng) as an Android executable and drop it into
# app/src/main/jniLibs/<abi>/libqjs.so
#
# yt-dlp >= 2025.11.12 needs an external JavaScript runtime to solve YouTube's
# challenges (EJS). Without one, YouTube downloads fail with HTTP 403 or
# "Sign in to confirm you're not a bot".
#
# The binary is shipped as a *native library* on purpose: youtubedl-android sets
#   PATH = $PATH:<applicationInfo.nativeLibraryDir>
# so anything placed in jniLibs/<abi>/ ends up on yt-dlp's PATH after install.
# The file is named libqjs.so (not qjs) because Android only extracts native
# libraries matching lib*.so; JsRuntime.kt therefore passes the full path via
# --js-runtimes instead of relying on the name.
#
# Usage:
#   scripts/build-quickjs.sh            # build every ABI
#   ABIS="arm64-v8a" scripts/build-quickjs.sh
#
# Requires: Android NDK (set ANDROID_NDK_HOME, or ANDROID_HOME/ANDROID_SDK_ROOT
# with an NDK installed), cmake and ninja/make.

set -euo pipefail

QJS_VERSION="${QJS_VERSION:-v0.17.0}"
QJS_REPO="${QJS_REPO:-https://github.com/quickjs-ng/quickjs.git}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-24}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$PROJECT_DIR/app/src/main/jniLibs"
WORK_DIR="$PROJECT_DIR/.quickjs-build"

# --- locate the NDK -----------------------------------------------------------
# A directory only counts as an NDK if it actually carries the CMake toolchain;
# $ANDROID_HOME/ndk itself does not (it merely contains versioned subdirectories).
find_ndk() {
  for base in "$@"; do
    [ -n "$base" ] && [ -d "$base" ] || continue
    if [ -f "$base/build/cmake/android.toolchain.cmake" ]; then
      echo "$base"
      return 0
    fi
    for sub in "$base"/*/; do
      [ -d "$sub" ] || continue
      if [ -f "${sub}build/cmake/android.toolchain.cmake" ]; then
        echo "${sub%/}"
        return 0
      fi
    done
  done
  return 1
}

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(find_ndk \
    "${ANDROID_HOME:-}/ndk" "${ANDROID_SDK_ROOT:-}/ndk" \
    "${ANDROID_HOME:-}/ndk-bundle" "${ANDROID_SDK_ROOT:-}/ndk-bundle")"
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
  echo "error: could not locate the Android NDK." >&2
  echo "       set ANDROID_NDK_HOME (or install an NDK under ANDROID_HOME)." >&2
  exit 1
fi

TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN" ]; then
  echo "error: NDK CMake toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi

echo "NDK:      $ANDROID_NDK_HOME"
echo "QuickJS:  $QJS_VERSION"
echo "ABIs:     $ABIS"
echo ""

# --- fetch sources -------------------------------------------------------------
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
git clone --depth 1 --branch "$QJS_VERSION" "$QJS_REPO" "$WORK_DIR/quickjs"

# --- build each ABI ------------------------------------------------------------
for abi in $ABIS; do
  echo "==> building QuickJS for $abi"
  build_dir="$WORK_DIR/build-$abi"
  cmake -S "$WORK_DIR/quickjs" -B "$build_dir" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release \
    -DQJS_ENABLE_INSTALL=OFF \
    -DQJS_BUILD_EXAMPLES=OFF

  cmake --build "$build_dir" --target qjs_exe -j"$(nproc 2>/dev/null || echo 4)"

  src="$build_dir/qjs"
  if [ ! -f "$src" ]; then
    echo "error: expected executable not produced: $src" >&2
    exit 1
  fi

  dest_dir="$JNI_DIR/$abi"
  mkdir -p "$dest_dir"
  install -m 755 "$src" "$dest_dir/libqjs.so"
  echo "    -> $dest_dir/libqjs.so ($(du -h "$dest_dir/libqjs.so" | cut -f1))"
done

echo ""
echo "QuickJS installed into app/src/main/jniLibs/:"
find "$JNI_DIR" -name 'libqjs.so' -exec ls -lh {} \; | awk '{print "  " $9 " (" $5 ")"}'
