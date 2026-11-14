# SPEC v2 — dsh-mobile：DSH 移动远程客户端重构（新插件 + 新原生 App）

- 状态：**v3（去掉 Cloudflare 隧道托管；事件通道改 HTTP 长轮询为主；App 为原生 Kotlin + Jetpack Compose）**
- v2 → v3 变更：① 用户已自行安装并连通 Cloudflare 通道，网关**不再管理 cloudflared、不存储 Tunnel Token**，只开放 8790 端口供其转发；② 因用户现有通道可能不透传 WebSocket，事件/流通道改为 **HTTP 长轮询为主**（网关内部维护上游 WS，App 只轮询），WS 仅作可选；③ 设置页去掉隧道配置，改为「远程访问」说明。
- v1 → v2 变更：App 从「Capacitor/WebView + 纯 JS SPA」改为「**Kotlin + Jetpack Compose 原生 App**」；网关不再托管 SPA 静态资源；其余（鉴权/隧道/网关/插件/功能范围/安全边界）不变。
- 日期：2026-11-14
- 交付仓库：`small-ora/dsh-mobile`（public，新建；CI 构建 APK 并发布 Release）
- 参考实现（只读）：`Blank-not-black/dsh-Remote`（MIT）；本任务**不修改**现有 `dsh-remote-plugin`

## 1. 分级与流程

| 项 | 值 |
| --- | --- |
| 级别 | **L3**（鉴权/信任边界、公网暴露、部署链路、跨端架构） |
| 理由 | 新增口令/设备 token 鉴权、通过用户自建 Cloudflare 通道暴露公网、新增 GitHub 发布链路 |
| 必须阶段 | 勘察 → 隔离分支 → SPEC（人确认）→ PLAN → 分步实现 → 自检 → 独立审查（含安全维度）→ 人工验收包 |
| 强制手段 | 无 CI 保护分支；`ENFORCEMENT: prompt-only` |

## 2. 背景与问题

现有 `dsh-remote-plugin`（0.8.0-rc.9）+ Capacitor App 功能齐全，但：

1. **界面花哨**：深色底 + 12 套主题 + 立绘/GSAP 动效/统计卡片，与用户想要的 DeepSeek 官方简洁风相悖。
2. **外网不便**：局域网直连好用；外网原方案依赖 Tailscale 或自建云中转，未适配用户已有的 Cloudflare 通道。
3. 现有 App 是 WebView 壳（Capacitor + 纯 JS），用户明确要求改为**安卓原生语言（Kotlin）**实现。
4. 现有网关鉴权只有单一 Bearer token，缺少口令登录、设备管理与暴力破解防护。

## 3. 目标（M1，本次交付）

- 全新 DSH bundle 插件 `dsh-mobile-plugin` + 独立网关进程 + **全新原生 Android App（Kotlin + Jetpack Compose）**。
- 目标设备：`arm64-v8a`（64 位），**Android 12+（minSdk 31）**，targetSdk 36。
- 界面为 **DeepSeek 官方简洁风**：白底为主、蓝色强调、行式列表、无装饰动效；支持浅色/深色/跟随系统。
- **兼容用户自建的 Cloudflare 通道**：网关只监听 `0.0.0.0:8790`，用户在自己 Cloudflare 通道里把域名转发到该端口即可；不管理 cloudflared、不读取/不存储 Tunnel Token。
- **通道兼容性**：事件流用 HTTP 长轮询（App 不依赖 WebSocket），确保 Cloudflare 通道不透传 WS 时仍可用；WS 作为局域网可选优化。
- **口令认证**：单管理口令（scrypt）登录 → 每设备独立 token（哈希存储、可撤销）；登录限速；WS 短时票据。
- **功能对齐现有 App**（M1 范围见 §10；M2 明确列为非目标）。

## 4. 非目标（本 SPEC 不做）

- 不修改、不卸载、不替换现有 `dsh-remote-plugin`（并存：新网关 8790 / 旧网关 8787）。
- 不部署到生产环境、不重启现有 3080 服务、不动 nginx/dsh-gate。
- 不做 iOS / 鸿蒙 / Windows / macOS 客户端；不做 Web 版 SPA。
- 不做 npm 发布、不做云中转 relay/E2EE。
- M1 不做：插件中心、技能中心、洞察/费用、团队、任务看板、Doctor、公告/反馈、操作回执、离线历史缓存、文件收件箱、断点续传、局域网自动发现、扫码配对、多主题皮肤。

