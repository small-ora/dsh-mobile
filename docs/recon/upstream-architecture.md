# DSH Remote 上游架构勘察（可据以重实现）

- 勘察对象：`/tmp/dsh-Remote-upstream`（浅克隆，`305afaf release: v0.8.8`）
- 只读勘察；行号均指该仓库内路径。
- 一句话：**网关（零依赖 Node，8425 行）**把浏览器/App 的 Bearer 认证、事件缓冲、文件 IO、DSH 生命周期全部收口，并把 `/api/*` 同时适配 DSH 的 **legacy 直接 RPC** 与 **0.2.x generated RPC** 两套协议；**App** 是 Capacitor WebView 壳 + 原生后台轮询/通知桥。

## 1. 网关 HTTP 面

### 1.1 进程与入口
- 单文件 `gateway.js`，`node gateway.js [--supervise]`；默认 `0.0.0.0:8787`，上游 `DSH_UPSTREAM=http://127.0.0.1:3080`（`gateway.js:1-29,1566-1599`）。
- 插件形态会把这套东西作为 bundle 装入 DSH，并由插件进程自启停（`packages/plugin/index.mjs`、`packages/plugin/gateway.cjs`）。
- 路由总入口是一串 `if (url.pathname …)`（`gateway.js:7954-7980`），不是框架路由。

### 1.2 鉴权模型
令牌来源优先级：`DSH_REMOTE_TOKEN` → `TOKEN` → `TOKEN_FILE`（默认 `~/.dsh-remote/token`，缺失则生成 24B base64url 并 `0600` 落盘）（`gateway.js:1904-1924`）。

```js
// gateway.js:2212-2246
function tokenOf(req, url) {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.authorization || '')
  if (m) return m[1]
  return url.searchParams.get('token')        // ← query token 在所有 authorized() 路由都有效
}
function authorized(req, url, options = {}) {
  const presented = tokenOf(req, url)
  if (!deviceKeyState.enabled && safeTokenEqual(presented, TOKEN)) { ... return true }
  if (deviceKeyState.enabled) { const record = findDeviceKeyByToken(presented); ... }
  if (options.consumeTicket) { /* ticket 一次性/多次 */ }
}
```

- 三种身份：`shared`（主令牌）、`device`（独立设备密钥，`device-keys.json`，最多 100 个，`gateway.js:2092-2152`）、`admin`（仅主令牌，`adminAuthorized` `gateway.js:2247-2252`）。
- 判定分层：
  - `authorized`：主令牌或设备令牌 → `/api/*`、`/fs/*`、`/api/events*`。
  - `controlAuthorized`：admin 或 device → `/api/operations`、部分 `/remote/api/*`（`gateway.js:2253-2256,7599-7618`）。
  - `adminAuthorized`：仅主令牌 → `/admin/api/*`、`/relay/*` 管理。
- **WS ticket**：`POST|GET /api/ws-ticket` 用 Bearer 换票，TTL 90s、`uses:4`；WS 握手时 `authorized(..., {consumeTicket:true})` 消费，票在 URL `?ticket=`（`gateway.js:1942-1943,2257-2266,4117-4141,8266`）。

```json
{"ok":true,"ticket":"<24B base64url>","expiresAt":1760000000000}
```

- 未用 ticket 时 WS 也接受 `?token=`；App 优先 ticket，404/405/501 才回退旧 token 握手（`public/app.js:522-556,1367-1381`）。
- **CORS**（`gateway.js:2614-2642`）：无 Origin 直接放行；允许 `capacitor://localhost`、`ionic://localhost`、`localhost` 任意协议、同 host、`DSH_REMOTE_CORS_ORIGINS`（可 `*`）。白名单含 `authorization, content-type, x-dsh-remote-client, x-dsh-remote-client-id, x-dsh-remote-inbox-key`，暴露 `etag,last-modified`，方法仅 `GET,POST,OPTIONS`，max-age 600。
- 设备识别头：`x-dsh-remote-client: app|web|admin` 与 `x-dsh-remote-client-id`（`gateway.js:2179-2182,2307-2316`）。

### 1.3 配对 QR 格式
生成端：`public/admin.js:647-666`、`public/setup.js:135-143`。

```
dshremote://pair?token=<token>&server=http://192.168.1.10:8787&server=http://100.64.0.2:8787
```

