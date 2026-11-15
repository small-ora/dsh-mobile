# CHANGELOG

本项目按 `v0.1.x` 迭代；每个 GitHub Release 附带 arm64 APK 与插件 tgz。

## v0.1.12（2026-11-15）

**修复：后台进程 / 子 Agent 消息被误展示为「排队」消息**
- 根因：DSH 的 `inbox.next-turn/next-step` 除人类排队消息外，还会注入后台任务完成通知（`source.kind="tool-jobs"`）、子 Agent 结算/互通（`subagent-settled` / `agent-message`）、团队消息（`team-message`）、goal 等续跑消息；App 解析排队消息时未检查 `source`，把这些内部消息也当作「排队 N 条」展示。
- 修复：排队消息只保留 `source.kind == "user"`；缺 `source` 的历史数据兼容保留。新增 `QueuedMessageSourceTest` 覆盖人类 / 后台任务 / 子 Agent / 团队 / goal 混合 inbox。
- 说明：这些注入消息本来也不会出现在 App 聊天正文（`ChatReducer` 同样按 `source.kind` 过滤用户气泡），本次只修「排队」区域的漏网。

**修复：已完成的目标卡片长期占位、遮挡后续内容**
- 根因：目标卡片在聊天页底部常驻，`goal.phase == "complete"` 后仍一直渲染，压缩消息/输入区空间。
- 修复：目标完成（`complete`/`completed`）时**自动隐藏**；卡片右上角新增「✕」可**手动关闭**任意阶段的目标（按 `goal.id` 记住，新目标会重新展示）。
- 新增 `GoalCardVisibilityTest`（完成自动隐藏、手动关闭只隐藏该目标、新目标重新展示）。

- 插件/网关功能无变化，版本号随 Release 同步为 `0.1.12`（Release 资产含插件 tgz）。

## v0.1.11（2026-11-15）

**修复：搜索在当前主机不可用时的体验**
- 主机 DSH 未挂载 `session-query` 时 `session/search` 被禁用；App 不再弹英文上游原文，改为中文提示「当前主机未启用会话搜索（需 DSH 挂载 session-query 组件）」，并避免每次输入重复请求。

**新增：Tool 结果中的图片渲染**
- `tool/result` 的 image 内容块（内联 `data` / `attachment`）解析为 `ChatItem.Tool.images`，在工具结果区按统一图片管线渲染（加载/缓存/全屏预览）；结果文本只取 text 块，不再显示 `[图片]` 占位。

**新增：Markdown 表格内的图片解析**
- 表格单元格内 `![alt](src)` 会被抽取并渲染在该表格之后；单元格保留 alt（空 alt → `[图片]`），不再原样显示图片语法。

**工程化（CI/质量）**
- CI 补网关门禁：`npm run check && npm test`（此前 CI 只跑 Android，51 个网关测试未在 CI 执行）。
- GitHub Actions 升级：`checkout@v7`、`setup-java@v6`、`cache@v6`、`upload-artifact@v7`，并新增 `setup-node@v7`（修掉 Node20 弃用/`setup-java@v4` 停止维护告警）。
- versionCode 统一：tag 构建与 `scripts/build-apk.sh` 统一为 `major*10000+minor*100+patch`（0.1.10→110、0.1.11→111），修掉本地 `git describe` 带后缀导致的计算错误。
- 启用 Android lint 门禁（`abortOnError/checkReleaseBuilds=true` + CI `:app:lintRelease`），并修复 lint 报出的 `InputStream.readNBytes` API 33 兼容问题（minSdk 31）。

**路线图**
- 明确不再做：iOS/鸿蒙、云中转 relay/E2EE（用户决策 2026-11-15），已从 SPEC/STATUS backlog 移除。

- 插件/网关功能无变化，版本号随 Release 同步为 `0.1.11`（Release 资产含插件 tgz）。

## v0.1.10（2026-11-15）

**修复：对话列表展示子 Agent 会话**
- 根因：DSH `session/list` 会把子 Agent 的 child session（`origin: "subagent"` + `parentSessionId`）与人类根会话一起返回，App 未过滤，导致「对话」列表、工作区计数、搜索结果出现子 Agent 会话（本机实测 85 条中 40 条为子 Agent）。
- 修复：App core 按 `origin = "subagent"` 判定并隐藏（`SessionRow.isSubagent`）；列表/工作区/搜索三个消费点都过滤。**不按 `parentSessionId` 过滤**：`session/fork` 分支会话同样带父会话 id 但无 `origin`，属于人类对话，继续展示。
- 证据：新增 `SubagentSessionFilterTest`（6 用例，含 fork 不被误伤回归）；修复前在 BASE `03c9de3` 的临时 worktree 上用同一红测试复现失败、修复后通过；Android JVM 单测 120/120；live DSH 列表 85 → 45 条；真机全量滚动核对 46/46 根会话、0 子 Agent 会话。

