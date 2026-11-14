#!/usr/bin/env bash
# scripts/build-plugin-tgz.sh — 打包 DSH bundle 插件（plugin/ + gateway/）为可安装 tgz。
# 用法: bash scripts/build-plugin-tgz.sh
# 产物: dist/dsh-mobile-plugin-<version>.tgz（不发布、不安装、不碰 live profile）
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
STAGE_ROOT="$DIST/stage"
STAGE="$STAGE_ROOT/dsh-mobile-plugin"

VERSION="$(node -e 'const fs=require("fs");const p=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));process.stdout.write(p.version)' "$ROOT/plugin/package.json")"
if [ -z "$VERSION" ]; then
  echo "build-plugin-tgz: cannot read version from plugin/package.json" >&2
  exit 1
fi

echo "==> staging dsh-mobile-plugin@$VERSION"
rm -rf "$STAGE_ROOT"
mkdir -p "$STAGE/gateway"

for file in package.json index.mjs client.js admin.html admin.js cordis.patch.yml; do
  if [ ! -f "$ROOT/plugin/$file" ]; then
    echo "build-plugin-tgz: missing plugin/$file" >&2
    exit 1
  fi
  cp "$ROOT/plugin/$file" "$STAGE/$file"
done
cp "$ROOT"/gateway/*.mjs "$STAGE/gateway/"

# 安全门禁：包内不得出现凭据/本地配置/测试产物。
if find "$STAGE" -type f \( -name '*.cookie' -o -name '*.json.tmp' -o -name 'initial-password.txt' -o -name '*.log' \) | grep -q .; then
  echo "build-plugin-tgz: refusing to pack credential/local files" >&2
  find "$STAGE" -type f | grep -E 'cookie|initial-password|\.log$' >&2 || true
  exit 1
fi

echo "==> syntax check"
find "$STAGE" -name '*.mjs' -o -name '*.js' | while read -r file; do
  node --check "$file" >/dev/null
done

echo "==> npm pack"
(
  cd "$STAGE"
  npm pack --pack-destination "$DIST" --silent >/dev/null
)

TGZ="$DIST/dsh-mobile-plugin-$VERSION.tgz"
if [ ! -f "$TGZ" ]; then
  echo "build-plugin-tgz: expected $TGZ not produced" >&2
  ls -la "$DIST" >&2 || true
  exit 1
fi

echo "==> verify archive"
tar -tzf "$TGZ"
SHA="$(sha256sum "$TGZ" | awk '{print $1}')"
echo "==> ok: $TGZ"
echo "    sha256: $SHA"