- `server` 可重复，按序写入多服务器；可带 `relaySetup=<32hex>` 走公网中转配对（`public/app.js:7497-7546`）。
- 设备密钥模式下 QR 里放的是**新建的 device token**，不是主令牌（`public/admin.js:652-661`）。
- Android 侧用 intent-filter 接 `dshremote://pair`（`AndroidManifest.xml:27-32`），Web 侧 `appUrlOpen`/`getLaunchUrl` 接住（`public/app.js:7804-7815`）。已有配置时 `confirmExisting` 会弹确认，防恶意深链静默换服（`public/app.js:7506-7511`）。

### 1.4 路由表（对 App 重要的）

| 路由 | 方法 | 入参 | 出参 / 要点 | 源码 |
|---|---|---|---|---|
| `/health` | GET | `?probe=live` 跳过上游等 100ms；`?probe=fresh` 强制刷新 | **无需鉴权**；`ok,status,version,protocol,capabilities,dshControl,pid,upstream,upstreamOk,upstreamReachable,upstreamStatus,upstreamCheckedAt,events{mux,host{connected,lastEventAt,eventLagMs,clients,reconnects}},readiness,liveness` | `gateway.js:7889-7950` |
| `/api/ws-ticket` | GET/POST | Bearer | `{ok,ticket,expiresAt}` | `4117` |
| `/api/events.poll` | GET | `kind=mux\|host&since=<seq>&wait=<ms<=25000>&epoch=<id>` | 见 §2.4 | `4142` |
| `/api/<method>` | POST | `{type:'client-request',rpcId,method,payload}` | `{rpcId,result:{ok,value\|error}}`；非生成 RPC 的 GET 透传 | `7599-7700` |
| `/api/respond` | POST | `{type:'client-response',rpcId,result:{ok:true,value}}` | `{accepted:boolean}`；对 modern 上游会转 `$events/result` | `7429-7475` |
| `/api/operations` | GET/POST | `?id=<16-80 字符>`；POST `{method,payload}` | 持久化写回执，202/200/409/404/503，见 §3.5 | `7303-7400` |
| `/fs/list` | GET | `?path=` | `{path,entries[{name,path,type,size,mtimeMs}],roots,platform,separator}` | `6056` |
| `/fs/file` | GET/HEAD | `?path=`，支持 `Range`/`If-None-Match`/`If-Range` | 流式文件，`etag,last-modified,accept-ranges`，206/304/416 | `6116` |
| `/fs/preview` | GET | `?path=` | 文本预览，≤1MB，扩展名白名单，415 | `6176` |
| `/fs/mkdir` | POST | `?path=<父目录>&name=` | 201/409/403 | `6569` |
| `/fs/upload-probe` | GET | `?path&name&session&size` | `{ok,name,partialSize,partExists,targetExists,targetSize,uploadLength,expiresAt}` | `6521` |
| `/fs/upload` | POST | `?path&name[&session&offset&size&finish&overwrite&sha256]`；raw/multipart | 201 `{ok,name,path,size,resumed,session,sha256}`，断点续传 200 `{partial:true,...}` | `6826-6860,6600-6772` |
| `/fs/upload-control` | POST | `?path&name&session&action=cancel` | 取消在途写流并删分片 | `6774-6800` |
| `/remote/api/command` | GET | `?sessionId=` | `{ok,commands:[{name,description,hint}]}` | `packages/plugin/index.mjs:1600-1640` |
| `/remote/api/command` | POST | `{sessionId,line,async?}` | 202 `{ok,executed,accepted,operation,debug}`；未知 `/xxx` 返回 `executed:false` 交回普通文本；`/export` 可同步 200 | `...index.mjs:1650-1710` |
| `/remote/api/command-status` | GET | `?sessionId=` | `{ok,operation}`，跨重连恢复进度 | `...index.mjs:1578-1599` |
| `/remote/api/session-file` | GET/HEAD | `?sessionId&attachmentId=<64hex>&token` | 附件流，`x-content-type-options:nosniff`，404/502 | `packages/plugin/session-file.mjs:2-40` |
| `/remote/api/host-description` | GET | — | `{ok,metadataOnly:true,version,cwd,home,attachedSessions}` | `...index.mjs:1258-1263` |
| `/remote/api/doctor/target` | GET | 无 query | 只读诊断目标 | `...index.mjs:1251-1257` |
| `/remote/api/skills/list\|read` | GET | `?sessionId` / `?id=<64hex>` | `{ok,schema:1,available,items[]}` / `{content}` | `...index.mjs:1264-1282`, `skills.mjs:28-55` |
| `/remote/api/task-board`、`/api/teams`、`/api/insights/cost`、`/api/insights/peak-reminders` | GET | 各自 query | 只读 insight | `...index.mjs:1286-1330` |
| `/remote/api/approval-summary` | POST | JSON ≤4KB | AI 审批摘要 | `...index.mjs:1324-1332` |
| `/remote/api/plugins/*` | GET/POST | 插件中心 | 202/200 | `...index.mjs:1335-1345` |
| `/admin/api/*` | GET/POST | 主令牌 | state/device-keys/token/rotate/relay/dsh/note/kick… | `gateway.js:5500-5830` |
| `/api/lan-pair` | POST | LAN-only JSON ≤2KB | ECDH 配对，返回 `{iv,ciphertext,tag}` | `gateway.js:2159-2172,2013-2063` |
| `/workbench` | GET/POST/DELETE | bind/unbind | 工作台绑定 | `gateway.js:7013-7100` |
| `/handoff`、`/diagnostics`、`/feedback`、`/crash-logs`、`/stats/*`、`/relay*`、`/api/file-inbox`、`/fs/inbox-file` | 各自 | — | 非 App 核心 | `7961-7979` |

