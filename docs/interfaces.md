# docs/interfaces.md — dsh-mobile 对接契约 v1（实现前冻结）

本文件是 SPEC v1 的落地契约。**实现中如需变更，必须在验收包写明 old → new。**
来源均为本机 `@deepseek-ai/dsh@0.2.1-rc.2` 源码实测/阅读，已标注依据。

## 1. 目录与端口

| 项 | 值 |
| --- | --- |
| 网关默认端口 | `8790`（env `DSH_MOBILE_GATEWAY_PORT`） |
| 绑定地址 | `0.0.0.0`（局域网）；公网由用户 Cloudflare 通道转发到 8790 |
| 配置目录 | `~/.dsh-mobile/`（0700）：`config.json`、`initial-password.txt`、`upstream.cookie`、`bin/`、`logs/` |
| App 形态 | Kotlin + Jetpack Compose 原生 APK（无 WebView、无静态 Web 资源） |
| DSH 插件挂载点 | `/mobile`（webServer prefix route） |

## 2. 网关 HTTP API（App 唯一入口）

所有响应 `application/json; charset=utf-8`，除注明外都需要 `Authorization: Bearer <deviceToken>`。

### 2.1 认证

| 方法 | 路径 | 入参 | 出参 |
| --- | --- | --- | --- |
| POST | `/api/login` | `{password, deviceName}` | `200 {token, deviceId, deviceName, mustChangePassword, host:{version,gatewayVersion}}`；`401 {error:'invalid-password'}`；`429 {error:'rate-limited', retryAfterMs}`；`423 {error:'locked', until}` |
| GET | `/api/me` | — | `{device:{id,name,createdAt,lastSeenAt}, host:{version,gatewayVersion}}` |
| POST | `/api/logout` | — | `{ok:true}`，撤销当前 token |
| POST | `/api/password` | `{current, next}` | `{ok:true}`；首次改密后 `mustChangePassword=false`。失败限速：6 次/分钟→429（Retry-After）、连续 10 次→423；同设备并发→409 |
| GET | `/api/devices` | — | `{items:[{id,name,platform,createdAt,lastSeenAt}]}` |
| DELETE | `/api/devices/:id` | — | `{ok:true}` |
| GET | `/health` | — | **无需鉴权** `{ok, service:'dsh-mobile', version, upstream:{ok}, mustChangePassword}`（无主机/会话信息） |

### 2.2 RPC 代理（现代协议）

`POST /api/rpc`，body `{method: string, args: object}`；网关向上游发送
`{type:'client-request', rpcId, method, payload:{args}}`，返回上游 `result` 原样：

```json
{"ok":true,"value":{...}}          // 成功
{"ok":false,"error":{"code":"session/not-found","message":"…","details":{}}}
```

- HTTP 状态：网关自身鉴权失败 401；上游不可达 502 `{error:'upstream-unavailable'}`；`/api/rpc` JSON 体上限 **32 MiB**（图片 base64 内联），超限 413 `body-too-large`；其他 JSON 端点仍 16 KiB/1 MiB。
- 网关只做方法白名单（见 §4），不做参数改写。
- `rpcId` 由网关生成，不暴露给客户端。

### 2.1.1 mustChangePassword 服务端强制

`mustChangePassword=true` 时，除 `GET /health`、`POST /api/login`、`GET /api/me`、`POST /api/password`、`POST /api/logout` 外，所有 `/api/*`（含 rpc / stream / files / pending / respond / devices / ws-ticket）与 WS 握手返回 **403 `{error:'password-change-required'}`**；改密成功即时解除（同进程状态），其他设备 token 不受影响。

### 2.3 事件/流代理（HTTP 长轮询为主，WebSocket 可选）

> 背景：用户现有 Cloudflare 通道可能不透传 WebSocket，因此 **App 只用 HTTP 长轮询**；网关内部用一条到 DSH 的 WebSocket 流，把帧缓冲并提供给 App 拉取。`/api/ws` 仅作为局域网可选优化保留（App v1 不使用）。

