# 排障（dsh-mobile）

先确认版本：插件面板底部/`/health` 显示网关版本；App「设置 → 关于」显示 App 版本。文中的路径以 Linux 为例。

## 1. 连不上 / 登录失败

| 现象 | 排查 |
| --- | --- |
| 登录 401 | 口令错误；初始口令在插件面板「初始口令」或 `~/.dsh-mobile/initial-password.txt`。改密后初始口令失效 |
| 登录 429 / 锁定 | 触发限速：等待 1 分钟或 15 分钟；或重启网关（`kill <gateway pid>`，插件会自动拉起）后重试 |
| 401 但刚登录过 | 设备被撤销 / token 过期（30 天）：重新登录 |
| 局域网连不上 | 手机与主机同一 Wi-Fi；确认 `ss -ltn \| grep 8790` 在监听；主机防火墙放行 8790 |
| 外网连不上 | Cloudflare Public Hostname 指向 `http://<内网IP>:8790`；App 地址用 `https://域名`；确认 cloudflared 在线 |
| 面板打不开 | DSH 侧边栏「Remote」；浏览器 Ctrl+F5 强刷；`/mobile` 依赖 DSH 登录态 |

## 2. 对话页「刷新失败 / 暂无会话」

- **v0.1.3 已修复**：旧版本会因 `NetworkOnMainThreadException` 失败；先升级 App。
- 当前版本错误条会显示具体原因 + 「重试」。常见：
  - `UnauthorizedException`：登录过期 → 重新登录。
  - `ApiException: 上游服务不可用`：DSH 未运行 → `systemctl --user status dsh-web`。
  - `NetworkOnMainThreadException`：App 旧版本 → 升级。
- 会话看不到别的目录：对话页顶部「全部工作区 ▾」切换工作区（按会话 cwd 聚合）。

## 3. 图片不显示

| 现象 | 原因 / 处理 |
| --- | --- |
| 占位「图片无法加载」 | 占位会带具体错误：`NetworkOnMainThreadException` → App 低于 v0.1.9，升级；`HTTP 404` → 附件不在该会话或旧数据迁移格式；`无数据` → 上游返回空 |
| 助手回复里 `![...](...)` 显示成原文 | 旧版不支持 Markdown 图片，升级到 v0.1.9+ |
| 工作区图片不显示 | 相对路径按会话 cwd 解析；确认文件位于网关允许根（家目录/DSH 工作区） |
| 图片很大/裁切 | 缩略图按 1600px 采样、圆角裁切；点击可全屏预览 |

## 4. 消息与队列

- 运行中发送的消息会进入**队列**：输入框上方「排队 N 条」（默认收起）；展开后可**插队**（立刻执行）或**删除**。
- 发送超时/断线：**不会自动重试**（可能已送达）。先刷新会话确认，再决定是否重发。
- 斜杠指令：输入 `/` 弹出菜单；命令不会发给模型。未知命令按普通文本发送。
- 权限：聊天「＋ → 权限」切换（`read-only` / `workspace-write` / `danger-full-access` / `auto`），执行 `/permission <值>`。

## 5. 通知不弹

1. App 内「设置 → 通知」开启审批/提问通知；系统设置允许通知、关闭省电限制。
2. 后台提醒需开启「后台轮询」（30s/1m/5m/15m）；Android 13+ 需授予通知权限。
3. Doze/厂商省电仍可能延迟；前台打开 App 时提醒最及时。
4. 通知点击进入对应会话；若服务器被删除或 token 变更会提示重新连接。

## 6. 网关/插件

```bash
# 网关是否健康（无需鉴权；401/000 判读见下）
curl -s http://127.0.0.1:8790/health
# 期望 {"ok":true,...,"upstream":{"ok":true}}

# 网关进程
pgrep -af 'dsh-mobile-plugin/gateway/gateway.mjs'

# 插件面板日志（也可在 DSH 面板看）
# 配置目录
ls -la ~/.dsh-mobile/
```

- `/health` 返回 `000`：网关未启动 → 重启 DSH Web，或看插件面板 `lastError`。
- `upstream.ok=false` 且 RPC 可用：v0.1.2 已修（WS hub 连接即 true）；确认版本。
- 端口冲突：`DSH_MOBILE_GATEWAY_PORT=8791` 后重启 DSH。
- 需要完全重置：停止 DSH → 删除 `~/.dsh-mobile` → 重启 DSH（需重新配对设备）。

## 7. APK 安装

| 现象 | 处理 |
| --- | --- |
| 无法覆盖安装 | 签名不一致（旧 v0.1.0 包）：卸载旧版再装 |
| 解析包错误 | 确认 arm64-v8a 与 Android 12+ |
| 安装被 Play Protect 拦截 | 侧载应用，需在系统安全设置允许 |

## 8. 收集诊断

- 插件面板「网关日志」尾部（已脱敏，不含 token/口令）。
- App 内错误条文本 / 图片占位错误文本。
- `/health` 输出（不含敏感信息）。
- 提交 Issue 时不要附带 token、口令、Cookie 或完整会话内容。
