#!/usr/bin/env bash
# 本地一键构建 release APK：
#   1) 解析版本号（VERSION_NAME/VERSION_CODE 环境变量 > 参数 vX.Y.Z > git describe > 默认）
#      versionCode 统一公式：major*10000 + minor*100 + patch（与 CI tag 构建一致，保证可覆盖升级）
#   2) 写入 local.properties（SDK 路径）
#   3) ./gradlew :app:assembleRelease
#   4) scripts/verify-apk.sh 门禁（包名/minSdk/targetSdk/arm64/签名）
#   5) 输出 APK 路径 + SHA-256
#
# 用法：
#   scripts/build-apk.sh              # 默认版本
#   scripts/build-apk.sh v0.1.0       # 用 tag 风格版本
#   VERSION_NAME=0.1.0 VERSION_CODE=42 scripts/build-apk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/app/android"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "错误：JAVA_HOME 无效（$JAVA_HOME），需要 JDK 17" >&2
  exit 1
fi
if [[ ! -d "$ANDROID_HOME/platforms/android-36" ]]; then
  echo "错误：缺少 Android SDK platform 36（$ANDROID_HOME）" >&2
  exit 1
fi

TAG_ARG="${1:-}"
VERSION_NAME="${VERSION_NAME:-}"
VERSION_CODE="${VERSION_CODE:-}"
if [[ -z "$VERSION_NAME" ]]; then
  if [[ -n "$TAG_ARG" ]]; then
    VERSION_NAME="${TAG_ARG#v}"
  else
    VERSION_NAME="$(git -C "$ROOT" describe --tags --always 2>/dev/null || echo "0.1.0")"
    VERSION_NAME="${VERSION_NAME#v}"
  fi
fi
if [[ -z "$VERSION_CODE" ]]; then
  # 与 CI tag 构建同一公式：major*10000 + minor*100 + patch（从 vX.Y.Z 前缀提取，忽略 -rc/-dirty 后缀）
  if [[ "$VERSION_NAME" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
    VERSION_CODE=$(( 10#${BASH_REMATCH[1]} * 10000 + 10#${BASH_REMATCH[2]} * 100 + 10#${BASH_REMATCH[3]} ))
  else
    VERSION_CODE="$(date +%Y%m%d)"
  fi
fi

echo "==> 版本：versionName=$VERSION_NAME versionCode=$VERSION_CODE"
echo "==> JDK：$("$JAVA_HOME/bin/java" -version 2>&1 | head -n1)"
echo "==> SDK：$ANDROID_HOME"

# 保留已有签名键（DSH_MOBILE_*，REVIEW P1：密钥不入库），仅更新/追加 sdk.dir
LOCAL_PROPS="$ANDROID_DIR/local.properties"
TMP_PROPS="$(mktemp)"
if [[ -f "$LOCAL_PROPS" ]]; then
  grep -v -E '^[[:space:]]*sdk\.dir[[:space:]]*=' "$LOCAL_PROPS" > "$TMP_PROPS" || true
fi
{ printf 'sdk.dir=%s\n' "$ANDROID_HOME"; cat "$TMP_PROPS"; } > "$LOCAL_PROPS"
rm -f "$TMP_PROPS"
chmod 600 "$LOCAL_PROPS"

# 本地 release 必须签名：local.properties 或环境变量提供 4 个签名键
has_signing_key() {
  local key="$1"
  grep -q "^${key}=" "$LOCAL_PROPS" && return 0
  [[ -n "${!key:-}" ]]
}
for key in DSH_MOBILE_KEYSTORE_PASSWORD DSH_MOBILE_KEY_ALIAS DSH_MOBILE_KEY_PASSWORD; do
  if ! has_signing_key "$key"; then
    echo "错误：缺少发布签名配置 $key（local.properties 或同名环境变量）。" >&2
    echo "  app/android/local.properties 需要（CI 由 workflow 写入，密钥不入库）：" >&2
    echo "    DSH_MOBILE_KEYSTORE_FILE=/abs/path/dsh-mobile.jks" >&2
    echo "    DSH_MOBILE_KEYSTORE_PASSWORD=<store password>" >&2
    echo "    DSH_MOBILE_KEY_ALIAS=dshmobile" >&2
    echo "    DSH_MOBILE_KEY_PASSWORD=<key password>" >&2
    exit 1
  fi
done
KEYSTORE_FILE="$(grep -E '^DSH_MOBILE_KEYSTORE_FILE=' "$LOCAL_PROPS" | tail -n1 | cut -d= -f2- || true)"
KEYSTORE_FILE="${KEYSTORE_FILE:-${DSH_MOBILE_KEYSTORE_FILE:-$ANDROID_DIR/keystore/dsh-mobile.jks}}"
if [[ ! -f "$KEYSTORE_FILE" ]]; then
  echo "错误：发布签名 keystore 不存在：$KEYSTORE_FILE" >&2
  exit 1
fi

cd "$ANDROID_DIR"
./gradlew :app:assembleRelease --no-daemon \
  "-PversionCode=$VERSION_CODE" \
  "-PversionName=$VERSION_NAME"

APK="$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -n1)"
if [[ -z "$APK" ]]; then
  echo "错误：未生成 APK" >&2
  exit 1
fi

bash "$ROOT/scripts/verify-apk.sh" "$APK"

SHA_FILE="${APK}.sha256"
sha256sum "$APK" > "$SHA_FILE"
echo
echo "==> 产物：$APK"
echo "==> 校验：$SHA_FILE"
cat "$SHA_FILE"