- 网关启动后建立并维护**一条**到上游 `ws://<dsh>/api/remote.mux` 的连接（Cookie 注入），支持在其上开多个逻辑流；断线指数退避重连（1.5s→60s），重连后自动重开已登记的流；重连时所有流带 `reset:true`，pending 缓存清空并由上游重放重建。
- `POST /api/stream/open` body `{endpoint:'$events'|'session/follow', args:{…}}` → `{streamId}`。
  - `$events` 的 args 为 `{}`；`session/follow` 的 args 形如 `{request:{address:{kind:'session',sessionId},assistantStream:true}}`。
- `GET /api/stream/poll?streamId=&since=0&wait=25000` → 长轮询（有数据立即返回，无数据最多挂 25s）：
  ```json
  {"items":[{"seq":1,"value":{"type":"ready","clientId":"…","host":{…}}}],"next":2,"closed":false}
  {"items":[],"next":7,"closed":true,"error":{"code":"…","message":"…"}}
  ```
  每个流有环形缓冲（2000 条 / 2 MiB），`since` 落后被截断时返回 `reset:true` 并给出当前 `next`（App 清空重收）。
  - 流归属：每条流绑定打开它的设备；`poll`/`cancel` 必须 owner 匹配，**不存在/跨设备/内部流一律 404 `{error:'stream-not-found'}`**（不泄露存在性）。
  - 每设备最多 20 条流（全局 200），超限 `POST /api/stream/open` → 429 `{error:'too-many-streams'}`；`GET /api/streams` 只列本设备非内部流。
  - 设备登出/撤销 → 关闭该设备全部流；WS ticket 依赖设备存在性，撤销后立即失效。
  - 上游 `error` 帧导致流结束时仍返回 200 + `closed:true, error`（App 正常展示）。
- `POST /api/stream/cancel` body `{streamId}` → 关闭该流并释放上游（非本人/内部流 404）。
- `GET /api/streams` → `{items:[{streamId,endpoint,seq,closed,at}]}`（仅本设备、非内部流）。
- `WS /api/ws?ticket=`（可选）：同一 hub 的实时版本，帧格式同上游 `{type:'open'|'cancel'|'item'|'error'}`；App v1 不依赖。设备注销/登出时该设备已有 WS 以 **4401** 关闭，open 时复查设备有效性。
- 帧内容与上游一致（`{type:'item',streamId,value}` 中的 `value` 即 `poll.items[].value`）：`ready`/`waterfall`/`emit`/`cancel`（`$events`），`snapshot`/`event`/`assistant-stream`（`session/follow`）。

### 2.4 待处理请求（后台通知用）

- 网关自持一条持久上游 WS，长期订阅 `$events`，把 `waterfall` 帧缓存为 pending 集合；收到 `cancel` 或自身转发的 `$events/result` 成功时移除。
- `GET /api/pending` → `{items:[{kind:'approval'|'question', eventId, sessionId, toolName?, reason?, questions?, at}]}`（供 Android 后台服务轮询）。
- 回答：`POST /api/respond` body `{eventId, outcome}`。`outcome` 是**任意 JSON 值**（不是固定对象）：approval 传字符串 `"allowed-once"`/`"rejected"`，question 传对象 `{answers:[{id, selected:[], custom?}]}`。网关调上游 RPC `$events/result` args `{clientId, eventId, outcome:{kind:'result', value: <outcome>}}`，返回 `{accepted:boolean}`。

