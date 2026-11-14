# DSH 0.2.1-rc.2 插件开发契约勘察（bundle + Web 侧边栏入口）

> 只读勘察，未修改任何安装文件；仅创建本报告。
> 证据路径别名（下文用别名 + `:行号`）：
> - `$CLI` = `/root/.nvm/versions/node/v25.5.0/lib/node_modules/@deepseek-ai/dsh`（CLI 安装根，版本 0.2.1-rc.2）
> - `$WS` = `$CLI/node_modules/@deepseek-ai`（各 `@deepseek-ai/*` 包）
> - `$PROF` = `/root/.dsh/profiles/web`
> - `$EXAMPLE` = `$PROF/node_modules/dsh-remote-plugin`（已装的同机范例）
> 标注约定：**【实测/源码】**=已从本机文件核实；**【未确认】**=本机无足够证据，需实测。
> 抽查时间 2026-11-14，运行时 Node v25.5.0。

## 1. bundle 插件的包结构

### 1.1 package.json 的 `dsh` 字段

类型定义在 `$WS/dsh-package-manifest/lib/types/types.d.ts`：

| 字段 | 位置 | 含义 / 约束 |
|---|---|---|
| `dsh.bundle.patch` | `types.d.ts:66-68` | **字符串或字符串数组**，每个都是相对包根目录的补丁文件路径，按序应用；非字符串/数组会抛错（`$WS/dsh-app-boot/lib/index.js:517-524`）。这是“bundle 层”的唯一声明。 |
| `dsh.client.platform` | `types.d.ts:78` | 必填字符串；Web 端**只接受精确的 `"web"`**（`$WS/dsh-client-modules/lib/index.js:733`），其它值该包被当作无 client（静默忽略）。 |
| `dsh.client.inject` | `types.d.ts:80` | **包名依赖边，不是 Cordis 服务注入**（注释原文 “Informational package-name dependencies, not Cordis service injection”）。只决定 client bundle 工厂的到达顺序；指向不存在图行的包名会被**静默跳过**（`$WS/dsh-client-modules/lib/client.js:789-791`）。真正的 Cordis 服务注入走 bundle 模块自己 `export const inject`。 |
| `dsh.client.immediately` | `types.d.ts:82` | 布尔；`true` 表示进入“第一阶段预取”批次，缺省进共享 application 批次（`manifest.d.ts:43-46,61`）。 |
| `dsh.client.external` | `types.d.ts:88` | 除基线外的精确模块表请求（可写 `<pkg>/client` 子路径）；只影响模块解析，不做服务注入。 |
| `exports["./client"]` | `$WS/dsh-client-modules/lib/index.js:171-181` | 声明了 `dsh.client` 的包**必须**有该子路径（字符串，或含 string `default` 的对象），否则启动期 FAILED fiber：`client-modules: <pkg> declares dsh.client but exports no "./client" bundle`（同文件 `:737-738`）。 |
| `exports["."]` | — | 服务端半体的入口，由 Loader 按包名 import（`$EXAMPLE/package.json` 用 `"./index.mjs"`）。 |

`version`/`name` 是兼容性检查的身份字段，缺失会在不满足时抛错（`$WS/dsh-app-boot/lib/index.js:311-325`）。

### 1.2 cordis.patch.yml 与 insert 语义

补丁文件是**顶层 YAML 数组**，元素形状：

```yaml
# 范例（$EXAMPLE/cordis.patch.yml）
- insert:
    - id: dsh-remote            # Loader 行 id
      name: 'dsh-remote-plugin' # 包名，按包名解析
```

实现 `applyEntryPatches`（`$WS/dsh-app-boot/lib/index.js:61-115`）：

- 有 `insert` 时：`id` 省略 → 追加到**顶层**；`id` 指向一个 `group` 行 → 追加进该 group 的 `config` 数组。`id` 不存在或目标不是 group → warn 并跳过（`:71-90`）。
- 无 `insert` 时：必须有 `id`，否则 warn+跳过（`:93-96`）；`name` 若给出则作为**断言**（与目标名不一致 → warn+跳过，`:101-104`）；其余键**整体替换**目标行字段，`config` 是**整块替换、非深合并**（`:105-110`，schema 描述 `:3044`）。
- 同一补丁列表里后一个补丁可以命中前一个 insert 插入的行（插入即建索引，`:88-89`）。

