#!/usr/bin/env bash
# Build the Media3 ExoPlayer FFmpeg *audio-only* decoder extension AAR (t116).
#
# Tested design: androidx.media 1.11.1 + FFmpeg release/6.0 + Android NDK r27, audio decoders only.
# Output: <media3>/libraries/decoder_ffmpeg/build/outputs/aar/decoder_ffmpeg-release.aar
#
# Usage: scripts/build_media3_ffmpeg_audio.sh <media3-dir> <ndk-path> [android-abi]
#   <media3-dir>  checkout of https://github.com/androidx/media at tag 1.11.1
#   <ndk-path>    Android NDK r27 (e.g. $ANDROID_NDK_HOME)
#   [android-abi] minSdk for the native build, default 21 (project minSdk is 24, 21<=24 is safe)
set -euo pipefail

MEDIA3_DIR=${1:?media3 checkout dir with libraries/decoder_ffmpeg}
NDK_PATH=${2:?path to Android NDK r27}
ANDROID_ABI=${3:-21}

# Audio-only decoder set. NOTE: Media3 maps AUDIO_MPEG_L2 (MPEG-1 Layer II / MP2) to FFmpeg's
# "mp3" decoder (see FfmpegLibrary.getCodecName), so "mp3" also covers MP2. No h264/hevc -> small.
ENABLED_DECODERS=(mp3 aac ac3 eac3 flac opus vorbis alac pcm_mulaw pcm_alaw)

FFMPEG_MODULE_PATH="${MEDIA3_DIR}/libraries/decoder_ffmpeg/src/main"
JNI_DIR="${FFMPEG_MODULE_PATH}/jni"

echo "== media3 dir: ${MEDIA3_DIR}"
echo "== ndk: ${NDK_PATH} (source.properties:)"
cat "${NDK_PATH}/source.properties" || true
echo "== enabled decoders: ${ENABLED_DECODERS[*]}"

if [ ! -d "${JNI_DIR}/ffmpeg/.git" ]; then
  echo "== cloning FFmpeg release/6.0"
  git clone --branch release/6.0 --depth=1 https://git.ffmpeg.org/ffmpeg.git "${JNI_DIR}/ffmpeg"
fi

echo "== building FFmpeg for the ABIs configured in build_ffmpeg.sh"
pushd "${JNI_DIR}" >/dev/null
./build_ffmpeg.sh "${FFMPEG_MODULE_PATH}" "${NDK_PATH}" "linux-x86_64" "${ANDROID_ABI}" "${ENABLED_DECODERS[@]}"
popd >/dev/null

echo "== building decoder_ffmpeg AAR"
pushd "${MEDIA3_DIR}" >/dev/null
./gradlew :lib-decoder-ffmpeg:assembleRelease --no-daemon --stacktrace
popd >/dev/null

AAR=$(find "${MEDIA3_DIR}" -path '*decoder_ffmpeg*' -name '*.aar' -print -quit)
echo "== AAR: ${AAR}"
ls -l "${AAR}"
sha256sum "${AAR}"
unzip -l "${AAR}" | grep -E 'jni/|classes.jar' || true