### 2.5 文件

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/files?path=` | `{path, roots:[…], entries:[{name,path,type:'file'|'dir',size,mtimeMs}]}`；越界 403 |
| GET | `/api/files/download?path=` | 流式下载，支持 `Range`；`content-disposition` |
| POST | `/api/files/upload?path=&name=` | raw body，上限 2 GiB；`x-content-sha256` 可选校验；冲突默认拒绝（409），`overwrite=1` 覆盖 |
| GET | `/api/files/preview?path=` | ≤1 MiB 文本 / 图片直接回源，否则 415 |

允许根：`~`（家目录）+ `config.roots` 额外目录 + DSH 当前工作区（插件启动时通过配置传给网关）；解析后必须落在根内（realpath 校验，防符号链接穿越）。

### 2.6 管理接口（口令/设备以外无隧道管理）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/password` | `{current, next}` 修改口令（见 2.1） |
| GET | `/api/devices` | 设备列表（见 2.1） |
| DELETE | `/api/devices/:id` | 撤销设备（见 2.1） |
| POST | `/api/admin/restart-gateway` | 仅插件面板使用（插件侧令牌），重启网关进程 |

> **没有 Cloudflare/cloudflared 管理接口**：用户已在内网自行安装并连通官方 Cloudflare 通道，只需在 Cloudflare 侧配置「域名 → 本机 8790」，网关不做隧道进程管理、不存储 Tunnel Token。

### 2.7 安全头与 CORS

- 所有响应：`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`、`Cache-Control: no-store`（静态资源除外，用 ETag）。
- 网关不托管 Web SPA（App 为原生）；`GET /` 返回极简状态页（无鉴权、无敏感信息）。
- 反代兼容：接受任意 `Host` 头、`X-Forwarded-Proto`/`X-Forwarded-For`，不依赖 WS；绑定 `0.0.0.0:8790` 供 Cloudflare 通道转发。
- 不返回 `Access-Control-Allow-Origin`（原生 App 不需要 CORS）。

## 3. DSH 现代协议要点（App 直接使用）

### 3.1 RPC（`POST /api/rpc` → 上游 `/api/<method>`）

参数一律包在 `args` 对象里；生成器要求参数名（见 `dsh-api-session-controller/lib/typert.host.js`）：

| method | args | 用途 |
| --- | --- | --- |
| `session/list` | `{_request:{}}` | 会话列表；items 含 projections（title/goal/todos/modelSelection/userQuestions/subagentCatalog/tokenUsage…）。**注意：只有 list 用 `_request`** |
| `session/modelCatalog` | `{}` | 模型目录（无参数） |
| `session/follow` | **流**：`{request:{address:{kind:'session',sessionId}, assistantStream:true, maxMessages?, turnWindow?}}` | 打开快照 + 实时事件；streamId 自定 |
| `session/page` | `{request:{address:{kind:'session',sessionId}, throughSeq, beforeSeq?, maxMessages?, turnWindow?}}` | 向上翻历史 |
| `session/prompt` | `{request:{requestId:<uuid>, sessionId, mode:'queue'|'steer', content:[{type:'text',text}|{type:'image',mediaType,data,name?}]}}` | 发送消息（**必填客户端生成的 `requestId`；断线不自动重试**） |
| `session/cancel` | `{request:{sessionId}}` | 停止生成 |
| `session/create` | `{request:{cwd?|workspaceId?, sessionId?, agentPreset?}}` | 新建会话 |
| `session/rename` | `{request:{sessionId,title}}` | 重命名 |
| `session/search` | `{request:{query}}` | 搜索 |
| `session/selectModel` | `{request:{sessionId,provider,model,reasoningEffort?}}` | 模型/思考档位 |
| `session/updateQueue` | `{request:{sessionId,items:[…]}}` | 队列编辑 |
| `session/fork` | `{request:{sessionId,atSeq?}}`（也接受 `_request` 变体） | 从回合创建分支 |
| `$events/result` | `{clientId,eventId,outcome:{kind:'result',value}}` | 回答审批/提问 |

> 精确参数以 `dsh-api-session-controller/lib/typert.host.js` 的 `parameters[].name` 与 `typert.remote-client.d.ts` 为准；实现前逐项核对。