### 1.3 profile 如何加载（层序即优先级）

`readProfilePatches`（`$WS/dsh-app-boot/lib/index.js:1127-1137`）组合顺序：

1. `package.json` 的 `dsh.profile.bundles` 顺序，每层读它 `dsh.bundle.patch`（`loadProfileDirectory`，`:1015-1050`）；
2. profile 自己的 `cordis.patch.yml`（`:1130`）；
3. `$DSH_HOME/cordis.patch.yml`（机器级，压过 per-profile，`:1131`）；
4. CLI `--patch` 覆盖层（`:1132`，按 argv 顺序）；
5. 遥测开关补丁（`:1134`）。

包解析是**两锚点**：bundle 包目录先查 dsh 安装，再查 profile 目录（`resolveBundleDir`，`$WS/dsh-app-boot/lib/index.js:995-1000`）；运行时模块解析则让 profile 的 `node_modules` 优先（同文件模块注释 `:498-503`）。profile 根配置 `cordis.yml` 恒被重写为 `[]`，**不要手改**（`$WS/dsh-app-boot/lib/types/profile-boot.d.ts:8-13` 的说明与 `$PROF/cordis.yml:1-5` 一致）。

bundle 不可读、或自身 `engines.dsh` 不满足且未豁免 → **跳过该 bundle**并记录在 `skippedBundles`（启动时打印），不阻断其它层（`:1015-1050`、`:538-541`）。

### 1.4 最小可发布包结构

```
my-gateway-plugin/
  package.json
  cordis.patch.yml     # insert 自身行
  index.mjs            # 服务端: export name/inject/apply
  client.js            # 浏览器: window.__ModuleLoader__.load(...)
```

```json
{
  "name": "my-gateway-plugin",
  "version": "0.1.0",
  "type": "module",
  "main": "./index.mjs",
  "exports": { ".": "./index.mjs", "./client": "./client.js" },
  "engines": { "dsh": "0.2.1-rc.2" },
  "dsh": {
    "bundle": { "patch": "./cordis.patch.yml" },
    "client": { "platform": "web", "inject": [] }
  }
}
```

> 一个 host 行即可同时带出客户端：client-modules 是**按 Loader 行指向的包的 package.json** 增量扫描 `dsh.client` 的（`$WS/dsh-client-modules/lib/index.js:720-750`），不需要在 patch 里再写客户端行。

## 2. 服务端插件 API

### 2.1 插件形状：`export const name / inject / apply`

Cordis 支持的 entry 形状（`$WS/cordis/lib/types/registry.d.ts:49-80`）：函数、类、或**对象 `{ name?, inject?, apply(ctx, config) }`**。`inject` 是**服务名数组**，缺服务时该 fiber 挂起等待（`registry.d.ts:13-17,58`）。`ctx.effect(fn, label)` 注册随 fiber 卸载的清理器（范例 `index.mjs:1184-1205`）。

```js
// index.mjs（最小）
export const name = 'my-gateway'
export const inject = ['webServer']
export function apply(ctx, config) {
  ctx.effect(() => ctx.webServer.register({
    kind: 'prefix', path: '/my-gw',
    handler: (req, res) => { res.writeHead(200, {'content-type':'application/json'}); res.end('{"ok":true}') },
  }), 'my-gateway: /my-gw')
}
```

patched 行里的 `config:` 会作为第二个实参传入（Loader 的行配置 → 插件 config）。

### 2.2 `webServer` 服务（`@deepseek-ai/dsh-host-webserver`）

`Context.webServer: WebServer`（`$WS/dsh-host-webserver/lib/types/index.d.ts:16-20`）。核心成员：

