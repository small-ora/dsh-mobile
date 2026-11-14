# 架构说明（dsh-mobile）

## 1. 组件

| 组件 | 运行位置 | 职责 |
| --- | --- | --- |
| `plugin/` | DSH 进程内 | 挂 `/mobile` 主机面板与 `/mobile/api/*`；用 `connection.authenticatedUrl()` 交换上游 Cookie；spawn/守护网关；注册侧边栏入口 |
| `gateway/` | 独立 Node 进程（零 npm 依赖） | 口令登录/设备 token/限速/WS ticket；`/api/rpc` 白名单代理；`/api/stream/*` HTTP 长轮询流中心；`/api/files/*` 文件；`/api/attachment` 会话附件；`/api/pending`、`/api/respond`；健康检查 |
| `app/android/` | Android 12+（arm64-v8a） | Kotlin + Jetpack Compose 原生 App：`core/`（ApiClient/StreamPump/SessionRepository/ChatReducer/Notifier/BackgroundService）+ `ui/`（chat/files/settings/login/sessions） |
| `.github/workflows/` | GitHub Actions | tag `v*` 构建 arm64 APK，跑单测与 APK 门禁，发布 Release |

## 2. 端口与目录

| 项 | 值 |
| --- | --- |
| 网关端口 | `DSH_MOBILE_GATEWAY_PORT`（默认 8790） |
| 网关监听 | `DSH_MOBILE_GATEWAY_HOST`（默认 0.0.0.0） |
| 配置目录 | `~/.dsh-mobile/`（0700）：`config.json`、`initial-password.txt`、`upstream.cookie`、`upstream.json`、`logs/` |
| DSH 上游 | 插件从 `ctx.webServer.host/port` 推导；Cookie 写 `upstream.cookie`（0600） |
| Android 包名 | `com.smallora.dshmobile` |

## 3. 数据流

### 3.1 登录

```
App ──POST /api/login {password, deviceName}──▶ Gateway
       ◀── {token(43B base64url), deviceId, mustChangePassword, host}
App 存 token（SharedPreferences）；之后所有请求 Authorization: Bearer <token>
mustChangePassword=true 时，除 health/login/me/password/logout 外全部 403
```

### 3.2 RPC

```
App ──POST /api/rpc {method, args}──▶ Gateway ──POST /api/<method>
     {type:'client-request', rpcId, method, payload:{args}} + Cookie──▶ DSH
     ◀── {result:{ok,value|error}} 原样返回
```

方法白名单见 `docs/interfaces.md` §4；网关只透传 args、不改写。

### 3.3 事件流（HTTP 长轮询）

网关向 DSH 维护一条 `ws://<dsh>/api/remote.mux`（Cookie 注入）：
- App 打开逻辑流：`POST /api/stream/open {endpoint, args}` → `{streamId}`
- App 拉取：`GET /api/stream/poll?streamId=&since=&wait=25000`
- 网关把 `$events`（审批/提问/会话事件）与 `session/follow`（快照+增量+流式 token）的帧放入每流环形缓冲（2000 条/2 MiB），断线按 1.5s→60s 退避重连并给流打 `reset`。
- 每设备最多 20 条流、全局 200；流绑定设备，跨设备访问 404；撤销设备/登出关闭其全部流。

### 3.4 文件与附件

- 文件：`/api/files` 列表、`/api/files/download`（Range）、`/api/files/upload`（raw，2 GiB 上限）、`/api/files/preview`（≤1 MiB 文本）。
  路径先做词法根校验，再 `realpath` 复核，越界 403；允许根 = 家目录 + DSH 工作区。
- 附件：DSH 消息内图片/文件是 `attachment` 引用（`attachmentId = sha256:<64hex>`）。
  App → `GET /api/attachment?sessionId=&attachmentId=` → 网关用上游 Cookie 请求 DSH 插件
  `GET /mobile/api/session-file` → 插件进程内 `sessionController.inspect` + `attachments.readImage/readFileStream` 流式返回。

### 3.5 后台通知（Android）

`BackgroundService`（`dataSync` 前台服务）按配置间隔轮询 `/api/pending`；新审批/提问发系统通知（通道 `dsh_attention`）。网关自身长驻 `$events` 流维护 pending 缓存，因此 UI 不在前台也能提醒。

## 4. App 状态模型（概览）

`SessionRepository` 是唯一数据源：
- `sessions / filteredSessions / workspaces / selectedWorkspace`
- `connection(online,error)`、`pending`、`modelCatalog`
- 每个打开会话一个 `ChatUiState`：`items`（User/Assistant/Tool/Notice，含 images）、`queued`、`permission`、`commands`、`cwd`、`goal/todos`、`hasMore`

`StreamPump` 负责每流 open→poll→分发→since 更新；`ChatReducer` 把 DSH 事件/帧归一化为上述模型（宽松解析，未知事件忽略）。

## 5. DSH 版本与兼容

- 目标 DSH：`0.2.1-rc.2`（现代协议：`/api/<method>` + `args`、`/api/remote.mux`、`$events` waterfall、`commands/*`、`permissionPresets/catalog`）。
- 插件 `engines.dsh` 为最低版本；DSH 升级后如事件字段变化，`ChatReducer` 做宽松兼容并在新版本修复。
- 与旧 `dsh-remote-plugin` 并存：端口 8787 vs 8790、配置目录 `~/.dsh-remote` vs `~/.dsh-mobile`，互不影响。

## 6. 失败模式

| 现象 | 机制 |
| --- | --- |
| 网关进程退出 | 插件守护自动重启（退避），面板显示 restarts/日志 |
| 上游不可达 | `/health.upstream.ok = healthy || streamHub.connected`；RPC 返回 502 |
| WS（网关→DSH）断开 | 长轮询流打 reset，自动重连后重开所有已注册流 |
| App 断线 | StreamPump 指数退避重开；`session/follow` 重新拉快照 |
| 长轮询被中间层截断 | 25s 内返回；App 以 `next` 续拉，不丢事件 |