### 3.2 流（`WS /api/ws`）

- `$events`：首个 item 是 `{type:'ready',clientId,host:{home,cwd,version,attachedSessions}}`；随后 `{type:'waterfall',...}`（需回答）、`{type:'emit',event,args}`（`api-session/added|removed|status|activity`、`user-questions/*` 等）、`{type:'cancel',eventId}`。
- `session/follow`：`snapshot`（records 为本轮历史、projections 为完整状态）→ `event`（`assistant/message`、`tool/call`、`tool/result`、`user/message`、`turn/start|end`…）→ `assistant-stream`（实时增量：`text-delta`/`reasoning-delta`/`tool-call-delta`/`block-end`）。
- 断线重连：重新 open；`session/follow` 会从 opening 重新给快照（用 `cursor`/`seq` 去重）。
- 审批/提问的会话归属：`waterfall.agentId === sessionId`。

### 3.3 事件渲染映射（App 归一化）

| 事件 | 渲染 |
| --- | --- |
| `user/message` | 右侧蓝色气泡；`message.content` 为块数组（text/image） |
| `assistant/message` | 左侧纯文本；`message.content` 块数组；`interrupted:true` 显示已停止 |
| `assistant/attempt` | 失败尝试（折叠，低调显示） |
| `tool/call` → `tool/result` | 折叠行「工具 · name」；展开显示参数/结果；isError 标红 |
| `approval/asked` / `approval/decided` | 审计事件，不单独渲染（pending 来自 waterfall） |
| `turn/start` / `turn/end` | 运行中状态/回合结束（错误原因提示） |
| `goal/change` | 目标卡片（phase active/paused/blocked/complete） |
| `todo/write` | 待办列表 |
| `session/title` | 顶栏标题 |
| `compaction/*` | 「整理上下文…」状态行 |
| `subagent/*` | 子代理折叠入口（M1 只读列表） |

## 4. 网关方法白名单

RPC 仅放行：
`session/list`、`session/modelCatalog`、`session/follow`（流）、`session/page`、`session/projections`、`session/prompt`、`session/cancel`、`session/create`、`session/rename`、`session/search`、`session/selectModel`、`session/updateQueue`、`session/fork`、`session/attachment`、
`goals/get`、`goals/pause`、`goals/resume`、`goals/complete`、`goals/clear`、
`workspace/archiveSession`、`workspace/unarchiveSession`、`workspace/create`、
`$events/result`。

- 参数命名（v3 实测更正）：**只有 `session/list` 用 `{_request:{}}`**；`session/page|prompt|cancel|create|rename|search|selectModel|updateQueue|follow|projections|attachment` 与 `workspace/*` 均为 `{request:{…}}`；`goals/get` 为 `{agentId}`；`goals/pause|resume|complete|clear` 为 `{agentId, ref:{id,revision}}`（`ref` 取自会话 goal 投影的 `goal.id` + `goal.revision`）。`session/prompt` 必须带客户端生成的 `requestId`（UUID）。网关只透传 args、不改名。
- 流仅放行：`$events`、`session/follow`。
- 其他一律 403 `{error:'method-not-allowed'}`。

## 5. App 原生模块契约（Kotlin + Jetpack Compose，零 WebView）

### 5.1 目录（写范围）

