# SPEC v5（as-built）— dsh-mobile：DSH 移动远程客户端

- 状态：**已交付 v0.1.11**（App `0.1.11` / 插件 `0.1.11`）
- 交付仓库：`small-ora/dsh-mobile`（public）
- 文档：安装/架构/安全/排障/发布见 [README](README.md) 与 [docs/](docs/)

## 1. 背景

现有 `dsh-remote-plugin` + Capacitor App 功能齐全，但：

1. 界面花哨（深色 + 12 套主题 + 立绘/动效），与 DeepSeek 官方简洁风相悖；
2. 外网访问依赖 Tailscale/自建中转，不便适配用户已有的 Cloudflare 通道；
3. App 为 WebView 壳，用户要求改为 Android 原生实现；
4. 鉴权只有单一 Bearer token，缺少口令登录、设备管理与暴力破解防护。

## 2. 分级

**L3**：新增口令/设备 token 鉴权、通过用户自建 Cloudflare 通道暴露公网、新增 GitHub 发布链路。
流程：勘察 → 隔离 → SPEC（人确认 3 轮调整）→ PLAN → 分步实现 → 自检 → 独立审查（2 份 fresh）→ 人工验收包。

## 3. 目标（已达成）

- 全新 DSH bundle 插件 `dsh-mobile-plugin` + 独立零依赖网关（8790）+ 全新原生 Android App（Kotlin + Jetpack Compose，arm64-v8a，Android 12+/minSdk 31，targetSdk 36）。
- 界面为 DeepSeek 官方简洁风（白底/品牌蓝/行式列表/浅深色），**无多主题、无装饰动效**。
- **兼容用户自建 Cloudflare 通道**：网关只监听 `0.0.0.0:8790`，用户把域名转发到该端口；不管理 cloudflared、不保存 Tunnel Token。
- **HTTP 长轮询事件通道**：App 不依赖 WebSocket（适配不透传 WS 的通道）；WS 仅作网关可选能力。
- 口令登录（scrypt）+ 每设备独立 token（哈希存储、可撤销、30 天滑动）+ 登录限速 + 短时 ticket。
- App 图标：DSH 官方鲸鱼 logo + 「Harness」字样（与 DeepSeek 官方白底蓝鲸区分）；通知栏图标同步鲸鱼剪影。

## 4. 交付物

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| 网关 | `gateway/` | 零 npm 依赖 Node ESM：鉴权、RPC 代理、长轮询流中心、文件/附件、反代信任、健康检查 |
| 插件 | `plugin/` | DSH bundle：`/mobile` 面板（状态/口令/设备管理/日志）、上游 Cookie 交换、网关守护、侧边栏入口 |
| App | `app/android/` | Kotlin + Compose：core（ApiClient/StreamPump/Repository/Reducer/Notifier/后台服务）+ ui（chat/files/settings/login/sessions） |
| CI | `.github/workflows/android.yml` | tag `v*` → 单测 + APK 门禁 + Release（APK + sha256） |
| 文档 | `README.md`、`docs/*`、`CHANGELOG.md` | 安装、架构、接口、安全、排障、发布 |

## 5. 功能范围（已交付）

- **连接**：口令登录、多服务器保存/切换、设备名、登录态持久、断线自动重连；口令/设备 token/限速/撤销；首次登录强制改密（服务端拦截）。
- **会话**：列表（标题/预览/时间/运行状态/工作区）、**工作区筛选**、**下拉刷新**、新建、重命名、归档、搜索（当前主机未启用时给中文提示）、向上分页历史；**子 Agent 会话不在列表/搜索中展示**。
- **聊天**：文本/图片发送；流式文本与思考（**reasoning 不混入正文**）；工具调用与思考合并为「过程 · N 项」默认收起（**工具结果内的图片直接渲染**）；审批/提问卡片；目标与待办；停止；排队/插队（**排队消息可插队/删除**）；模型与思考档位（「＋」功能菜单）；权限切换（`/permission`）；斜杠指令菜单（`/compact /export /feedback /goal /permission /plan`）；Markdown 渲染与代码复制；**Markdown 图片（含表格内图片）与附件图片渲染**（全屏预览）；回合结束提示。
- **文件**：允许根浏览、上传（流式）、下载、文本/图片预览。
- **通知**：Android 审批/提问通知；后台轮询（30s/1m/5m/15m）；任务结束通知渠道。
- **设置**：服务器、改口令、设备管理（撤销；撤销当前设备自动登出）、通知、外观（浅/深/跟随）、关于、退出登录。
- **主机插件面板**：局域网地址、初始口令、网关状态、事件通道、**设备管理（在线/撤销）**、网关日志。

