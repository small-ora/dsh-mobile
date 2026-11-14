# dsh-mobile

DSH（DeepSeek Harness）的移动远程客户端：**DSH 插件 + 零依赖网关 + 原生 Android App（Kotlin / Jetpack Compose）**。

- 界面为 **DeepSeek 官方简洁风**（白底、品牌蓝 `#4d6bfe`、行式列表、浅/深色跟随系统）。
- 手机与 DSH 主机通过 **口令登录**（每设备独立 token、可撤销、登录限速）；DSH 侧支持设备管理。
- 支持 **Cloudflare Tunnel / 任意反向代理**：网关只监听 `0.0.0.0:8790`，把域名转发到这个端口即可；事件通道用 **HTTP 长轮询**，不依赖 WebSocket。
- 与现有 `dsh-remote-plugin` 完全并存：独立插件、独立网关（8790）、独立配置目录 `~/.dsh-mobile`，不改动现有 DSH 配置。

当前版本：**v0.1.9**（App `0.1.9` / 插件 `0.1.9`），见 [CHANGELOG](CHANGELOG.md) 与 [docs/STATUS.md](docs/STATUS.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 组件、端口、目录、数据流、DSH 协议适配 |
| [docs/interfaces.md](docs/interfaces.md) | 网关 HTTP/WS API 契约 + App 模块契约 |
| [docs/SECURITY.md](docs/SECURITY.md) | 信任边界、凭据模型、威胁与缓解、漏洞报告 |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | 常见问题（刷新失败/图片/通知/连接/安装） |
| [docs/RELEASING.md](docs/RELEASING.md) | 版本、CI、签名密钥、发布与回滚 |
| [docs/STATUS.md](docs/STATUS.md) | 当前版本状态、已交付功能、已知限制与路线图 |
| [SPEC.md](SPEC.md) | 需求与验收标准（as-built） |

## 架构

```
Android App (Kotlin/Compose, arm64-v8a · Android 12+)
   │ HTTPS(公网) / HTTP(局域网)，Bearer device token
   ▼
Cloudflare 通道（由你自己配置：域名 → 内网:8790）
   ▼
dsh-mobile-gateway (8790)：口令/token 鉴权、RPC 代理、HTTP 长轮询流中心、文件与附件传输
   ▼ 上游 Cookie（插件自动交换，0600）
DSH web (3080)：现代 RPC + /api/remote.mux
   ▲
dsh-mobile-plugin：/mobile 主机面板（状态/口令/设备管理/日志）+ 自动拉起网关 + 侧边栏入口
```

## 安装（DSH 主机）

需要 DSH ≥ 0.2.1-rc.2、Node ≥ 20（网关零 npm 依赖）。

```bash
# 1) 构建插件包（或从 GitHub Release 下载 dsh-mobile-plugin-0.1.9.tgz）
bash scripts/build-plugin-tgz.sh          # 产出 dist/dsh-mobile-plugin-0.1.9.tgz

# 2) 安装到你的 web profile（会自动追加 bundle）
dsh plugin --profile web add /path/to/dsh-mobile-plugin-0.1.9.tgz

# 3) 完整重启 DSH Web（按你的部署方式，例如）
systemctl --user restart dsh-web
```

重启后打开 DSH → 侧边栏「Remote」→ 主机面板：

- 局域网地址（`http://<内网IP>:8790`）、初始口令
- 网关状态 / 事件通道状态
- **设备管理**：查看在线状态、逐台撤销
- 网关日志尾部

首次启动会生成 10 位初始口令（`~/.dsh-mobile/initial-password.txt`，0600）。App 首次用它登录后会**强制修改口令**；服务端同样拦截未改密设备。

## App

- 从 [GitHub Releases](https://github.com/small-ora/dsh-mobile/releases) 下载 `dsh-mobile-v<version>.apk`（仅 arm64-v8a，Android 12+）。
- 签名固定（见 [docs/RELEASING.md](docs/RELEASING.md)），同签名可直接覆盖升级。
- 主要界面：对话（工作区筛选/下拉刷新/过程折叠/排队消息插队删除）、文件（浏览/上传/下载/预览）、设置（服务器/改口令/设备/通知/外观/关于）。

### 首次连接

1. App → 输入服务器地址 + 初始口令：
   - 局域网：`http://192.168.x.x:8790`
   - 公网：`https://你的域名`（见下）
2. 按提示修改口令。
3. 在「＋ → 功能」里选择模型/权限/发图片；输入 `/` 可用斜杠指令（`/compact` `/export` `/goal` `/permission` `/plan` 等，命令不会发给模型）。

## 外网访问（自建 Cloudflare 通道）

1. Cloudflare 控制台 → 你的 Tunnel → Public Hostname 新增：
   - Hostname：`dsh.example.com`
   - Service：`http://<内网 DSH 主机 IP>:8790`
2. App 登录页填 `https://dsh.example.com` + 口令。
3. 事件通过普通 HTTPS 长轮询传输，**不要求 Cloudflare 透传 WebSocket**。
4. 公网暴露时请使用强口令；`/health` 无需鉴权但只返回最小信息。
5. 若 cloudflared 不在这台主机上：在 DSH 启动环境设置可信代理网段，登录限速才能按真实客户端 IP 生效：

```bash
export DSH_MOBILE_TRUST_PROXY_CIDRS=192.168.1.0/24   # 逗号分隔，可多个；默认只信任回环
```

> 网关不管理 cloudflared、不读取/不存储任何 Cloudflare 凭据。

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `DSH_MOBILE_GATEWAY_PORT` | `8790` | 网关端口 |
| `DSH_MOBILE_GATEWAY_HOST` | `0.0.0.0` | 监听地址 |
| `DSH_MOBILE_CONFIG_DIR` | `~/.dsh-mobile` | 配置目录（0700） |
| `DSH_MOBILE_TRUST_PROXY_CIDRS` | 空 | 可信反代网段（决定是否信任 `X-Forwarded-For`/`CF-Connecting-IP`） |
| `DSH_MOBILE_ADMIN_TOKEN` | 插件注入 | 面板 → 网关管理接口令牌 |

## 开发

```bash
npm run check        # 全部手写 JS 语法门禁
npm test             # 网关 node:test（51 例）
npm run e2e          # 对隔离 DSH 的端到端验收
bash scripts/build-plugin-tgz.sh
bash scripts/build-apk.sh        # 本机需 JDK17 + Android SDK 36
```

隔离测试环境（不碰正在运行的 live DSH）：

```bash
export DSH_HOME=~/.dsh-dev
export DSH_MOBILE_CONFIG_DIR=~/.dsh-dev/.dsh-mobile
export DSH_MOBILE_GATEWAY_HOST=127.0.0.1
dsh --profile web --host 127.0.0.1 --port 3091 --no-open
dsh plugin --profile web add dist/dsh-mobile-plugin-0.1.9.tgz
E2E_PASSWORD=... node scripts/e2e.mjs
```

代码结构：

```
gateway/    零依赖 Node 网关（鉴权、RPC/长轮询、文件/附件、反代信任）
plugin/     DSH bundle 插件（/mobile 面板、Cookie 交换、网关守护、侧边栏入口）
app/android 原生 Android App（core/ + ui/，Kotlin + Compose）
tests/      网关 node:test
scripts/    检查、E2E、构建与 APK 门禁
```

## 安全要点

- 口令 scrypt（N=16384,r=8,p=1）；设备 token 只存 SHA-256；每设备可撤销；登录按 IP 限速（5 次/分钟，10 次失败锁 15 分钟）。
- 文件接口 realpath 校验允许根，拒绝越界；附件接口只允许会话内可达的附件。
- 日志不记录口令/token/Cookie；配置目录 0700、配置与 Cookie 0600。
- 详细威胁模型与已知取舍见 [docs/SECURITY.md](docs/SECURITY.md)。

## License

MIT