```
app/android/
├── settings.gradle, build.gradle, gradle.properties, gradlew, gradle/wrapper/, keystore/
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/…
        └── java/com/smallora/dshmobile/
            ├── MainActivity.kt                  (core)
            ├── core/                            (core)
            │   ├── Models.kt / Json.kt / ApiClient.kt / StreamClient.kt
            │   ├── SessionRepository.kt / FilesClient.kt / AdminClient.kt
            │   ├── SettingsStore.kt / Notifier.kt / BackgroundService.kt
            │   └── AppContainer.kt
            └── ui/                              (core: App/theme/login/sessions；ui: chat/files/settings)
                ├── theme/DshTheme.kt            (core)
                ├── App.kt                       (core)
                ├── login/LoginScreen.kt, LoginViewModel.kt        (core)
                ├── sessions/SessionsScreen.kt, SessionsViewModel.kt （core）
                ├── chat/ChatScreen.kt, ChatViewModel.kt, ChatComponents.kt （ui）
                ├── files/FilesScreen.kt, FilesViewModel.kt         （ui）
                ├── settings/SettingsScreen.kt, SettingsViewModel.kt （ui；含「远程访问」说明卡）
                └── md/Markdown.kt               （ui）
```

依赖（Gradle，均已本机验证可构建）：AGP `8.13.0`、Kotlin `2.2.20`、`org.jetbrains.kotlin.plugin.compose`、`androidx.compose:compose-bom:2025.10.01`、material3、activity-compose `1.11.0`、lifecycle-runtime-compose `2.9.4`、lifecycle-viewmodel-compose `2.9.4`、kotlinx-coroutines-android、`com.squareup.okhttp3:okhttp:4.12.0`；JSON 用平台 `org.json`。`compileSdk=36, minSdk=31, targetSdk=36, abiFilters=['arm64-v8a']`。

### 5.2 核心类型（core/Models.kt，core 负责实现；ui 只读消费）

```kotlin
data class ServerProfile(val id: String, val name: String, val base: String)
data class DeviceInfo(val id: String, val name: String)
data class HostInfo(val version: String?, val gatewayVersion: String?)
data class LoginResult(val token: String, val device: DeviceInfo, val mustChangePassword: Boolean, val host: HostInfo)
data class Goal(val id: String, val objective: String, val phase: String, val blockedReason: String?, val revision: Long)
data class Todo(val content: String, val status: String)          // completed|pending|in_progress
data class ModelSelection(val provider: String, val model: String, val reasoningEffort: String?)
data class ModelOption(val provider: String, val model: String, val name: String, val reasoning: List<String>)
data class SessionRow(
  val id: String, val title: String?, val preview: String?, val updatedAt: Long,
  val running: Boolean, val blank: Boolean, val parentSessionId: String?, val cwd: String?,
  val goal: Goal?, val todos: List<Todo>, val selection: ModelSelection?, val agentAvailable: Boolean)
data class QuestionOption(val label: String, val description: String?)
data class Question(val id: String, val question: String, val header: String?, val detail: String?,
  val options: List<QuestionOption>, val multiSelect: Boolean)
data class PendingRequest(
  val kind: String,            // "approval" | "question"
  val eventId: String, val sessionId: String,
  val toolName: String?, val reason: String?,
  val questions: List<Question>, val at: Long)
data class FileEntry(val name: String, val path: String, val dir: Boolean, val size: Long, val mtimeMs: Long)
data class FileListing(val path: String, val roots: List<String>, val entries: List<FileEntry>)
data class ConnState(val online: Boolean, val server: String?, val error: String?)

sealed interface ChatItem { val key: String
  data class User(override val key: String, val text: String, val images: List<String>) : ChatItem
  data class Assistant(override val key: String, val text: String, val reasoning: String?,
    val streaming: Boolean, val interrupted: Boolean) : ChatItem
  data class Tool(override val key: String, val name: String, val args: String, val result: String?,
    val isError: Boolean, val running: Boolean) : ChatItem
  data class Notice(override val key: String, val text: String, val kind: String) : ChatItem
}

data class ChatUiState(
  val sessionId: String, val title: String, val running: Boolean,
  val items: List<ChatItem>, val pending: List<PendingRequest>,
  val goal: Goal?, val todos: List<Todo>, val selection: ModelSelection?,
  val hasMore: Boolean, val loadingOlder: Boolean, val error: String?)
```

### 5.3 Repository / Client 契约（core 实现；ui 只调用）