静态资源（`gateway.js:5401-5470`）：`/` → `index.html`，`/admin` → `admin.html`，`/dsh-remote.apk` 从 `apk/` 取且强制 `application/vnd.android.package-archive`；`.html/.js/.css`/`build.json` 以内容 sha256 生成弱 ETag 且 `no-cache`；`/update.json` 无 `local` 参数时把 `-rc.N` 去掉（兼容旧 App），`cache-control:no-store`；其余静态 5 分钟缓存 + 可选 gzip。静态资源**不需鉴权**。

## 2. 实时通道

### 2.1 拓扑
DSH 的 `events.mux` / `events.host` 是**只下行**流：客户端在 WS 上不发业务消息，只被动收帧。legacy 上游为每个通道各维持**一条** WS 采集器（`startEventCollector`），modern 上游只开一条 `/api/remote.mux` 再拆成两路，把帧归一后广播给所有手机；手机断开重连时从 replay/buffer 重放（`gateway.js:4208-4315,8158-8258`）。

- 上游通道：`ws(s)://UPSTREAM/api/events.mux?client=web`、`/api/events.host`（`gateway.js:4210-4215`）。
- 新 DSH 0.2.x 只暴露 `/api/remote.mux`（`{type:'open',streamId:'events'|'control'|'workspaces'}`），网关把 modern 事件**翻译回 legacy 帧**再发给客户端（`gateway.js:4637-4710,4565-4636`）。这是网关最大的复杂度来源之一。
- 客户端升级：`GET /api/events.mux?ticket=…&client=app&clientId=…`（`gateway.js:8261-8276`）。网关自己回 RFC6455 Ping（30s）、校验 Pong（90s）、空闲 180s 断开，慢客户端写缓冲 >2MiB 直接踢（`gateway.js:1586-1590,8070-8157`）。

### 2.2 网关 → 客户端帧（真实形状）
WS 直接发上游原始 JSON（无外层 seq）：

```json
{"rpcId":"m1","payload":{"type":"approval/requested","sessionId":"session-1","approvalId":"approval-1","toolName":"shell","reason":"执行测试命令"}}
{"rpcId":"q1","payload":{"type":"question/requested","sessionId":"session-1","questions":[{"id":"choice","header":"测试问题","question":"是否继续？","options":[{"label":"继续","description":"继续测试"}],"multiSelect":false}]}}
{"rpcId":"m3","payload":{"type":"session/projection","sessionId":"s1","key":"title","value":"poll test","seq":2}}
{"rpcId":"h1","payload":{"type":"host/session-status","sessionId":"s1","running":true}}
{"rpcId":"…","payload":{"type":"session/event","sessionId":"s1","event":{"type":"assistant/message","seq":12,"data":{"role":"assistant","content":[{"type":"text","text":"…"}]}},"replay":true}}
```

（取自 `tests/fixtures/realistic-stack.cjs:256-267`、`tests/gateway.test.js:67-70`；`replay` 由 modern snapshot 补发时置 true，`gateway.js:4533`。）

