#!/usr/bin/env bash
# Builds the tunnel (main.go, a VLESS + Reality client on Xray-core) as a separate executable for every
# ABI into app/src/main/jniLibs. It is named libkinotunnel.so only so that Android installs it with the
# app's native libraries, the one place an app may run programs from; the app starts it as its own process.
#
#   ANDROID_NDK_HOME=/path/to/android-ndk-r28c ./tunnel/build.sh
#
# Needs Go (the version in go.mod is fetched automatically) and NDK r28+, whose linker aligns to
# 16 KB pages. Works in Git Bash on Windows as well as on Linux and macOS.
set -euo pipefail

API=24
TUNNEL="$(cd "$(dirname "$0")" && pwd)"
OUT="$(dirname "$TUNNEL")/app/src/main/jniLibs"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the NDK folder}"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.cmd ;;
  Darwin) HOST=darwin-x86_64; EXT= ;;
  *) HOST=linux-x86_64; EXT= ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin"
[ -d "$TOOLCHAIN" ] || { echo "No NDK toolchain at $TOOLCHAIN" >&2; exit 1; }

cd "$TUNNEL"
export GOTOOLCHAIN=auto
LDFLAGS="-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384"

for spec in "arm64:aarch64-linux-android:arm64-v8a" "arm:armv7a-linux-androideabi:armeabi-v7a" \
            "amd64:x86_64-linux-android:x86_64" "386:i686-linux-android:x86"; do
  IFS=: read -r arch triple abi <<< "$spec"
  echo "== $abi"
  mkdir -p "$OUT/$abi"
  GOOS=android GOARCH="$arch" GOARM=7 CGO_ENABLED=1 \
    CC="$TOOLCHAIN/$triple$API-clang$EXT" \
    go build -trimpath -buildmode=pie -ldflags="$LDFLAGS" -o "$OUT/$abi/libkinotunnel.so" .
done
echo "Built the tunnel into $OUT"
