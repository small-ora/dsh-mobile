# 安全说明（dsh-mobile）

> 这是单人自托管工具的安全模型。默认假设：DSH 主机与手机属于同一信任所有者；公网仅通过你自己的反向代理（如 Cloudflare Tunnel）暴露。

## 1. 信任边界

```
手机 App ──(HTTPS/HTTP+密码)──▶ 反向代理(Cloudflare 等) ──▶ 网关 8790 ──(Cookie)──▶ DSH 3080
```

- 网关是**唯一**对外入口；它持有访问 DSH 的上游 Cookie，但**不把 Cookie 或客户端凭据转发给上游之外**。
- DSH 自己的登录/会话/插件生态不在本项目安全边界内。

## 2. 凭据模型

| 凭据 | 生成 | 存储 | 生命周期 |
| --- | --- | --- | --- |
| 管理口令 | 首次启动随机 10 位 | `config.json`：scrypt(N=16384,r=8,p=1,64B) | 首次登录强制修改；可随时改 |
| 设备 token | 登录时 32B 随机 → base64url(43) | 服务端仅存 SHA-256；App 存 SharedPreferences（应用私有） | 30 天滑动过期；可按设备撤销 |
| WS / 流 ticket | HMAC-SHA256 | 不落盘 | 30 秒，仅用于 WS 查询参数 |
| DSH 上游 Cookie | 插件用进程启动 token 交换 | `~/.dsh-mobile/upstream.cookie`（0600） | 随 DSH 重启刷新；不写日志 |

- 口令/token 比较全部常量时间；口令错误统一提示，不区分原因。
- 改密失败复用登录限速；同设备并发改密返回 409。
- 目录 0700；`config.json`/`initial-password.txt`/`upstream.cookie` 0600；日志不记录凭据。

## 3. 认证与限速

- `POST /api/login`：同 IP 5 次/分钟；连续 10 次失败锁定 15 分钟。
- 登录限速的客户端 IP 解析：
  - 默认只信任**回环直连**的 `X-Forwarded-For` / `CF-Connecting-IP`（同机 cloudflared 场景）。
  - cloudflared 在其它主机时，必须设置 `DSH_MOBILE_TRUST_PROXY_CIDRS=<网段>`；否则按反代地址统一限速（更严格但可用）。
  - 不可信来源直连时一律忽略转发头，防止伪造头绕过限速。
- 会话/业务接口未带有效 token 一律 401；`mustChangePassword=true` 时业务接口 403（`password-change-required`）。
- WS 握手需有效 ticket；设备撤销/登出会以 4401 关闭其已建立连接。

## 4. 授权与越权面

- 每条长轮询流绑定打开它的设备；跨设备 `poll/cancel` 返回 404（不泄露存在性）；内部 `$events` 流不可被客户端访问。
- 文件接口：词法根校验 + `realpath` 复核，拒绝 `..`/符号链接/绝对路径越界；上传 2 GiB 上限。
- 附件接口：只允许会话日志中真实可达的 `attachmentId`；参数严格校验（64 hex）。
- RPC 只放行白名单方法（见 interfaces §4），不转发任意 URL/method；客户端 `Authorization`/`Cookie` 不透传上游。
- 管理接口（`/api/admin/*`）仅接受插件注入的 `DSH_MOBILE_ADMIN_TOKEN`（`/mobile` 面板经 DSH 登录态鉴权）。

## 5. 公网暴露建议

1. 使用反向代理的 HTTPS（App 用 `https://`；无 scheme 时公网域名默认按 HTTPS 解析）。
2. 使用强口令（≥12 位，非字典）。
3. 不需要时停用隧道/删除 Public Hostname。
4. 定期在插件面板「设备管理」撤销不用的设备。
5. 不要把 `/mobile` 管理面板直接暴露公网（它依赖 DSH 登录态，但尽量减少暴露面）。

## 6. 已知取舍 / 局限

- **固定签名密钥**：发布 APK 使用同一密钥以保证覆盖升级；密钥只存放在维护者本地与 GitHub Actions Secrets，不入仓库（v0.1.0 因密钥入库已撤回并由新密钥重发 v0.1.1）。若密钥泄露，应轮换并通知用户重装。
- **App token 未加密存储**：存于应用私有 SharedPreferences（非 Keystore 加密）；依赖 Android 应用沙箱。设备被 root/备份风险由系统侧承担（`allowBackup=false`）。
- **局域网明文 HTTP**：局域网内用 http（`usesCleartextTraffic=true`）；公网务必走 HTTPS 反向代理。
- **多设备共用管理口令**：每台设备登录都需口令；口令泄露等价于可注册新设备（可用设备管理撤销，但无法阻止新登录）。公网场景请用强口令。
- **Cloudflare 凭据**：网关不管理 cloudflared、不保存 Tunnel Token；相关凭据由你自己的通道配置管理。

## 7. 漏洞报告

这是个人自托管项目。发现安全问题请通过 GitHub Issue（不要公开利用细节）或私下联系仓库维护者。请附版本号、复现步骤与影响范围；不要附真实 token/口令。