```kotlin
class ApiClient(context: Context) {
  fun setSession(server: String, token: String?)        // 设置当前连接与凭据
  suspend fun login(base: String, password: String, deviceName: String): LoginResult
  suspend fun me(): HostInfo
  suspend fun logout()
  suspend fun rpc(method: String, args: JSONObject): JSONObject      // 返回上游 result JSON
  // 事件流（HTTP 长轮询；网关内部维护上游 WS）
  suspend fun openStream(endpoint: String, args: JSONObject): String   // → streamId
  suspend fun pollStream(streamId: String, since: Long, waitMs: Long = 25000): StreamPoll
  suspend fun cancelStream(streamId: String)
  suspend fun download(path: String): okhttp3.Response
  suspend fun upload(dir: String, name: String, bytes: ByteArray): JSONObject
  suspend fun pending(): List<PendingRequest>
  suspend fun respond(eventId: String, outcome: JSONObject): Boolean
}

data class StreamPoll(val items: List<Pair<Long, JSONObject>>, val next: Long, val closed: Boolean,
  val reset: Boolean, val error: String?)

// StreamPump：每个流一个协程循环 open→poll(since)→分发 items→更新 since；断线/closed 时按
// 1.5s→60s 退避重开；由 SessionRepository 持有，向 UI 暴露 StateFlow（UI 不直接 poll）。

class SessionRepository(api: ApiClient, store: SettingsStore) {
  val sessions: StateFlow<List<SessionRow>>
  val connection: StateFlow<ConnState>
  val pending: StateFlow<List<PendingRequest>>
  val modelCatalog: StateFlow<List<ModelOption>>
  suspend fun refresh()
  suspend fun createSession(): String?
  suspend fun rename(id: String, title: String): Boolean
  suspend fun archive(id: String): Boolean          // 走 workspace/archiveSession {request:{sessionId}}
  suspend fun search(q: String): List<SessionRow>
  suspend fun openSession(id: String)                  // 启动 $events + session/follow
  fun chatState(id: String): StateFlow<ChatUiState>
  suspend fun closeSession(id: String)
  suspend fun loadOlder(id: String)
  suspend fun send(id: String, text: String, images: List<String> = emptyList(), steer: Boolean = false)
  suspend fun cancel(id: String)
  // payload 约定：{"kind":"approval","value":"allowed-once"|"rejected"}
  //            或 {"kind":"question","value":{"answers":[{"id","selected":[],"custom?"}]}}
  // 实现取 payload.opt("value") 作为 /api/respond 的 outcome 原样发送
  suspend fun answer(eventId: String, payload: JSONObject): Boolean
  suspend fun goalPause(sessionId: String): Boolean   // goals/pause {agentId, ref:{id,revision}}
  suspend fun goalResume(sessionId: String): Boolean
  suspend fun goalComplete(sessionId: String): Boolean
  suspend fun setModel(id: String, selection: ModelSelection)
  suspend fun fork(id: String, atSeq: Long? = null): String?
}

class FilesClient(api: ApiClient) {
  suspend fun list(path: String?): FileListing
  suspend fun downloadTo(context: Context, path: String): Boolean      // MediaStore Downloads
  suspend fun upload(context: Context, dir: String, uri: android.net.Uri): Boolean
}

class AdminClient(api: ApiClient) {
  suspend fun changePassword(current: String, next: String): Boolean
  suspend fun devices(): List<DeviceInfo>
  suspend fun revokeDevice(id: String): Boolean
}

class SettingsStore(context: Context) {
  var servers: List<ServerProfile>
  var currentServerId: String?
  var deviceName: String
  fun token(serverId: String): String?
  fun saveToken(serverId: String, token: String)
  var themeMode: String        // "system" | "light" | "dark"
  var notifyApprovals: Boolean
  var bgIntervalMin: Int       // 0.5|1|5|15
  var passwordChanged: Boolean
}

class AppContainer(context: Context) { val store; val api; val repo; val files; val admin }
```