| 成员 | 位置 | 说明 |
|---|---|---|
| `host` | `index.d.ts:154` | **配置里的绑定地址**（具体 IPv4/IPv6 字面量，拒绝 `0.0.0.0`/`::`，`:60-71`）。 |
| `port` | `index.d.ts:152` | 实际监听端口；`config.port=0` 时是 OS 分配值，绑定前为 `undefined`。 |
| `getOrigin()` | `index.d.ts:167` | `http(s)://<bindUrlHost>:<port>`；绑定前或无 URL 形式地址会抛。 |
| `register(route)` | `index.d.ts:174`，`WebRoute` 在 `:34-40` | `{kind:'exact'\|'prefix', path:'/abs/no/trailing', handler:(req:IncomingMessage,res:ServerResponse)=>void\|Promise<void>}`；重复 `(kind,path)` 抛错；返回**移除 disposer**。handler 自己拥有响应生命周期（可 SSE/长连接）。 |
| `registerUpgrade(route)` | `index.d.ts:181` | WebSocket 等 Upgrade 精确路径。 |
| `registerFallback(handler)` | `index.d.ts:190` | 唯一兜底席位，二次注册抛错。 |
| `tapIndex(fn)` / `renderIndex(html)` | `index.d.ts:197,222` | 原生 index.html 的 raw 变换 / 结构化注入表渲染。 |

拿监听地址（范例 `$EXAMPLE/index.mjs:1175-1178`）：
```js
dshListen = { host: ctx.webServer.host, port: ctx.webServer.port }
```

**鉴权提醒【实测/源码】**：`webServer.register` 的路由**不经过 Connection 的鉴权栅栏**；栅栏只覆盖 `/api`（`$PROF/cordis.patch.yml` 中 connection 行注释；`$WS/dsh-client-connection/lib/types/rpc-host.d.ts:101-126`）。自己挂的 HTTP 路由若暴露在 `192.168.31.170:3080` 上就是**局域网明文可访问**。可在 handler 里显式调用：

```js
const rej = ctx.connection.requestRejection(req)   // req.headers
if (rej) { res.writeHead(rej); return res.end() }
```
`requestRejection(request: ConnectionTrustRequest): 401|403|undefined`（`rpc.d.ts:77-82,185`）；`admit(request)` 返回 `{peer}` 或 `{rejection}`（`:191`）。`ConnectionTrustRequest` 只需要 `{ headers }`（`:77-80`）。

### 2.3 `connection.authenticatedUrl()`

- 签名：`authenticatedUrl(baseUrl: string): string`（Host 侧 `$WS/dsh-client-connection/lib/types/rpc-host.d.ts:60`；接口 `rpc.d.ts:223`；实现语义见 `browser-auth.d.ts:28-32`）。
- 语义：把**本进程启动令牌**作为唯一认证参数附加到 `baseUrl`，保留原 authority/mount，返回同一个 URL（`browser-auth.d.ts:26-30`）；用它发一次请求会 `303` + `Set-Cookie` 换取浏览器会话 Cookie（Host 侧的 connect 重定向契约见 `rpc-host.d.ts:50-54`；权威调用范例 `$EXAMPLE/index.mjs:114-124`：`fetch(loginUrl,{redirect:'manual'})` → 取 `set-cookie`，要求 `303`）。
- 它**不是**通用鉴权/校验函数，只是“登录入口 URL”。同文件的 `authorizeIndex`（`:44`）、`isAuthenticated`（`:59`）、`authenticateApi`（`:66`）在 `BrowserAuth` 上，**未**经 `ctx.connection` 暴露——所以路由鉴权只能用 2.2 的 `requestRejection`/`admit`。

### 2.4 `commands` / `agents` 最小用法

