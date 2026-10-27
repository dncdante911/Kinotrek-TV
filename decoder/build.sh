#!/usr/bin/env bash
# Builds the FFmpeg audio decoder used for Dolby and DTS tracks the box cannot decode itself (or only
# bitstreams, so the remote's volume does nothing) into app/src/main/jniLibs/<abi>/libffmpegJNI.so.
# The JNI glue is Media3's decoder_ffmpeg (decoder/jni, Apache 2.0); the Java side lives in
# app/src/main/java/androidx/media3/decoder/ffmpeg. Keep both at the Media3 version of app/build.gradle.kts.
#
#   ANDROID_NDK_HOME=/path/to/android-ndk-r28c ./decoder/build.sh [ffmpeg-branch]
#
# Needs Linux (on Windows: WSL) with make, and NDK r28+, whose linker aligns to 16 KB pages.
# FFmpeg is configured without --enable-gpl/--enable-nonfree, so the library stays LGPL-2.1.
set -euo pipefail

BRANCH="${1:-release/9.0}"
API=28
DECODERS=(ac3 eac3 dca truehd mlp)
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
WORK="$HERE/.upstream"
SRC="$WORK/ffmpeg"
OUT="$ROOT/app/src/main/jniLibs"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the NDK folder}"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$TOOLCHAIN" ] || { echo "No Linux NDK toolchain at $TOOLCHAIN" >&2; exit 1; }
JOBS="$(nproc 2>/dev/null || echo 4)"

mkdir -p "$WORK"
if [ -d "$SRC/.git" ]; then
  git -C "$SRC" fetch -q --depth 1 origin "$BRANCH"
  git -C "$SRC" checkout -q -f FETCH_HEAD
else
  git clone -q --depth 1 --branch "$BRANCH" https://github.com/FFmpeg/FFmpeg.git "$SRC"
fi

OPTIONS=(
  --target-os=android --enable-cross-compile --enable-static --disable-shared --enable-pic
  --disable-autodetect --disable-doc --disable-programs --disable-everything
  --disable-avdevice --disable-avformat --disable-swscale --disable-avfilter --disable-network
  --enable-swresample --disable-symver --disable-vulkan
)
for decoder in "${DECODERS[@]}"; do OPTIONS+=("--enable-decoder=$decoder"); done

for spec in "aarch64:armv8-a:aarch64-linux-android:arm64-v8a" "arm:armv7-a:armv7a-linux-androideabi:armeabi-v7a" \
            "x86_64:x86-64:x86_64-linux-android:x86_64" "x86:i686:i686-linux-android:x86"; do
  IFS=: read -r arch cpu triple abi <<< "$spec"
  echo "== $abi"
  build="$WORK/build/$abi"
  prefix="$WORK/out/$abi"
  rm -rf "$build" "$prefix"
  mkdir -p "$build"
  extra=()
  case "$abi" in
    armeabi-v7a) extra=(--extra-cflags="-march=armv7-a -mfloat-abi=softfp -mfpu=neon") ;;
    # Text relocations in the x86 assembly are not allowed on Android.
    x86|x86_64) extra=(--disable-asm) ;;
  esac
  (cd "$build" && "$SRC/configure" --prefix="$prefix" --arch="$arch" --cpu="$cpu" \
    --cc="$TOOLCHAIN/$triple$API-clang" --cxx="$TOOLCHAIN/$triple$API-clang++" \
    --nm="$TOOLCHAIN/llvm-nm" --ar="$TOOLCHAIN/llvm-ar" --ranlib="$TOOLCHAIN/llvm-ranlib" \
    --strip="$TOOLCHAIN/llvm-strip" "${OPTIONS[@]}" "${extra[@]}" >/dev/null \
    && make -s -j"$JOBS" >/dev/null && make -s install-libs install-headers >/dev/null)

  # libc++ is linked in: the APK ships no libc++_shared.so.
  ldflags=(-static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL)
  # See https://github.com/google/ExoPlayer/issues/9933.
  [ "$abi" = arm64-v8a ] && ldflags+=(-Wl,-Bsymbolic)
  mkdir -p "$OUT/$abi"
  "$TOOLCHAIN/$triple$API-clang++" -shared -fPIC -O2 -std=c++11 -fvisibility=hidden -fvisibility-inlines-hidden \
    -I"$prefix/include" "$HERE/jni/ffmpeg_jni.cc" \
    "$prefix/lib/libswresample.a" "$prefix/lib/libavcodec.a" "$prefix/lib/libavutil.a" \
    -llog -landroid -lm "${ldflags[@]}" -o "$OUT/$abi/libffmpegJNI.so"
  "$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUT/$abi/libffmpegJNI.so"
done
echo "Built FFmpeg $(cat "$SRC/VERSION" 2>/dev/null || echo "$BRANCH") (${DECODERS[*]}) into $OUT"
