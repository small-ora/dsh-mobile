# CHANGELOG

本项目按 `v0.1.x` 迭代；每个 GitHub Release 附带 arm64 APK 与插件 tgz。

## 未发布

**修复：对话列表展示子 Agent 会话**
- 根因：DSH `session/list` 会把子 Agent 的 child session（`origin: "subagent"` + `parentSessionId`）与人类根会话一起返回，App 未过滤，导致「对话」列表、工作区计数、搜索结果出现子 Agent 会话（本机实测 85 条中 40 条为子 Agent）。
- 修复：App core 按 `origin = "subagent"` 判定并隐藏（`SessionRow.isSubagent`）；列表/工作区/搜索三个消费点都过滤。**不按 `parentSessionId` 过滤**：`session/fork` 分支会话同样带父会话 id 但无 `origin`，属于人类对话，继续展示。
- 证据：新增 `SubagentSessionFilterTest`（6 用例，含 fork 不被误伤回归）；修复前在 BASE `03c9de3` 的临时 worktree 上用同一红测试复现失败、修复后通过；Android JVM 单测 120/120；live DSH 列表 85 → 45 条；真机全量滚动核对 46/46 根会话、0 子 Agent 会话。

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
