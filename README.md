# dsh-mobile

DSH（DeepSeek Harness）的移动远程客户端：**DSH 插件 + 零依赖网关 + 原生 Android App（Kotlin / Jetpack Compose）**。

- 界面为 DeepSeek 官方简洁风（白底、品牌蓝 `#4d6bfe`、行式列表、浅/深色）。
- 手机与 DSH 主机通过 **口令登录**（每设备独立 token、可撤销、登录限速）连接。
- 支持 **Cloudflare Tunnel 等任意反向代理**：网关只监听 `0.0.0.0:8790`，把域名转发到这个端口即可；事件通道用 **HTTP 长轮询**，不依赖 WebSocket。
- 与现有 `dsh-remote-plugin` 完全并存：独立插件、独立网关（8790）、独立配置目录 `~/.dsh-mobile`，不改动现有 DSH 配置。

## 架构

```
Android App (Kotlin/Compose, arm64 · Android 12+)
   │ HTTPS(公网) / HTTP(局域网)，Bearer device token
   ▼
Cloudflare 通道（你自己配置：域名 → 内网:8790）
   ▼
dsh-mobile-gateway (8790)：口令/token 鉴权、RPC 代理、HTTP 长轮询流中心、文件传输
   ▼ 上游 Cookie（插件自动交换，0600）
DSH web (3080)：现代 RPC + /api/remote.mux
   ▲
dsh-mobile-plugin：/mobile 主机面板 + 自动拉起网关 + 侧边栏入口
```

## 目录

| 路径 | 说明 |
| --- | --- |
| `gateway/` | 独立网关（Node ≥20，零 npm 运行时依赖） |
| `plugin/` | DSH bundle 插件（侧边栏入口 + `/mobile` 主机面板：状态/初始口令/设备管理/日志 + 网关自启） |
| `app/android/` | 原生 Android App（Kotlin + Compose，arm64-v8a，minSdk 31）；应用图标：蓝底白鲸（`docs/assets/app-icon-512.png`），与 DeepSeek 官方白底蓝鲸区分 |
| `tests/` | 网关 `node:test` 测试 |
| `scripts/` | 语法门禁、E2E、构建脚本 |
| `docs/interfaces.md` | 网关 HTTP/WS API、DSH 协议、Kotlin 模块契约 |
| `SPEC.md` | 需求与验收标准（冻结基线） |

## 安装（DSH 主机）

```bash
# 1) 构建插件包
bash scripts/build-plugin-tgz.sh          # 产出 dist/dsh-mobile-plugin-<version>.tgz

# 2) 安装到你的 web profile（会自动追加 bundle）
dsh plugin --profile web add <repo>/dist/dsh-mobile-plugin-0.1.0.tgz

# 3) 完整重启 DSH Web（示例，按你的部署方式）
systemctl --user restart dsh-web

# 4) 打开 DSH → 侧边栏「Remote」→ 主机面板
#    可看到：局域网地址、初始口令、网关状态、事件通道、设备管理（查看在线状态/撤销设备）
#    App 首次用初始口令登录后，会强制要求修改口令（服务端同样拦截，未改密不能调用业务接口）
```

网关默认监听 `0.0.0.0:8790`，配置目录 `~/.dsh-mobile/`（0700，`config.json`/`upstream.cookie` 0600）。

登录限速默认只信任**回环直连**的 `X-Forwarded-For`/`CF-Connecting-IP`（同机 cloudflared 场景，按真实客户端 IP 分桶）；如果 cloudflared 装在另一台内网机，请设置可信代理网段：

```bash
export DSH_MOBILE_TRUST_PROXY_CIDRS=192.168.1.0/24   # 逗号分隔，可多个
dsh mobile  # 或你的 DSH 启动方式（插件会透传给网关）
```
从不可信来源直连时，转发头一律忽略，按 socket 地址限速（防伪造头爆破）。

## 外网访问（你自己已有的 Cloudflare 通道）

1. Cloudflare 控制台 → 你的 Tunnel → Public Hostname 新增：
   - Hostname：`dsh.example.com`（你的域名）
   - Service：`http://<内网 DSH 主机 IP>:8790`
2. App 登录页填 `https://dsh.example.com` + 口令即可。
3. 事件通过普通 HTTPS 长轮询传输，不需要 Cloudflare 额外开启 WebSocket。
4. 公网暴露时请使用强口令；插件面板会显示无鉴权可见的最小 `/health`。

> 网关不做隧道进程管理，也不读取/存储任何 Cloudflare 凭据。

## App

- 构建：`bash scripts/build-apk.sh`（本机需 JDK17 + Android SDK 36）或 GitHub Actions（tag `v*` 自动发 Release）。
- 安装：从 GitHub Releases 下载 `dsh-mobile-v<version>.apk`（附 `.sha256` 校验）。
- 最低系统：Android 12（API 31）；仅 arm64-v8a。

## 开发

```bash
npm run check        # 所有 JS 语法门禁
npm test             # 网关单测
npm run e2e          # 对隔离 DSH 实例的端到端验收（需先启动测试实例）
bash scripts/build-apk.sh
```

隔离测试环境（不碰正在运行的 live DSH）：

```bash
export DSH_HOME=~/.dsh-dev
export DSH_MOBILE_CONFIG_DIR=~/.dsh-dev/.dsh-mobile
export DSH_MOBILE_GATEWAY_HOST=127.0.0.1
dsh --profile web --host 127.0.0.1 --port 3091 --no-open
dsh plugin --profile web add dist/dsh-mobile-plugin-0.1.0.tgz
E2E_PASSWORD=... node scripts/e2e.mjs
```

## 安全

- 口令 scrypt（N=16384,r=8,p=1）；设备 token 只存 SHA-256；WS/轮询 ticket 短时 HMAC。
- 登录限速 5 次/分钟、10 次失败锁 15 分钟；日志不记录 token/口令/Cookie。
- 文件接口 realpath 校验允许根，拒绝越界路径。
- 公网仅经你的反向代理暴露；默认不开启任何隧道。

## 发布签名

发布签名密钥**不进入仓库**：CI 从 GitHub Actions secrets（`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`、`ANDROID_CERT_SHA256`）注入；本地构建在 `app/android/local.properties` 配置（已 gitignore）。同一密钥持续用于覆盖升级；请自行备份密钥与口令。

## License

MIT