`commands`（`$WS/dsh-commands/lib/types/index.d.ts`）：
```js
const off = ctx.commands.register({          // :94
  name: 'hello', description: 'demo',
  handler: ({ agent, rawInput, signal }) => ({ kind: 'success', text: 'hi' }),
})
const list = ctx.commands.list(agent)        // :106
const out  = await ctx.commands.execute(agent, '/hello', [], signal)  // :142，4 参
```
当前版本 `execute` 固定 4 参：`(agent, line, submittedAttachments, signal)`。handler 返回值是 `{kind:'success', text?}` 或 `{kind:'error', text}`（`$WS/dsh-commands/lib/types/types.d.ts:33-41`）。范例插件为兼容旧版做了**运行时探测**（`execute.length === 3 ? execute(agent,line,signal) : execute(agent,line,[],signal)`，`$EXAMPLE/index.mjs:608-612`）——说明该签名历史上变过，**调用方按 4 参写即可（JS 多传参不影响旧实现）**。

`agents`（`$WS/dsh-agent/lib/types/index.d.ts`）：`ctx.agents.get(id): Agent|undefined`（`:343`）、`list(): Agent[]`（`:357`）、`create(options)`（`:278`）、`resume({resumeSessionId})`（`:286`，返回 `AgentHandle`，`:143-146`）。范例 `$EXAMPLE/index.mjs:556-570` 先 `get` 再 `resume`。

## 3. 浏览器端插件注入

### 3.1 加载机制（已核实）

1. client-modules 的 node 半体扫描 Loader 行 → 组 `window.__DSH_BOOT__` 图（`$WS/dsh-client-modules/lib/types/index.d.ts:1-21`）。
2. 浏览器先执行内联队列，再加载 bootstrap/application combo；每个 bundle 只是**注册工厂**：`window.__ModuleLoader__.load({ id, factory })`（`$WS/dsh-client-modules/lib/types/client/manifest.d.ts:10-13,195-204,226-239`）。
3. `factory(require)` 在首次 import 时**只执行一次**并缓存；`id` **必须等于包名**（图行 key，`manifest.d.ts:195-197`）。
4. 文件按**原始字节拼接**下发，无转译/打包（`$WS/dsh-client-modules/lib/index.js:337-341`）；因此可以“产物入库、无构建步骤”直接手写 CJS 风格 `client.js`（范例 `$EXAMPLE/client.js:1-6`）。
5. 同源 URL 形如 `/plugins/??<id>/client.js&rev=<rev>`（combo 查询可并列多包，`$WS/dsh-client-modules/lib/index.js:202-210`）。

### 3.2 模块表种子（决定 `require()` 能拿到什么）

Web shell 静态表（`$WS/dsh-web-frontend/dist/assets/index-CN7NIWNw.js:190`，原文）：

```
react, react/jsx-runtime, react-dom, react-dom/client, @deepseek-ai/cordis, zod,
@tanstack/react-virtual, @deepseek-ai/dsh-client-store,
@deepseek-ai/dsh-client-ui-slots, @deepseek-ai/dsh-client-ui-primitives,
@deepseek-ai/dsh-client-ui-dockkit
```

**`@deepseek-ai/dsh-client-runtime` 不在此表、安装目录也没有该包 → `require` 会抛。** 取 store 必须用 `@deepseek-ai/dsh-client-store`（范例已做 try/catch 回退，`$EXAMPLE/client.js:10-16`）。

### 3.3 槽位 API

服务声明：`Context.slots: SlotRegistry`（`$WS/dsh-client-ui-renderer/lib/types/client/index.d.ts:26-28`）。

- `ctx.slots.inject(key, callback)`：槽位被声明时执行 callback，返回 dispose；槽位收起时回调的 effects 被清理（`registry.d.ts:96-111`）。
- `ctx.slots.register(spec, Component)`：`spec = { name, id, order?, label?, store?, inject? }`，返回定义 disposer；经调用方 `ctx.effect` 绑定生命周期（`registry.d.ts:69-85`）。
- 这就是 client 插件要 `export const inject = ['slots']` 的原因（服务注入，同 `$EXAMPLE/client.js:173`）。