mux 帧 `payload.type` 集合（`public/app.js:1664-1697`）：`session/reasoning`、`session/event`、`session/subscribed`、`approval/requested|resolved`、`question/requested|resolved`、`session/queue`、`session/jobs`、`session/projection`、`stream/error`、`session/stream-error`。
host 帧集合（`public/app.js:1699-1736`）：`host/session-status`、`host/agent-error`、`host/session-added|removed|activity`、`host/workspace-changed|removed|order-changed`、`host/archived-sessions-changed`、`host/remote-event`。

- 只对 `host/session-status`、`host/agent-error` 追加 `remoteEventId`（`<epoch>:host:<seq>`），保证 WS 与 poll 去重（`gateway.js:4061-4068`）。
- 心跳是协议层 Ping/Pong，前端 `onmessage` 只看业务帧（`public/app.js:1418-1421`）。

### 2.3 审批 / 提问的应答
客户端用帧里的 `rpcId` 回 `POST /api/respond`：

```json
{"type":"client-response","rpcId":"approval-rpc-1","result":{"ok":true,"value":{"sessionId":"session-1","approvalId":"approval-1","outcome":"allowed-once"}}}
{"type":"client-response","rpcId":"question-rpc-1","result":{"ok":true,"value":{"sessionId":"session-1","answer":{"answers":[{"id":"choice","selected":["继续"]}]}}}}
```

（`public/app.js:4567,4638`；`outcome` 取 `allowed-once | rejected`，提问每项 `{id,selected[],custom?}`。）
响应 `{"accepted":true|false}`；`accepted:false` 表示主机已不持有该请求，网关会补发 `approval/resolved|question/resolved`（`cancelled`），避免重连后卡片复活（`gateway.js:7429-7478`）。

### 2.4 轮询（降级通道）
`GET /api/events.poll` 返回：

```json
{"ok":true,"kind":"mux","epoch":"<uuid>","since":13,"latestSeq":42,"truncated":false,"reset":false,"waitSupported":true,
 "baseline":[{"rpcId":"…","payload":{"type":"approval/requested","approvalId":"open"}}],
 "events":[{"seq":14,"ts":1760000000000,"event":{"rpcId":"…","payload":{"type":"session/event"}}}]}
```

- `baseline`：仅 `since=0` 或 `reset` 时给，是"当前仍待处理"的审批/提问/queue/jobs/reasoning 的重放集合（`collectorReplay`，`gateway.js:4019-4058,4082-4093`）。
- `reset`：epoch 变化、`latestSeq<since`、或缓冲已截断；客户端清游标全量重收。
- 长轮询：`wait=25000`，无事件则挂起，最多 25s 返回；有事件立即唤醒（`gateway.js:4142-4206,4095-4116`）。
- 缓冲：环形，1000 条 / 8MiB，按整条淘汰（`gateway.js:3525-3531,4061-4080`）。

### 2.5 重连与降级策略（前端）
- 任一通道连续 **3 次**失败 → `enterPollMode()`：关 WS，`pollSeq={0,0}`，4s 定时 tick + 每 30s `tryRestoreWs()`（`public/app.js:1494-1528`）。
- WS 退避：`1.5s * 2^attempt`，上限 60s，±20% 抖动（`public/app.js:1462-1478`）。
- 轮询单次 `wait=25000`，读超时 30s；`PollLoop` 保证同通道不并发（`public/app.js:1530-1560`）。
- 双通道都恢复 OPEN 才停止轮询并回 `ws` 模式（`public/app.js:1413-1422`）。
- `mux` 每次重连先清空本地审批/提问并重新加载历史，规避漏掉 `resolved` 的僵尸卡片（`public/app.js:1432-1440`）。
- 网关侧上游采集器独立退避（1.5s→60s），DSH 重启后自动重连；`/health.events.*` 暴露重连次数与最后错误（`gateway.js:4208-4315`）。

## 3. 上游 DSH 对接

### 3.1 会话 Cookie（`connection.authenticatedUrl`）
DSH 0.1.2-alpha.1 起，Host RPC 与 WS 都要求浏览器会话 Cookie。插件用 host 自己的 `connection.authenticatedUrl(upstream)` 兑换 Cookie：

```js
// packages/plugin/index.mjs:132-155（节选）
const loginUrl = dshConnection.authenticatedUrl(upstream)
const response = await fetch(loginUrl, { redirect: 'manual', signal: AbortSignal.timeout(2500) })
const setCookie = response.headers.get('set-cookie') || ''
const cookie = setCookie.split(';', 1)[0].trim()
if (response.status !== 303 || !cookie.includes('=') || cookie.length > 4096 || /[\0\r\n]/.test(cookie)) throw …
writeFileSync(dshUpstreamCookieFile(), cookie + '\n', { mode: 0o600 })
```

