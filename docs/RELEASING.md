# 发布流程（dsh-mobile）

## 1. 版本约定

| 位置 | 含义 |
| --- | --- |
| 根 `package.json` `version` | 项目版本（文档/脚本用） |
| `plugin/package.json` `version` | 插件包版本（与 Release 对齐） |
| `plugin/index.mjs` `PLUGIN_VERSION` / `GATEWAY_VERSION` | 面板与 `/health` 展示 |
| `gateway/config.mjs` `VERSION` | 网关版本（`/health`） |
| Git tag `v<version>` | 触发 CI 构建 APK + Release |
| App `versionName/versionCode` | tag 构建时由 CI 注入：`versionName=<tag 去掉 v>`，`versionCode=major*10000+minor*100+patch`（与 `scripts/build-apk.sh` 同一公式，保证单调可覆盖：0.1.9→109、0.1.10→110、0.1.11→111、0.2.0→200）；非 tag 构建（main/dev/PR）versionCode 仍用 `GITHUB_RUN_NUMBER` |

一次发版通常同步 bump：根 `package.json`、`plugin/package.json`、`plugin/index.mjs`、`gateway/config.mjs`、`tests/auth.test.js`（版本断言），然后提交。

## 2. 发布步骤

```bash
# 0) 在干净工作区、全部测试通过后：
npm run check && npm test
bash scripts/build-plugin-tgz.sh          # dist/dsh-mobile-plugin-<ver>.tgz

# 1) bump 版本（示例 0.1.9 → 0.1.10）
#    修改上述 5 处版本号
git add -A && git commit -m "release: v0.1.10"
git push origin main
git tag -a v0.1.10 -m "dsh-mobile v0.1.10"
git push origin v0.1.10
```

2. GitHub Actions（`.github/workflows/android.yml`）在 tag 上：
   - 安装 Node 22 + JDK17 + Android SDK 36
   - **网关门禁**：`npm run check && npm test`（26 文件语法 + 51 例 node:test）
   - 从 Actions Secrets 注入签名密钥
   - `./gradlew :app:testReleaseUnitTest`（143 个 JVM 单测）+ `./gradlew :app:lintRelease`（lint error 阻断）
   - `./gradlew :app:assembleRelease -PversionCode -PversionName`
   - `scripts/verify-apk.sh` 门禁：包名 / minSdk31 / targetSdk36 / 仅 arm64 / 非 testOnly / 签名证书 SHA-256
   - `gh release create` 上传 `dsh-mobile-v<ver>.apk` 与 `.sha256`
3. 手动把插件包补到 Release：

```bash
sha256sum dist/dsh-mobile-plugin-<ver>.tgz > dist/dsh-mobile-plugin-<ver>.tgz.sha256
gh release upload v<ver> dist/dsh-mobile-plugin-<ver>.tgz dist/dsh-mobile-plugin-<ver>.tgz.sha256 --clobber
```

## 3. CI Secrets

| Secret | 用途 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | base64 的 release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | key alias（当前 `dshmobile`） |
| `ANDROID_KEY_PASSWORD` | key 口令 |
| `ANDROID_CERT_SHA256` | 期望的签名证书 SHA-256（门禁用，hex） |

> 签名密钥**不入仓库**。本地构建用 `app/android/local.properties` 配置同样四项（已 gitignore）。

## 4. 签名密钥管理

- 固定密钥保证「同包名可覆盖升级」；更换密钥会导致老用户必须卸载重装。
- 轮换流程：
  1. 生成新 keystore（2048 RSA，10000 天）：`keytool -genkeypair ...`
  2. 更新全部 5 个 GitHub Secrets（含新的证书 SHA-256）
  3. 本地 `app/android/local.properties` 更新
  4. 发布新版本；README/Release Notes 提醒用户「先卸载旧版」
- 历史教训：v0.1.0 曾把 keystore 提交进 public 仓库 → 已撤回并轮换密钥重发 v0.1.1。**永远不要把 keystore/口令提交到仓库或写进日志。**

## 5. 本地构建与门禁

```bash
export ANDROID_HOME=/opt/android-sdk
bash scripts/build-apk.sh          # 构建 + aapt2/apksigner 门禁 + sha256
bash scripts/verify-apk.sh app/android/app/build/outputs/apk/release/app-release.apk
```

APK 是**非字节可复现**的（签名/时间戳差异），sha256 以 CI 产物或最后一次串行本地构建为准；不要混用不同构建的 sha。

## 6. 回滚

- App：Release 保留历史版本；回退 → 用旧 tag 重新构建/下载旧 APK（versionCode 必须 ≥ 已装版本才能覆盖；否则先卸载）。
- 插件：`dsh plugin --profile web add <旧 tgz>` + 重启 DSH。
- 数据：`~/.dsh-mobile` 为独立目录；删除即完全重置（设备需重新登录）。
- 不影响现有 DSH / 旧插件（8787）。

## 7. 发布检查清单

- [ ] 全部测试通过：`npm run check && npm test` + Android `testReleaseUnitTest`
- [ ] lint 门禁通过：`./gradlew :app:lintRelease`（error 阻断；无 `abortOnError=false` 绕过）
- [ ] versionCode 符合公式 `major*10000+minor*100+patch`，且 ≥ 上一个 Release 的 versionCode
- [ ] 版本号四处/五处一致
- [ ] `bash scripts/build-plugin-tgz.sh` 并核对包内无凭据（脚本自带检查）
- [ ] tag 推送后 CI 绿，Release 资产含 APK + `.sha256` + 插件 tgz
- [ ] 下载 Release APK 复核：versionName/versionCode、arm64、签名证书 SHA-256
- [ ] 更新 CHANGELOG 与 README 中的版本号