目标槽位（原生侧边栏）：
- `sidebar.footer.action`：**list** 槽，scope root，侧边栏脚步 Settings 旁的动作位（`$WS/dsh-client-ui-sidebar/lib/types/client/contract/slots.d.ts:76-81`；渲染位置 `.../lib/client.js:403`）。这是“侧边栏入口”的正解。
- `shell.overlay`：**list** 槽，整帧浮层，点击穿透，条目自行开启 pointer events（`$WS/dsh-client-ui-layout/lib/types/client/index.d.ts:88-98`）——适合打开抽屉/面板。
- 单值槽（`sidebar`、`sidebar.settings` 等）注册会**替换**原生 occupant，不要占。

### 3.4 最小 client.js（可直接复用）

```js
/* client.js — 无构建步骤，产物入库 */
window.__ModuleLoader__.load({
  id: 'my-gateway-plugin',                 // 必须 === package.json name
  factory: (require) => {
    const React = require('react')
    const { defineStore } = require('@deepseek-ai/dsh-client-store')
    const module = { exports: {} }
    const exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    const store = defineStore({
      init: () => ({ open: false }),
      actions: { toggle(d) { d.open = !d.open }, close(d) { d.open = false } },
    })

    function SidebarEntry(props) {
      const open = props.useStore((s) => s.open)
      return React.createElement('button', {
        type: 'button', title: 'My Gateway', 'aria-expanded': open,
        onClick: () => props.actions.toggle(),
        style: { display: 'flex', alignItems: 'center', gap: 7, width: '100%',
                 padding: '6px 10px', border: 'none', borderRadius: 8,
                 background: open ? 'rgba(125,207,255,.14)' : 'transparent',
                 cursor: 'pointer', font: '500 13px/1.3 system-ui, sans-serif' },
      }, 'My Gateway')
    }

    function Drawer(props) {
      const open = props.useStore((s) => s.open)
      return React.createElement('div', {
        'aria-hidden': !open,
        style: { position: 'fixed', top: 12, right: 12, bottom: 12, zIndex: 2147483000,
                 width: 'min(520px, calc(100vw - 24px))', background: '#fff',
                 border: '1px solid rgba(128,128,128,.25)', borderRadius: 16,
                 overflow: 'hidden', display: open ? 'block' : 'none' },
      }, React.createElement('iframe', {
        src: '/my-gw/ui', title: 'My Gateway',
        style: { width: '100%', height: '100%', border: 'none' },
      }))
    }

    const inject = ['slots']
    function apply(ctx) {
      ctx.effect(() => ctx.slots.inject('sidebar.footer.action', () =>
        ctx.slots.register({ name: 'sidebar.footer.action', id: 'my-gateway', order: 100, store }, SidebarEntry)),
        'my-gateway: sidebar entry')
      ctx.effect(() => ctx.slots.inject('shell.overlay', () =>
        ctx.slots.register({ name: 'shell.overlay', id: 'my-gateway-drawer', order: 100, store }, Drawer)),
        'my-gateway: overlay')
    }

    exports.apply = apply
    exports.inject = inject
    return module.exports
  },
})
```

> 若面板需要读取 Host 数据：浏览器同源 `fetch('/my-gw/api/...')` 会带 Cookie；Host 端按 2.2 用 `requestRejection` 验签。**不要**把启动令牌写进页面/前端常量。

## 4. 安装 / 更新 / 重启

`dsh plugin` 是 **pnpm 转发器**（`$CLI/lib/bin.js:139-150` 的 commander 定义；`$CLI/lib/types/plugin.d.ts:1-8`），`--profile` 之后的所有参数原样交给 pnpm，工作目录是 profile 目录（`$CLI/lib/plugin-BiveYA7h.js:72-91`）。首次对不存在的 profile 执行会先 `initProfile`（shipped 模板优先，否则 `@deepseek-ai/dsh-base`，`:39-41`）。

- 安装：`dsh plugin --profile web add <pkg>` / `add <pkg>@<version>`
- 更新：`dsh plugin --profile web add <pkg>@<new>` 或 `dsh plugin --profile web update <pkg>`（都只是 pnpm 语义）
- 移除：`dsh plugin --profile web remove <pkg>`