- 落盘 `~/.dsh-remote/dsh-upstream.cookie`（`DSH_REMOTE_DSH_COOKIE_FILE` 可改），网关**每次请求动态读取**，所以 DSH 重启后无需重启网关（`gateway.js:1647-1663`）。
- 网关转发时必须剥掉客户端的 `authorization`/`cookie`/`origin`/`referer`，再注入该 cookie（`gateway.js:7544-7553,8296-8303`）。
- 旧版 DSH 没有 `authenticatedUrl`，跳过即可（返回 false）。

### 3.2 RPC 代理
客户端信封：

```json
{"type":"client-request","rpcId":"<uuid>","method":"session.list","payload":{}}
```

上游两种 flavor 由探测决定（`detectUpstreamApiFlavor`，`gateway.js:3626-3671`）：

| flavor | 探测 | 转发 |
|---|---|---|
| `modern` (0.2.x) | `POST /api/session/list` 返回 `{result:{ok:true,value:{items:[]}}}` | `translateModernRpc()` 把 `/api/session.list` 映射成 `/api/session/list`，再包 `{type:'client-request',rpcId,method:endpoint,payload:{args}}`（`gateway.js:3613-3625,3755-3996`） |
| `legacy` | 上述失败，但 `/api/session.list` 返回同形信封 | 原样 `POST /api/<method>` 透传 |

现代映射（节选，`gateway.js:3755-3940`）：`session.list→session/list`、`session.history→session/page`（先取 `session/projections` 得游标）、`session.prompt→session/prompt`（自动补 `requestId`）、`session.models→session/modelCatalog`、`skills.list→skills/list`、`subagent.list→subagents/list`、`settings.*`、`credentials.*`、`goal.*→goals/*`。
网关对外**统一输出 legacy 信封**：

```json
{"rpcId":"…","result":{"ok":true,"value":{…}}}
{"rpcId":"…","result":{"ok":false,"error":{"code":"session/not-found","message":"…","details":{}}}}
```

客户端 `rpc()` 只认 `full.result`（`public/app.js:583-612`）。App 实际用到的 method：`host.describe`、`workspace.list`、`session.list`、`session.history`、`session.prompt`、`session.cancel`、`session.create`、`session.models`、`session.fork`、`session.rename`、`session.attachment`、`session.updateQueue`、`goal.*`、`subagent.*`（`public/app.js:3080,3741,3984,4930`）。

### 3.3 WS 代理
- `/api/events.mux|host` → **网关自建采集器**，不走透明隧道。
- 其他 `/api/*` WS（如插件自定义流）→ 透明 `upgrade` 转发：复制握手头、注入 cookie、双向 `pipe`、双向心跳（`gateway.js:8260-8390`）。
- 上游返回非 101 时，把状态码原样写回客户端（`gateway.js:8243-8250`）。

### 3.4 Slash 命令
`/remote/api/command` 由 DSH **插件**处理，不是网关；网关只是把 `/remote/*` 代理到插件并注入 `authorization: Bearer <网关主令牌>`（`gateway.js:7551-7553`）。插件侧：

- `GET`：先 `sessionController.resolveAgent(sessionId)` 再 `ctx.commands.list(agent)`，返回 `{name,description,hint}`（过滤非 `[a-z][a-z0-9_-]{0,199}`）。
- `POST {sessionId,line}`：`commandHead(line)` 若不在已登记命令里 → `200 {executed:false, reason:'unknown-command'}`，客户端当普通文本发；已登记命令 → `beginCommandOperation()` 异步执行并 `202 {operation}`（`/export` 例外，可同步 200）。
- 进度用 `GET /remote/api/command-status?sessionId=` 拉 `{operation:{active,phase,command,startedAt,endedAt,message}}`，重连可恢复（`...index.mjs:1134-1145,1578-1599`）。

### 3.5 写操作回执 `/api/operations`（可靠性核心）
- 白名单 14 个写方法（`gateway.js:7179-7182`）。
- `POST /api/operations?id=<id>` body `{method,payload}`：先落盘 intent 再 202，客户端断线不会取消/重放；同一 id 重发要求 payload 指纹一致，否则 409。
- `GET /api/operations?id=` 轮询：`{id,method,phase:'running'|'complete'|'rejected'|'unknown',createdAt,completedAt,result,error,diagnostic}`。
- 网关重启把 `running` 改 `unknown`（`gateway.js:7208-7218`）；客户端本地也存 24h 回执（`public/operations.js:13,46-52`），把"可能已生效"显式告知用户，绝不自动重试（`public/app.js:3738-3752`）。