**修复：「对话」页顶部多出一条状态栏高度的空白**
- 根因：外层 `Scaffold`（`ui/App.kt`）已为整个 App 消费系统栏安全区，内层 `SessionsScreen` 的 `Scaffold` 又按默认 `contentWindowInsets` 再消费一次 → 顶部叠加两个状态栏高度（本机状态栏 140px）。
- 修复：内层 Scaffold 设 `contentWindowInsets = WindowInsets(0, 0, 0, 0)`，系统栏安全区只由外层统一处理（底部导航栏内边距同步修正）。
- 证据：真机顶栏分隔线 y=449 → 309（状态栏 140 + 52dp 顶栏 169 = 309），与聊天页（44dp 顶栏）一致；其它页面无变化；Android 单测/网关门禁全绿。

- 插件/网关功能无变化，版本号随 Release 同步为 `0.1.10`（Release 资产含插件 tgz）。

## v0.1.9（2026-11-15）

**图片显示修复**
- App 附件图片 / Markdown 图片无法显示：根因是 `ApiClient.fetchAttachment` / `fetchFile` 的 OkHttp 响应体读取在主线程执行（`NetworkOnMainThreadException`）；已移入 `Dispatchers.IO`。
- 支持 Markdown 图片 `![alt](src)`：工作区相对/绝对路径（经网关文件接口）、`http(s)` 外链、`data:` 内联；代码块内不解析；图片按段落渲染，点击全屏预览。
- 图片占位现在显示具体错误原因，便于排查。
- 附件图标/图片链路：DSH `attachment` 引用 → `GET /api/attachment` → 1600px 采样 + 20 张 LRU。
- 插件版本同步 0.1.9；网关 51 测试、Android 114 测试全绿。

## v0.1.8（2026-11-15）

**官方图标 + 图片附件显示**
- 应用图标改为 DSH 官方鲸鱼 logo + 下方「Harness」字样（白底圆角）；通知栏小图标同步为鲸鱼剪影。
- 新增图片附件读取链路：
  - 插件 `GET/HEAD /mobile/api/session-file?sessionId=&attachmentId=<64hex>`（in-process `sessionController.inspect` + `attachments.readImage/readFileStream`）。
  - 网关 `GET/HEAD /api/attachment`（Bearer → 上游 Cookie 代理，流式透传，不转发客户端凭据）。
  - App 解析 `{type:'image', attachment:{attachmentId:'sha256:…'}}` 并渲染（1600px 采样、20 张 LRU、失败占位、全屏预览）。
- 真实 DSH 验证：附件字节 sha256 与 `attachmentId` 完全一致（插件与网关两侧）。

## v0.1.7 及更早（中间版本未单独发布 Release）

- v0.1.7：通知图标与图标资源准备（未单独发版）。
- v0.1.6：模型/权限从顶栏移入「＋」功能菜单（顶栏恢复完整标题）；排队消息行内「插队/删除」；图标去信号弧、鲸鱼居中放大。
- v0.1.5：排队消息展示；权限切换 chip 与 `/permission` 指令；斜杠指令菜单（`/compact` `/export` `/feedback` `/goal` `/permission` `/plan`）；网关白名单扩展；真实 DSH 协议核对。
- v0.1.4：聊天中间过程合并为「过程 · N 项」默认收起；修复 reasoning 内容混入正文。
- v0.1.3：修复对话列表/聊天加载失败（`NetworkOnMainThreadException`，OkHttp 响应体读取移入 IO）。
- v0.1.2：插件面板新增设备管理（在线状态/撤销）；`/health.upstream.ok` 修复（WS hub 已连接即 ok）；对话页下拉刷新、工作区筛选；App/插件移除「远程访问」说明（只保留文档）。
- v0.1.1：首个公开版本（替换因签名密钥入库而撤回的 v0.1.0）：DSH 插件 + 零依赖网关 + 原生 Kotlin/Compose App；口令登录、设备 token、HTTP 长轮询事件通道（兼容不透传 WebSocket 的 Cloudflare 通道）、文件传输、通知与后台轮询；90+ 项测试与独立安全审查问题修复。
