# 项目状态（STATUS）

> 当前权威状态快照。功能细节见 [README](../README.md)、契约见 [interfaces](interfaces.md)、变更见 [CHANGELOG](../CHANGELOG.md)。

## 版本

| 项 | 当前 |
| --- | --- |
| 项目 / App / 插件 | **v0.1.13**（App `0.1.13`，插件 `0.1.13`，网关 `0.1.13`） |
| 最新 Release | https://github.com/small-ora/dsh-mobile/releases/tag/v0.1.13 |
| 发布资产 | `dsh-mobile-v0.1.13.apk`（arm64-v8a，Android 12+）、`dsh-mobile-plugin-0.1.13.tgz`、各自 `.sha256` |

## 组件状态

| 组件 | 状态 | 说明 |
| --- | --- | --- |
| 网关 `gateway/` | ✅ 稳定 | 51 个 node:test；鉴权/限速/RPC/长轮询/文件/附件/反代信任 |
| 插件 `plugin/` | ✅ 稳定 | `/mobile` 面板（状态/口令/设备管理/日志）、Cookie 交换、网关守护 |
| App `app/android/` | ✅ 可用（真机验收） | Kotlin + Compose；174 个 JVM 单测；APK 门禁通过 |
| CI | ✅ | tag `v*` → 单测 + APK 门禁 + Release |
| 文档 | ✅ | README + 架构/接口/安全/排障/发布/状态 |

## 已交付功能

- 连接：口令登录、多服务器、强制改密、设备 token/撤销、登录限速。
- 会话：列表/工作区筛选/下拉刷新/新建（**可选目标工作区**）/重命名/归档（**归档后从列表/搜索/计数中消失**）/搜索（**未启用 session-query 的主机给出中文提示**）/分页历史（**子 Agent 会话不在列表/搜索中展示**）。
- 聊天：流式与思考（默认折叠）、工具过程折叠（**工具结果图片直接渲染**）、审批/提问、目标/待办（**目标完成自动隐藏、可手动关闭**）、停止、排队/插队/删除（**仅人类排队消息；后台任务/子 Agent/团队通知不展示**）、模型与思考档位、权限切换、斜杠指令、Markdown 与代码块（**GFM 表格渲染、工作区路径链接只显示标签**）、**附件图片与 Markdown 图片（含表格内图片，全屏预览）**。
- 文件：浏览/上传（流式）/下载/文本与图片预览。
- 通知：审批/提问系统通知、后台轮询可配、任务结束渠道。
- 主机面板：连接信息、初始口令、网关/事件通道状态、**设备管理**、日志。

## 已知限制

- App token 存于应用私有 SharedPreferences（未用 Keystore 加密）；局域网明文 HTTP（公网走 HTTPS 反代）。
- 多设备共用同一管理口令；请用强口令并定期在面板撤销设备。
- 发布签名固定（密钥不入库）；更换密钥需用户卸载重装。
- APK 非字节可复现，sha256 以 CI 产物为准。

## 快速自检

```bash
npm run check && npm test                 # 网关门禁
curl -s http://127.0.0.1:8790/health       # 网关健康（upstream.ok 应为 true）
bash scripts/build-plugin-tgz.sh          # 插件包
bash scripts/build-apk.sh                 # APK + aapt2/apksigner 门禁
```

## 路线图（M2 backlog）

插件中心、技能/洞察/团队/任务看板、公告与反馈、操作回执、离线历史缓存、文件收件箱、断点续传、局域网发现与扫码配对、多主题。

> 明确不做（用户决策 2026-11-15）：iOS/鸿蒙、云中转 relay/E2EE。

## 维护入口

- 发布与密钥：[RELEASING](RELEASING.md)
- 安全模型：[SECURITY](SECURITY.md)
- 排障：[TROUBLESHOOTING](TROUBLESHOOTING.md)
- 数据流与失败模式：[ARCHITECTURE](ARCHITECTURE.md)