## 4. App 侧

### 4.1 WebUI 结构
`public/index.html` 只有 6 个 `<section class="view">`（`public/index.html:72,104,131,228,288,293`）：

| view | 内容 |
|---|---|
| `view-home` | 会话列表、统计条、工作台、工作区筛选 |
| `view-files` | 文件收发、上传进度、断点续传 |
| `view-session` | 会话详情、输入框、审批/提问 Inbox、子代理、队列/jobs |
| `view-activity` | 活动/概览 |
| `view-plugins` | 插件中心 |
| `view-settings` | 分组：`general/model/tests/servers/notify/theme/about`（`public/app.js:7350`） |

路由是手写 `showView(id)`（`public/app.js:7320-7348`），无 History API；`body.in-session` 控制会话页顶栏。设置页 `showSettingsPage(name)` 只切 `hidden`（`7358-7367`）。

### 4.2 连接流程（实际是 3 条 + 深链，**没有蓝牙**）
1. **手填**：设置→服务器，输入 `http(s)://host:port` + token；`state.servers[]` 支持多服务器、分组、测速选优（`public/app.js:1132-1215,883-945`）。URL 带 `?token=` 时启动即接管并 `history.replaceState` 抹掉（`public/app.js:7818-7832`）。
2. **扫码**：`btn-scan-camera`/`btn-scan-gallery` → 实时 `getUserMedia` + `BarcodeDetector`，不支持则 Worker + `jsQR`；都是**纯本地解码**，不依赖 Google 服务（`public/app.js:7563-7760`）。解出 `dshremote://pair` 后走 `applyPairUrl`。
3. **局域网发现**：`NativeLan.search()` 6 秒 UDP 广播到 `8788`，收 `dsh-remote-host` 应答；`pair(id, code)` 走 ECDH P-256 + HMAC proof，返回加密的 `{token,deviceId}`（`LanDiscovery.java:22-176`，`gateway.js:1946-2063`）。**不是蓝牙**（全仓无 Bluetooth API）。
4. **深链**：系统相机/浏览器扫到 `dshremote://pair` 唤起 App（`public/app.js:7801-7815`）。

`applyPairUrl()` 是唯一收敛点：校验协议/主机、去重、写入 `state.servers`、`state.token`、切组、`syncBgConfig()`（`public/app.js:7492-7546`）。

### 4.3 token 存储
- `localStorage`：`LS.set('token', …)`（`public/app.js:12-16`）；服务器列表 `dshRemoteServersV1` 里**每台服务器各存一份 token**（`public/app.js:802-820`）。
- 无 Keychain/Keystore 加密；Android 侧同一 WebView 的 localStorage 即应用私有目录。`allowBackup="false"` 避免云备份带走（`AndroidManifest.xml:5`）。
- 后台服务另存一份明文 token 于 `SharedPreferences("dsh_remote_bg")`（`MainActivity.java:520-535`）。
- 安全提示（重写时应改进）：query token 在所有路由可用，且会进访问日志/Referer；主令牌与设备令牌权限边界只靠路径白名单。

### 4.4 Capacitor 原生桥
`MainActivity.onCreate` 注册 5 个接口（`MainActivity.java:154-162`）：

| JS 名 | 类 | 用途 |
|---|---|---|
| `NativeUpdate` | `UpdateBridge` | 版本、APK 下载/安装、诊断导出、insets |
| `NativeFile` | 同一个 `UpdateBridge` | `downloadToDownloads(url,name,token)` 走系统 `DownloadManager` |
| `NativeBackground` | `BackgroundBridge` | 后台轮询配置、任务/待处理通知、通知点击跳转 |
| `NativeLan` | `LanDiscovery` | UDP 发现 + ECDH 配对 |
| `NativeE2ee` | `E2eeBridge` | 公网中转的加密 HTTP/WS、设备配对（高级，可裁剪） |