## 5. 信任边界与安全设计（关键）

### 5.1 凭据模型

| 凭据 | 生成 | 存储 | 生命周期 |
| --- | --- | --- | --- |
| 管理口令 | 首次启动随机生成 10 位（无歧义字符集），写入 `~/.dsh-mobile/initial-password.txt`(0600) 并打印到插件面板 | `config.json` 存 `scrypt(password, salt, N=16384,r=8,p=1)` | 用户可改；首次登录强制修改 |
| 设备 token | 登录成功后服务端生成 32B 随机 → base64url（43 字符） | 服务端仅存 SHA-256；App 存 `EncryptedSharedPreferences`（AndroidX Security）或 `SharedPreferences` + 明确说明 | 30 天滑动过期；可撤销；可命名 |
| WS ticket | HMAC-SHA256(设备ID+过期时间) | 不落盘 | 30 秒，仅用于 WS query |

- 口令与 token 比较均用 `crypto.timingSafeEqual`；错误信息统一「口令错误」。
- 登录限速：同 IP 每分钟 5 次；连续 10 次失败锁定 15 分钟；失败计数常驻内存（重启清零可接受，写审计日志）。
- 除 `GET /health`、`POST /api/login` 外，所有 `/api/*`、`/ws`、`/fs/*` 必须携带有效设备 token。
- 响应安全头：`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`；禁止 CORS 通配。
- 日志脱敏：token/口令/tunnel token/DSH 上游 Cookie 一律不落日志；错误只记录类型与状态码。
- `~/.dsh-mobile/` 目录 0700，配置文件与 cookie 0600。
- App 退出登录/撤销设备时清除本地 token 与缓存。

### 5.2 公网暴露面

- 公网入口由用户 Cloudflare 通道转发到网关 8790（Cloudflare 终止 TLS，App 使用 `https://<用户域名>`）。
- 网关仅监听 `0.0.0.0:8790`（局域网 http）。`/api/health` 公网可见，但只返回 `{ok,service,version,tunnel.running}`，不含主机/会话信息。
- App 设置页「远程访问」卡提示：公网已由 Cloudflare 通道暴露，请使用强口令；不在 App 内配置隧道凭据。
- 登录页失败提示不泄露剩余尝试次数。

## 6. 系统架构

```
┌──────────── Android App (Kotlin + Compose, arm64) ────────────┐
│ ApiClient(OkHttp RPC/WS) · Repository(StateFlow) · Compose UI │
│ BackgroundService(前台轮询 /api/pending) · NotificationManager│
└───────────────▲──────────────────────────────────────────────┘
                │ HTTPS(公网)/HTTP(局域网)   Bearer device token
┌───────────────┴───────────────┐
│ 用户自建 Cloudflare 通道        │  ← 用户自己配置 域名 → 内网:8790
└───────────────┬───────────────┘
                ▼
┌──────────────────────────────────────────────┐
│ dsh-mobile-gateway (Node, 零 npm 依赖, 8790)  │
│ 口令登录 / 设备 token / 限速 / WS ticket       │
│ POST /api/rpc → 上游 /api/<method> (Cookie)   │
│ WS /api/ws   → 上游 /api/remote.mux (透明)    │
│ /api/pending /api/respond /api/files/*        │
│ HTTP 长轮询流中心（上游 WS 由网关自持）         │
└───────────────┬──────────────────────────────┘
                │ 上游 Cookie(0600)
       ┌────────┴─────────┐
       │ DSH web (3080)   │
       │ RPC + remote.mux │
       └──────────────────┘
   DSH bundle plugin: /mobile 面板 + 自动拉起网关 + 上游 Cookie 交换
```

| 组件 | 运行位置 | 说明 |
| --- | --- | --- |
| `plugin/` | DSH 进程内 | 挂 `/mobile` 面板、交换上游 Cookie、spawn/守护网关、侧边栏入口 |
| `gateway/` | 独立 Node 进程 | 零 npm 依赖；鉴权、RPC 代理、HTTP 长轮询流中心、文件传输 |
| `app/android/` | Android 原生 | Kotlin + Jetpack Compose；OkHttp；Material3 自定义主题 |
| `.github/workflows/` | GitHub Actions | JDK17 + SDK36 构建 arm64 release APK，tag 触发 Release |

