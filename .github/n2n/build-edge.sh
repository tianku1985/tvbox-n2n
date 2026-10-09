#!/usr/bin/env bash
# 在 GitHub Actions (ubuntu-latest) 上用 Android NDK 交叉编译 n2n edge。
# 产物写入 $GITHUB_WORKSPACE/.github/n2n/bin/，由后续 n2n-edge 工件上传。
set -euo pipefail

N2N_VERSION="3.1.1"
WORK="$(mktemp -d)"
OUT="$GITHUB_WORKSPACE/.github/n2n/bin"
ANDROID_API="${N2N_ANDROID_API:-21}"

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

echo "==> 安装构建依赖"
sudo apt-get update -qq
sudo apt-get install -y -qq autoconf automake libtool m4 make curl

echo "==> 定位 Android NDK"
if [ -z "${NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK_HOME="$ANDROID_NDK_HOME"
  elif [ -n "${ANDROID_NDK:-}" ]; then
    NDK_HOME="$ANDROID_NDK"
  elif ls "$ANDROID_SDK_ROOT/ndk/" 2>/dev/null | grep -q .; then
    NDK_HOME="$(ls -d "$ANDROID_SDK_ROOT"/ndk/* | sort -V | tail -1)"
  fi
fi
if [ -z "${NDK_HOME:-}" ] || [ ! -d "$NDK_HOME/toolchains/llvm/prebuilt" ]; then
  echo "==> 未找到可用 NDK，改用 sdkmanager 安装固定版本"
  SDKMGR="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  yes 2>/dev/null | "$SDKMGR" --licenses >/dev/null 2>&1 || true
  "$SDKMGR" --install "ndk;26.1.10909125"
  NDK_HOME="$ANDROID_SDK_ROOT/ndk/26.1.10909125"
fi
TC="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TC/bin:$PATH"
echo "NDK_HOME=$NDK_HOME"

echo "==> 下载 n2n ${N2N_VERSION}"
cd "$WORK"
curl -fsSL "https://codeload.github.com/ntop/n2n/tar.gz/refs/tags/${N2N_VERSION}" -o n2n.tar.gz
tar xzf n2n.tar.gz
N2N_SRC="$(find "$WORK" -maxdepth 1 -type d -name 'n2n-*' | head -1)"
cd "$N2N_SRC"

echo "==> 应用 Android tuntap 补丁"
cp "$GITHUB_WORKSPACE/.github/n2n/edge/tuntap_android.c" src/tuntap_linux.c

chmod +x autogen.sh
./autogen.sh

mkdir -p "$OUT"

build_abi() {
  local host="$1"
  local outname="$2"
  echo "==> 编译 $outname ($host)"
  make distclean >/dev/null 2>&1 || true
  ./configure \
    --host="$host" \
    CC="$TC/bin/${host}${ANDROID_API}-clang" \
    AR="$TC/bin/llvm-ar" \
    RANLIB="$TC/bin/llvm-ranlib" \
    STRIP="$TC/bin/llvm-strip" \
    CFLAGS="-O2" > configure.log 2>&1 || { echo "！！！ configure 失败，日志: "; tail -n 60 configure.log; exit 1; }
  make edge -j"$(nproc)" > build.log 2>&1 || { echo "！！！ make 失败，日志: "; tail -n 120 build.log; exit 1; }
  "$TC/bin/llvm-strip" edge
  cp edge "$OUT/$outname"
}

build_abi "aarch64-linux-android" "edge-arm64-v8a"
build_abi "armv7a-linux-androideabi" "edge-armeabi-v7a"

echo "==> 完成，产物列表:"
ls -l "$OUT"