### 5.4 导航 / 主题 / 屏幕签名（core 定义，ui 使用）

```kotlin
// ui/theme/DshTheme.kt (core)
@Composable fun DshTheme(themeMode: String = "system", content: @Composable () -> Unit)
// 颜色/字体一律走 MaterialTheme.colorScheme / MaterialTheme.typography；品牌蓝 #4D6BFE 已在 scheme 中。

// ui/App.kt (core)
sealed interface Route {
  data object Login : Route
  data object Sessions : Route
  data class Chat(val sessionId: String) : Route
  data object Files : Route
  data object Settings : Route
}
class AppNavState { var route: Route; fun go(route: Route); fun back() }
@Composable fun DshApp(container: AppContainer)     // 内部创建 AppNavState 并按 route 渲染下面的屏幕

// 以下签名由 ui 提供，core 的 App.kt 直接 import：
@Composable fun ChatScreen(sessionId: String, container: AppContainer, nav: AppNavState)
@Composable fun FilesScreen(container: AppContainer)
@Composable fun SettingsScreen(container: AppContainer, nav: AppNavState)
```

**约束**：ui 只写自己的目录；不得修改 `ui/theme`、`ui/App.kt`、`core/**`、`MainActivity.kt`、Gradle 文件。需要 core 新增 API 时，在任务板留言由 Lead 协调，不得直接改 core。

### 5.5 测试

- core：`app/src/test/java/.../JsonTest.kt`、`RepositoryTest.kt`（JVM，`testImplementation 'org.json:json:20240303'`），覆盖 JSON 解析、事件归一化、去重/排序。
- ui：`app/android/app/src/test/java/com/smallora/dshmobile/ui/md/MarkdownTest.kt`（纯 JVM，验证转义与代码块；允许写在 src/test 下，视为 ui 的测试文件）；Compose UI 走真机验收。

## 6. Android 平台行为

- 通知：通道 `dsh_attention`（IMPORTANCE_HIGH + 震动，审批/提问）与 `dsh_task`（IMPORTANCE_DEFAULT，任务结束）；点击跳转到对应会话。
- 后台：`BackgroundService`（`foregroundServiceType="dataSync"`，通知低打扰）每 `intervalMin` 轮询 `/api/pending`；401 连续 3 次停服并通知重新登录。
- 下载：OkHttp 流式写入 `MediaStore.Downloads`（`Download/dsh-mobile/`），显示系统通知进度；上传：`ACTION_OPEN_DOCUMENT` 选文件后流式 POST。
- 权限：`INTERNET`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`ACCESS_NETWORK_STATE`；不申请相机/存储全盘。
- 网络安全：`usesCleartextTraffic=true`（局域网 http 网关）；公网入口为用户的 HTTPS 反向代理。
- 返回键：Compose `BackHandler` 优先关闭弹层/返回上一级，根页退出。
- 主题：跟随系统 / 浅色 / 深色；状态栏与导航栏配色随主题。

## 7. 配置与偏好键

- 服务端 `~/.dsh-mobile/config.json`：`{version:1, password:{algo:'scrypt',salt,hash}, mustChangePassword, devices:[{id,name,platform,tokenHash,createdAt,lastSeenAt}], roots:[], port:{...}, log:{...}}`
- App `SharedPreferences`（`dsh_mobile`）：`servers`（JSON [{id,name,base}]）、`token.<serverId>`、`currentServer`、`settings`（theme/notify/bgInterval）、`draft.<sessionId>`、`deviceName`。

## 8. 错误码（网关自有）

`invalid-password`、`rate-limited`、`locked`、`unauthorized`、`forbidden-path`、`method-not-allowed`、`upstream-unavailable`、`stream-not-found`、`body-too-large`、`conflict`、`password-change-required`。