`BackgroundBridge` 的 `@JavascriptInterface`（`MainActivity.java:459-628`）：
`handleTaskEvent(base,token,json,replay)`、`testNotification(kind)`、`handlePendingEvent(base,token,json,enabled)`、`setPendingNotificationsEnabled(enabled)`、`getPendingNavigation()`、`matchesPendingNavigation(base,token,identity)`、`acknowledgePendingNavigation(value)`、`saveBackgroundConfig(json)`、`getBackgroundConfig()`、`savePeakReminderConfig(json)`、`getPeakReminderConfig()`、`startPeakReminder()`、`stopPeakReminder()`。
`saveBackgroundConfig` 入参：`{enabled,intervalMin,base,token,clientId,notifyTaskDone}`；base/token 变化会清空 `seq_*`/`epoch_*` 游标（`MainActivity.java:505-544`）。

### 4.5 后台轮询与通知
`RemotePollService`（前台服务，`dataSync`）：

- `pollOnce()` 并行拉 `mux`、`host` 两路 `GET {base}/api/events.poll?kind=…&since=…&wait=25000&epoch=…`，`Authorization: Bearer`、`X-Dsh-Remote-Client: app`、`X-Dsh-Remote-Client-Id`（`RemotePollService.java:318-395`）。
- 成功 → 250ms 后再拉（长轮询天然节流）；失败 → 退回 `intervalMin`（默认 0.5 分钟）定时（`RemotePollService.java:255-265`）。
- 收到事件：`TaskNotifications.handle()` 发"任务完成"通知（通道 `dsh_remote_task_results`，IMPORTANCE_HIGH）；`PendingNotifications.handleBatch()` 发审批/提问通知（通道 `dsh_remote_attention`，HIGH + 震动）（`TaskNotifications.java:17-27`、`PendingNotifications.java:19-59`）。
- 401 连续 3 次 → 置 `login_expired` 并停服（`RemotePollService.java:300-315`）。
- 网络恢复回调立即重拉；Android 15 dataSync 预算超时 → `onTimeout` 干净停止（`RemotePollService.java:151-160`）。

### 4.6 AndroidManifest / Gradle
权限（`AndroidManifest.xml:59-66`）：`INTERNET`、`ACCESS_NETWORK_STATE`、`REQUEST_INSTALL_PACKAGES`、`CAMERA`、`POST_NOTIFICATIONS`、`VIBRATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`。
`android:usesCleartextTraffic="true"`（局域网 http 网关，`:10`）；`launchMode=singleTask` + `dshremote://pair` 深链（`:19-32`）；两个 `exported=false` 的前台服务（`:34-44`）。

Gradle：
- `minSdk 24 / compileSdk 36 / targetSdk 36`（`android/variables.gradle:2-4`），无 NDK、无 ABI split、无 `abiFilters`（纯 WebView）。
- **固定 debug 签名**给 debug+release 共用，保证可原地覆盖升级（`android/app/build.gradle:35-49`，keystore 在 `android/app/keystore/dsh-remote-debug.keystore`，口令 `android`）。上架前必须换。
- 版本：版本号唯一来源是根 `package.json`（`version 0.8.8`、`androidVersionCode 8056`，`:3-4`）；`versionName` 把 `-` 换成 `.`（`0.8.8-rc.1 → 0.8.8.rc.1`），`versionCode` 直接取 `androidVersionCode`（`scripts/android-version.mjs:5-13`，`android/app/build.gradle:3-7,18-19`）。

## 5. 构建与发布

- `npm run build-app` = `prepare-version.mjs` → `npx cap sync android` → `./gradlew --no-daemon assembleDebug`（`scripts/build-app.mjs:19-21`）。
- 产物 `android/app/build/outputs/apk/debug/app-debug.apk`；`npm run publish` 复制到 `apk/dsh-remote.apk`、算 sha256、写 `public/version.json` + `public/update.json`（`scripts/publish.js:15-55`）。
- `npm run check` 是真正的门禁：`node --check` 全部 JS/CJS/MJS + `node --test tests/*.test.js`（`package.json:34`）。
- `.github/workflows/`：
  - `checks.yml`：Node 22 / `npm ci` / `npm run check`（push main+dev、PR）。
  - `compat.yml`：装最新 `@deepseek-ai/dsh` + `dsh-remote-plugin`，验证 `/remote/version.json` 可访问（每周一 03:00 UTC）。
  - `release-build.yml`：tag `v*` 触发，JDK 21 + Android SDK；先 `sync-plugin` + `cmp public/app.js packages/plugin/public/app.js` 保证插件与独立页同源，再 build APK、`npm run publish`、`pkg` 出 linux/win 单文件、macOS arm64（ad-hoc 签名+冒烟），最后 GitHub Release + `npm publish`（RC 默认 `next` tag）。
  - `macos-files.yml`/`windows-files.yml`：全量检查与 Windows 真实网关文件测试。
  - `macos-preview.yml`、`gitee-release-sync.yml`：手动预览/镜像同步。

