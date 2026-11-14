#!/usr/bin/env bash
# 校验 release APK 的发布门禁（SPEC AC7）：包名 / minSdk / targetSdk / 仅 arm64-v8a / 非 testOnly /
# 签名（固定 keystore）/ SHA-256。CI 与本地脚本共用。
#
# 用法：
#   scripts/verify-apk.sh [path/to/app-release.apk]
# 环境：ANDROID_HOME（默认 /opt/android-sdk）；需要 build-tools 36.0.0。
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"
EXPECTED_PACKAGE="${EXPECTED_PACKAGE:-com.smallora.dshmobile}"

if [[ $# -ge 1 && -n "${1:-}" ]]; then
  APK="$1"
else
  APK="$(ls app/android/app/build/outputs/apk/release/*.apk 2>/dev/null | head -n1 || true)"
fi
if [[ -z "${APK:-}" || ! -f "$APK" ]]; then
  echo "错误：未找到 APK（先运行 ./gradlew :app:assembleRelease，或传入路径）" >&2
  exit 1
fi

for tool in "$AAPT2" "$APKSIGNER"; do
  if [[ ! -x "$tool" ]]; then
    echo "错误：缺少工具 $tool（ANDROID_HOME=$ANDROID_HOME，需 build-tools 36.0.0）" >&2
    exit 1
  fi
done

BADGING="$("$AAPT2" dump badging "$APK")"
fail=0

check() {
  local pattern="$1" label="$2"
  if grep -qE "$pattern" <<<"$BADGING"; then
    echo "OK   $label"
  else
    echo "FAIL $label（期望匹配: $pattern）"
    fail=1
  fi
}

check "package: name='${EXPECTED_PACKAGE}'" "packageName=${EXPECTED_PACKAGE}"
check "minSdkVersion:'31'" "minSdkVersion=31"
check "targetSdkVersion:'36'" "targetSdkVersion=36"
check "native-code: 'arm64-v8a'" "native-code 仅 arm64-v8a"

NATIVE_LINE="$(grep -o "native-code: '[^']*'" <<<"$BADGING" | head -n1 || true)"
if [[ "$NATIVE_LINE" != "native-code: 'arm64-v8a'" ]]; then
  echo "FAIL native-code 必须且仅为 arm64-v8a，实际: ${NATIVE_LINE:-<无>}"
  fail=1
fi

# testOnly 门禁（REVIEW P1：badging 格式可能是 true/1/%d，改用 xmltree 权威判断 + badging 双检）
XMLTREE="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null || true)"
if grep -qiE 'testOnly[^=]*=(true|0x1)' <<<"$XMLTREE" || grep -qiE "testOnly='(true|1)'" <<<"$BADGING"; then
  echo "FAIL APK 不允许 testOnly=true（xmltree/badging 命中）"
  fail=1
else
  echo "OK   testOnly=false"
fi

echo "---- aapt2 关键行 ----"
grep -E "^(package:|minSdkVersion:|targetSdkVersion:|native-code:)" <<<"$BADGING" || true

echo "---- apksigner ----"
CERT_OUT="$("$APKSIGNER" verify --print-certs "$APK" 2>&1)" || { echo "FAIL apksigner 验签失败" >&2; echo "$CERT_OUT" >&2; exit 1; }
echo "$CERT_OUT"
if [[ -n "${EXPECTED_CERT_SHA256:-}" ]]; then
  if grep -qi "certificate SHA-256 digest: ${EXPECTED_CERT_SHA256}" <<<"$CERT_OUT"; then
    echo "OK   签名证书 SHA-256 与期望一致"
  else
    echo "FAIL 签名证书 SHA-256 与期望不符（期望 ${EXPECTED_CERT_SHA256}）" >&2
    exit 1
  fi
fi

echo "---- sha256 ----"
sha256sum "$APK"

if [[ "$fail" -ne 0 ]]; then
  echo "APK 门禁未通过" >&2
  exit 1
fi
echo "APK 门禁全部通过：$APK"
