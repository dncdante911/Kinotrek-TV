#!/usr/bin/env bash
# Builds libtorrserver.so (TorrServer MatriX + jnibridge) for every ABI into app/src/main/jniLibs.
#
#   ANDROID_NDK_HOME=/path/to/android-ndk-r28c ./engine/build.sh [tag]
#
# Needs Go (the version in TorrServer's go.mod is fetched automatically) and NDK r28+, whose
# linker aligns to 16 KB pages. Works in Git Bash on Windows as well as on Linux and macOS.
set -euo pipefail

TAG="${1:-MatriX.147.1}"
API=28
ENGINE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$ENGINE")"
SRC="$ENGINE/.upstream"
OUT="$ROOT/app/src/main/jniLibs"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the NDK folder}"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.cmd ;;
  Darwin) HOST=darwin-x86_64; EXT= ;;
  *) HOST=linux-x86_64; EXT= ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin"
[ -d "$TOOLCHAIN" ] || { echo "No NDK toolchain at $TOOLCHAIN" >&2; exit 1; }

if [ -d "$SRC/.git" ]; then
  git -C "$SRC" fetch -q --depth 1 origin tag "$TAG"
  git -C "$SRC" checkout -q -f "$TAG"
  git -C "$SRC" clean -q -fdx
else
  git clone -q --depth 1 --branch "$TAG" https://github.com/YouROK/TorrServer.git "$SRC"
fi
rm -rf "$SRC/server/jnibridge"
cp -r "$ENGINE/jnibridge" "$SRC/server/jnibridge"
# Files that replace upstream ones (Swagger UI stub).
cp -r "$ENGINE/overlay/." "$SRC/"
# The web UI is embedded into the binary (about 21 MB); the app never opens it, so the pages stay empty.
find "$SRC/server/web/pages/template/pages" -type f -exec sh -c ': > "$1"' _ {} \;

cd "$SRC/server"
export GOTOOLCHAIN=auto GOFLAGS=-mod=mod
LDFLAGS="-s -w -checklinkname=0 -X server/version.Version=$TAG -extldflags=-Wl,-z,max-page-size=16384"

for spec in "arm64:aarch64-linux-android:arm64-v8a" "arm:armv7a-linux-androideabi:armeabi-v7a" \
            "amd64:x86_64-linux-android:x86_64" "386:i686-linux-android:x86"; do
  IFS=: read -r arch triple abi <<< "$spec"
  echo "== $abi"
  mkdir -p "$OUT/$abi"
  GOOS=android GOARCH="$arch" GOARM=7 CGO_ENABLED=1 \
    CC="$TOOLCHAIN/$triple$API-clang$EXT" CXX="$TOOLCHAIN/$triple$API-clang++$EXT" \
    go build -trimpath -tags=nosqlite,nomsgpack -buildmode=c-shared -ldflags="$LDFLAGS" \
      -o "$OUT/$abi/libtorrserver.so" ./jnibridge
  rm -f "$OUT/$abi/libtorrserver.h"
done
echo "Built $TAG into $OUT"