**本地路径 / tgz 是支持的【源码】**：
- `./`、`../` 相对路径会先按**调用者 cwd** 绝对化（`$WS/dsh-plugin-manager/lib/index.js:300-304`），因此 `dsh plugin --profile web add ./dist/my-plugin-0.1.0.tgz` 可用；纯绝对路径也可。
- 安装规格解析器接受 `file:`/`link:`/绝对路径/`.tgz`（`$WS/dsh-plugin-manager/lib/types/install-spec.js:58-86`）；**浏览器 UI 的安装入口要求绝对路径**（同文件 d.ts 注释）。
- 目录会由 pnpm 记为本地依赖（通常是 `link:`），其自身依赖**未必**被装进 profile；要“独立可分发”优先用 `npm pack` 出的 tgz。

**自动激活**：pnpm 成功（exit 0）后 `reconcile` 把**新增的**直接依赖里声明了 `dsh.bundle` 的包追加进 `package.json` 的 `dsh.profile.bundles`；没有 `dsh.bundle` 的只 warn（`$WS/dsh-plugin-manager/lib/index.js:323-351`、`:762`）。已禁用/已存在的 bundle 不会被重新启用（`:328-331`）。

**profile 的 pnpm 约定**（`initProfile` 写入，`$WS/dsh-app-boot/lib/index.js:598-603,611-627`）：

```yaml
packages:
  - .
nodeLinker: hoisted
autoInstallPeers: false
```

**重启 Web 的方法（未执行）**：本机 3080 由 systemd 用户单元托管（`/root/.config/systemd/user/dsh-web.service` → `ExecStart=/usr/local/lib/dsh-web-run.sh` → `node .../dsh/lib/bin.js web --port 3080 --no-open`）。改 host 插件/客户端 bundle/`dsh.profile.bundles` 后需重启：

```bash
systemctl --user restart dsh-web.service    # 会重新走 3080 与 .web-auth-url 发布
journalctl --user -u dsh-web.service -f     # 观察启动/插件 FAILED fiber
```

`cordis.patch.yml` 支持热重载，但 `package.json` 的 bundle 列表、插件包代码、client.js 都是**启动期读取**（未发现 watch 它们的代码；`client-hmr` 只服务 `pnpm run dev:web` 重写产物场景）。

## 5. 隔离测试（命令只写不执行）

可以。两条路线：

### A. 同 DSH_HOME、独立 profile、另一个端口（最省事）

```bash
# 1) 用官方 web 模板初始化 dev-test，仅写文件不监听
dsh --profile dev-test --from-default-profile web --dump-default-config >/dev/null

# 2) 安装本地插件（tgz 优先）
dsh plugin --profile dev-test add /root/dsh-app/dist/my-gateway-plugin-0.1.0.tgz

# 3) 换端口启动（--host/--port 是 web app 的参数，launcher 会透传）
dsh --profile dev-test --host 127.0.0.1 --port 3091 --no-open
```

要点：
- 模板生成的 `dev-test/cordis.patch.yml` 是空层，所以 `--host/--port` 生效（webserver 行读 `ctx.webStartup.*`，`$WS/dsh-web-app/cordis.patch.yml` “webserver” 行；flag 解析 `$WS/dsh-web-app/lib/startup.js:35-43,61-68`）。
- **不要**把 `$PROF/cordis.patch.yml` 原样拷进来：它硬编码 `webserver host: 192.168.31.170 / port: 3080`，会压过命令行 flag。若必须复用，用 `--patch` 覆盖（overlay 在 profile 层之后）：
  ```bash
  dsh --profile dev-test --patch /root/dsh-app/dev-test-port.yml --no-open
  # dev-test-port.yml:
  # - id: webserver
  #   config: { host: '127.0.0.1', port: 3091 }
  ```
  注意非 insert 补丁**整块替换** `config`，需自行补齐 `compression` 等键。

### B. 完全独立 DSH_HOME（更隔离）