端口与目录约定：
- 网关端口：`DSH_MOBILE_GATEWAY_PORT`（默认 **8790**）；绑定 `0.0.0.0`。
- 配置目录：`~/.dsh-mobile/`（`config.json`、`initial-password.txt`、`upstream.cookie`、`bin/`、`logs/`）。
- 上游 DSH：由插件从 `ctx.webServer.host/port` 推导，Cookie 写入 `~/.dsh-mobile/upstream.cookie`。

## 7. 通道兼容设计（长轮询为主）

- 网关绑定 `0.0.0.0:8790`，接受任意 `Host` 与 `X-Forwarded-*` 头；用户 Cloudflare 通道转发到该端口即可。
- 事件通道 = **HTTP 长轮询**：网关内部维护一条到 DSH `/api/remote.mux` 的 WebSocket，把 App 打开的逻辑流（`$events`、`session/follow`）帧缓冲，App 用 `POST /api/stream/open` + `GET /api/stream/poll?wait=25000` 拉取；断线自动重开流。
- 为什么：用户的 Cloudflare 通道可能不透传 WebSocket；长轮询在 Cloudflare 上走普通 HTTPS 请求，兼容性最好，且长轮询被唤醒时延接近实时。
- `WS /api/ws` 仅作为局域网可选优化保留，App v1 不依赖。
- 网关不装、不管 cloudflared；不存储任何 Cloudflare 凭据。

## 8. UI 设计原则（DeepSeek 官方简洁风，Compose 实现）

- **色彩**：浅色 `#ffffff` 底、`#f5f6f7` 次级面、`#1a1a1a` 主文字、`#8b8b8b` 次文字、`#ececec` 分割线、`#4d6bfe` 品牌蓝、`#eef1ff` 弱蓝底。深色：`#191919/#232323/#ededed/#9b9b9b/#2c2c2c`，品牌蓝不变。
- **字体**：系统字体（Roboto/Noto Sans CJK）；标题 17sp/600，正文 15sp/400，辅助 13sp/400；行高 1.55。
- **布局**：44dp 顶栏、16dp 页面边距、行式列表（无卡片阴影）、圆角 12–18dp、1px 边框代替阴影；底部 3 Tab（对话 / 文件 / 设置）。
- **聊天**：用户气泡右侧蓝底白字；助手回答左侧无气泡纯文本；思考与工具调用默认折叠为一行（`思考 · N 秒` / `工具 · N 项`）；底部输入框圆角 22dp + 蓝色圆形发送键。
- **动效**：仅 150ms 透明度/位移动画；无立绘、无渐变装饰、无光晕。
- **禁止**：多主题皮肤、表情导航、统计大卡片、装饰性背景图。

## 9. 认证与连接流程（App）

1. 首次打开：连接页（Logo + 说明），输入「服务器地址」+「口令」（可保存多个服务器）。
2. `POST /api/login` → 设备 token + 主机摘要；token 存 `SharedPreferences`（应用私有目录）；后续请求带 `Authorization: Bearer`。
3. 若 `mustChangePassword=true` → 引导修改口令。
4. WS：`GET /api/ws-ticket` → `wss://…/api/ws?ticket=…`；断线指数退避重连（1.5s→60s）。
5. 退出登录/撤销设备 → 清空 token 与本地缓存。

## 10. M1 功能清单（= 本次验收范围）

| 模块 | M1 内容 |
| --- | --- |
| 连接 | 口令登录、多服务器保存/切换/删除、设备名、登录态持久、断线自动重连 |
| 会话 | 列表（标题/预览/时间/运行状态）、新建、重命名、归档、搜索、下拉刷新、向上分页历史 |
| 聊天 | 文本 + 图片发送、流式文本、思考折叠、工具调用折叠、审批卡片（允许一次/拒绝）、提问卡片（单选/多选/填空）、目标卡片、计划/待办、停止生成、排队/插队、模型与思考档位、Markdown 渲染与代码复制、回合结束提示 |
| 文件 | 允许根浏览、上传、下载、文本/图片预览 |
| 通知 | 前台审批/提问提醒、Android 系统通知（审批/提问）、后台轮询（30s/1m/5m/15m 可配） |
| 设置 | 服务器、口令修改、设备管理（撤销）、远程访问说明（Cloudflare 通道→8790）、通知、外观（浅/深/跟随）、关于 |
| 主机插件 | DSH 侧边栏入口 + 面板：网关状态、局域网/公网连接地址、初始口令、远程访问说明、开机自启说明 |