## 6. 重写建议：最小集 vs 可裁剪

### 6.1 App 必须实现的最小端点集（< 10 个）
| 能力 | 端点 | 说明 |
|---|---|---|
| 连通性/能力协商 | `GET /health` | 仅用 `ok/version/capabilities` 判断是否新网关；可省 |
| 实时下行 | `WS /api/events.mux`、`/api/events.host` | **核心**，可只用 `?token=`，省掉 ticket |
| RPC | `POST /api/<method>` | 至少 `session.list`、`session.history`、`session.prompt`、`session.cancel`、`host.describe` |
| 人机确认 | `POST /api/respond` | 审批/提问**必须**，否则 agent 会卡住 |
| 后台/降级 | `GET /api/events.poll` | Android 后台通知的**事实上的唯一**可靠通道（WS 在 Doze 下不可靠），建议保留 |
| 文件 | `GET /fs/list`、`GET /fs/file`、`POST /fs/upload` | 只需 raw 上传即可，resumable 可后加 |
| 斜杠命令 | `POST /remote/api/command` | 若产品不承诺 `/compact`、`/export`，可删（普通文本仍可用） |

鉴权只需 `Authorization: Bearer` + 同源/Capacitor CORS；配对只需要 `dshremote://pair?token&server`（或手填），不必实现 UDP ECDH。

### 6.2 可以不复刻
- **公网中转/E2EE**（`relay/*`、`E2eeBridge`、`e2ee-*`，代码量占比很大）：用 Tailscale/WireGuard 替代。
- **DSH 生命周期管理**（systemd/windows/process/desktop、`/admin/api/dsh`、crash log、自启、supervisor 租约）：交给系统服务。
- **管理控制台**（`/admin`、设备密钥、审计、多服务器测速）：单人使用只需一个 token。
- **生态面板**：插件中心、teams、task-board、insights、peak reminders、skills、doctor、announcements、feedback、stats、file-inbox、handoff、workbench 绑定。
- **协议适配器的一半**：如果锁定 DSH ≥0.2.x，可只实现 modern `session/*` 路由，删掉 legacy/all-fallback 分支（`gateway.js` 中约 3k 行）。
- **手写 RFC6455 帧解析/压缩/ETag**：Node 端直接用 `ws`，静态资源交给 `express.static`/`serve-static`。
- **operations 回执**：不是协议必需，但**强烈建议保留**——它是"发送后断线不重复提交"的唯一保障。

### 6.3 重写时的 5 个坑（源码已踩过）
1. 不要把客户端 `authorization` 透给上游；必须剥离并注入 Cookie，否则 DSH 0.1.2+ 全部 401（`gateway.js:7544-7553`）。
2. `events.mux`/`host` 是单向下行，别设计客户端上行帧；重连语义靠"重放 baseline + 清本地 pending"（`public/app.js:1432-1440`）。
3. 审批/提问的 `rpcId` 必须原样回传；`accepted:false` 要当作"已失效"并清卡片。
4. `session.history` 是分页 + `beforeSeq` 向上翻；`session.prompt` 的 `content` 是块数组（`[{type:'text'|'image',…}]`），不是字符串（`public/app.js:3853-3862`）。
5. `session.prompt` 超时/断线**不可自动重试**；必须用 `/api/operations` 或本地"未确认"状态（`public/app.js:3738-3752`）。

---

### 我无法确认的点
- 未运行网关/App/测试；所有结论来自静态阅读，运行时行为（尤其 Doze、Android 15 前台服务预算、DSH 各版本兼容）未实测。
- `session.control` 仅作为 modern 上游 WS 的 `streamId` 出现（`gateway.js:4713`），未见同名 HTTP 端点；对外只是 `session/queue`/`session/jobs`/`session/projection` 帧。
- 未逐一核对 `relay/*`、`E2ee*`、插件中心、文件收件箱的完整语义（与最小重写无关，已归入"可裁剪"）。
- `/health` 无鉴权、`?token=` 可出现在 URL 是有意兼容还是遗漏，无法从代码判断设计意图。
- 未验证 DSH 版本与 `modern/legacy` 探测结果的精确对应关系，只知探测条件与回退策略。