```bash
DSH_HOME=/root/.dsh-dev dsh web --host 127.0.0.1 --port 3091 --no-open   # 首次自动按 web 模板建 profile
DSH_HOME=/root/.dsh-dev dsh plugin --profile web add /root/dsh-app/dist/my-gateway-plugin-0.1.0.tgz
DSH_HOME=/root/.dsh-dev dsh web --host 127.0.0.1 --port 3091 --no-open
```
`DSH_HOME` 优先级见 `$WS/dsh-home-paths/lib/types/index.d.ts:59-74`；缺失的 shipped profile 会自动初始化（`$WS/dsh-app-boot/lib/index.js:1065-1073`）。代价：新 home 没有 3080 的 provider/模型配置（模板 patch 为空），UI/路由可测、真实会话可能缺模型。

不影响 3080 的前提：**端口不冲突**；且不要 `systemctl` 操作生产单元。风险：路线 A 与 3080 共享 `~/.dsh` 的 sessions/日志/daemon 目录，两个进程若操作同一 session 会互相踩（未做并发写实测，标为 **【未确认】**）；路线 B 无此问题。

## 6. 版本 / 兼容风险（0.2.1-rc.2）

1. **客户端种子收窄**：只有 3.2 列出的 11 个名字。`@deepseek-ai/dsh-client-runtime` 已不可 require；`defineStore` 在 `dsh-client-store`。范例注释亦称 0.1.2-alpha.1 起迁移（`$EXAMPLE/client.js:10-16`）。新插件不要依赖旧模块名。
2. **`dsh.client` 强校验**：`platform` 必须 `"web"`；必须存在 `exports["./client"]`；`inject`/`external` 必须是字符串数组，否则启动期抛错（`parseDshClient`，`$WS/dsh-client-modules/lib/index.js:62-76`）。
3. **`commands.execute` 曾变签名**：当前 4 参（`dsh-commands/lib/types/index.d.ts:142`），范例带 3 参回退（`$EXAMPLE/index.mjs:608-612`）。
4. **兼容性门禁**：`engines.dsh` 优先，不满足时**整层 bundle 被跳过**（`$WS/dsh-app-boot/lib/index.js:279-298,1015-1037`）；豁免须精确 `name@version` + 精确 DSH 版本（`$CLI/lib/plugin-BiveYA7h.js:16-56`、`dsh plugin allow-version`）。**不要**写 `workspace:*` 到 `engines.dsh`。
5. **bundle patch 必须是 string|string[]**（`dsh-package-manifest/.../types.d.ts:68`，`dsh-app-boot/lib/index.js:517-524`）。
6. **webServer 拒绝通配绑定**（`0.0.0.0`/`::` 直接启动失败，`dsh-host-webserver/lib/types/index.d.ts:60-71`）；`port: 0` 合法（OS 分配，`:72`）。
7. **路由默认不鉴权**（见 2.2），暴露到非 loopback 绑定地址前必须自行 `requestRejection` 或改用 `/api` 的 Connection fetch 路由（`ctx.connection.fetch`）。
8. `dsh.client.inject` 里写不存在的包名**不会报错也不会等待**（`dsh-client-modules/lib/client.js:789-791`），不要把它当服务依赖。
9. profile 根 `cordis.yml` 每次启动被重写（`profile-boot.d.ts:8-13`），任何手改会丢。

## 7. 未确认点（需实测）

- 本地**目录**安装（`add /abs/dir`）时 pnpm 记为 `link:`，其 `dependencies` 是否被装入 profile 的 `node_modules`：**未确认**；发布前请用 tgz 并实测。
- 同一 `DSH_HOME` 下两个 dsh web 实例同时运行对 sessions/日志/daemon 锁的影响：**未确认**（建议路线 B）。
- `command.execute` 传入 4 参时旧实现的行为只是 JS 语义推断（多余实参被忽略）；运行期未实测。
- 范例包 `dsh-remote-plugin@0.8.0-rc.9` 未声明 `engines.dsh`，其与本版本的完全兼容性**未确认**（本报告只把它当结构范例，不为其运行时正确性背书）。
- 未见 0.2.1-rc.2 的官方 CHANGELOG/迁移文档随包分发（`$CLI/README.md` 无版本变更节）；上述“变更点”均由源码与范例注释推断。