## 6. 非目标（M2 backlog，未实现）

插件中心、技能中心、洞察/费用、团队、任务看板、Doctor、公告/反馈、操作回执、离线历史缓存、文件收件箱、断点续传、局域网自动发现、扫码配对、多主题皮肤。

> 明确不做（用户决策 2026-11-15）：iOS/鸿蒙、云中转 relay/E2EE。

## 7. 验收标准与结果

| # | AC | 结果 | 证据 |
| --- | --- | --- | --- |
| AC1 | 隔离 DSH 安装插件后 `/mobile` 200、网关 8790 健康 | ✅ | `dsh plugin add` + `/mobile` 200 + `/health ok` |
| AC2 | 鉴权（401/403/429/撤销/强制改密） | ✅ | gateway `node:test`（51/51）+ E2E |
| AC3 | RPC 真实上游数据（session/list、modelCatalog、工作区等） | ✅ | 隔离 DSH E2E + 真实 DSH 协议核对 |
| AC4 | `$events` ready + `session/follow` snapshot（长轮询） | ✅ | E2E 18/18 |
| AC5 | 文件列表/下载 SHA-256/越权 403/上传 | ✅ | 单测 + E2E |
| AC6 | 反代兼容（任意 Host/X-Forwarded、不依赖 WS） | ✅ | E2E + 单测 |
| AC7 | APK 门禁（包名/minSdk31/target36/arm64/非 testOnly/签名） | ✅ | `scripts/verify-apk.sh` + CI |
| AC8 | App 关键流程真机可用 | ✅（用户真机验收） | adb 真机走查：登录/会话/聊天/审批/文件/图片/工作区/排队/权限/指令 |
| AC9 | GitHub Actions → Release | ✅ | v0.1.1…v0.1.11 CI success + Release 资产 |
| AC10 | 凭据存储/权限/日志脱敏 | ✅ | 单测 + 权限 700/600 + 敏感信息扫描 |
| AC11 | UI 简洁风 | ✅ | 真机截图（浅/深色、无多主题装饰） |
| AC12 | 现有 8787/3080 未受影响 | ✅ | 前后 health/进程对比 |

## 8. 关键设计决策（历次调整）

1. **App 原生 Kotlin + Compose**（非 WebView/Capacitor），图标/风格按 DeepSeek 简洁风。
2. **取消 Cloudflare 托管**：用户已自建通道，网关只开端口；不读取/存储任何 CF 凭据。
3. **事件通道 HTTP 长轮询**：规避通道不透传 WebSocket 的风险；网关内部维护一条上游 WS。
4. **UI 迭代**：中间思考默认折叠 → 模型/权限移入「＋」功能菜单（顶栏保留完整标题）→ 排队消息插队/删除 → 官方鲸鱼图标 + Harness。
5. **图片全链路**：DSH 附件引用（插件内进程读取 → 网关流式代理）与 Markdown 图片（工作区/远程/data）双路径；所有响应体读取必须在 IO 线程。

## 9. 安全与风险

见 [docs/SECURITY.md](docs/SECURITY.md)。关键记录：

- 发布签名密钥**不入仓库**，CI 使用 GitHub Secrets；v0.1.0 曾因密钥入库撤回并轮换密钥重发 v0.1.1。
- 登录限速默认只信任回环反代头；外部 cloudflared 需配置 `DSH_MOBILE_TRUST_PROXY_CIDRS`。
- 已知局限：App token 未用 Keystore 加密；局域网明文 HTTP；多设备共用管理口令。

## 10. 回滚

独立仓库/独立进程/独立目录（`~/.dsh-mobile`）。回滚 = 停用新插件 + 删除 `~/.dsh-mobile`；现有 DSH 与旧插件（8787）零改动。