## 11. 验收标准（AC，实现后冻结）

| # | AC | 验证方式 |
| --- | --- | --- |
| AC1 | 隔离 profile 安装新插件后启动 `DSH_HOME=/root/.dsh-dev dsh web --profile web --host 127.0.0.1 --port 3091 --no-open`，`/mobile` 返回 200，网关 8790 自动拉起且 `/api/health` 返回 `ok:true` | 命令 + HTTP 状态 + JSON |
| AC2 | 无 token 访问 `/api/rpc` → 401；错误口令第 6 次/分钟 → 429；正确口令返回设备 token；撤销设备后原 token → 401 | `node --test tests/auth.test.js` + E2E 脚本 |
| AC3 | 登录后 `/api/rpc` 的 `session/list`、`session/modelCatalog` 返回真实 DSH 上游数据 | E2E 脚本输出（脱敏） |
| AC4 | `GET /api/ws-ticket` 后连接 `/api/ws`，open `$events` 收到上游 `{type:'ready'}`；open `session/follow` 收到 `snapshot` | E2E 脚本 |
| AC5 | `/api/files` 列目录；下载内容 SHA-256 与源文件一致；`..` 越权 → 403 | `tests/files.test.js` |
| AC6 | 通道兼容：`/api/stream/open`→`poll` 能收到 `$events` ready 与 `session/follow` snapshot；请求带 `Host: example.com` + `X-Forwarded-Proto: https` 仍正常（模拟 Cloudflare 反代）；网关不依赖 WS 即可完成上述 | `tests/stream.test.js` + 反代模拟 |
| AC7 | Android release APK 构建成功；aapt2 校验 `packageName=com.smallora.dshmobile`、`minSdk=31`、`targetSdk=36`、仅 `arm64-v8a`、无 `testOnly=true`；apksigner 校验签名 | `./gradlew assembleRelease` + aapt2/apksigner 输出 |
| AC8 | App 关键流程（登录→会话→聊天→审批→文件→远程访问说明）在真机走通 | 用户真机人工验收（截图/录屏）；JVM 单测覆盖协议/状态机 |
| AC9 | 推送 `v*` tag 后 GitHub Actions 成功产出 Release（APK + SHA256） | `gh run list` / Release 链接 |
| AC10 | 口令 scrypt、token 仅哈希存储、配置/目录权限 0600/0700、日志无敏感信息 | 单测 + `grep` 证据 |
| AC11 | UI 符合 §8（浅色/深色、无多主题、无装饰动效） | Compose 截图/真机截图 + 人工验收 |
| AC12 | 现有 8787 插件与 3080 DSH 未被改动/中断 | 改动前后 health/进程对比 |

## 12. 契约

- 网关 HTTP/WS API：见 `docs/interfaces.md`（实现前冻结，实现中变更需在验收包写明）。
- DSH 现代协议：`POST /api/<method>` + `{args}`；`WS /api/remote.mux` + `{type:'open',streamId,endpoint,payload}` / `{type:'item',streamId,value}`；审批/提问走 `$events` waterfall + `$events/result`。详见 interfaces.md §3。
- App 内部模块契约：见 interfaces.md §5。

## 13. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| DSH 事件 schema 漂移 | 宽松归一化 + 真实 DSH E2E；未知事件忽略不崩溃 |
| 公网暴露被爆破 | 限速/锁定 + 强口令提示 + 登录默认要求首次改密 |
| 无真机/模拟器 | JVM 单测 + APK 静态门禁 + 用户真机验收；验收包标注未验证项 |
| 通道不透传 WS | 事件改 HTTP 长轮询；WS 仅可选 |
| Kotlin/Compose 依赖下载 | 本地已实测 Gradle/AGP/Compose 构建链；CI 用官方 SDK |
| 误装到 live profile | 只在 `DSH_HOME=/root/.dsh-dev` 隔离环境测试；live 安装需验收确认 |

**回滚**：新项目为独立仓库/独立目录/独立端口；回滚 = 停用新插件 + 删除 `~/.dsh-mobile`，现有系统零改动。

## 14. 人工门禁点

1. 本 SPEC v2 确认（开工前）。
2. 是否安装到 live `web` profile（验收时）。
3. 推送 GitHub 仓库/触发 Release（用户已授权推送与自动构建；打 tag 发布前在验收包中再次确认）。
4. 反向代理/Cloudflare 通道配置由用户自行完成，AI 不读取、不代填任何凭据。
