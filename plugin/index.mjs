/**
 * plugin/index.mjs — dsh-mobile DSH bundle 插件（服务端半体）。
 *
 * 职责（SPEC v3 §6）：
 *  - 挂 `/mobile` 主机面板（admin.html/admin.js，DeepSeek 简洁风，无外部依赖）；
 *  - 用 `ctx.connection.authenticatedUrl()` 把 DSH 启动令牌兑换成上游 Cookie，
 *    写入 `~/.dsh-mobile/upstream.cookie`（0600）供独立网关进程读取；
 *  - spawn/守护网关进程（默认 0.0.0.0:8790，可被 env 收紧），DSH 退出时清理；
 *  - `/mobile/api/*` 面板数据接口（`ctx.connection.requestRejection` 做同源鉴权）。
 *
 * 不做 Cloudflare/cloudflared 管理（v3：用户自建通道，把域名转发到 8790 即可）。
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

export const name = 'dsh-mobile-plugin'
export const inject = ['webServer', 'connection']

const PLUGIN_DIR = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_VERSION = '0.1.12'
const GATEWAY_VERSION = '0.1.12'
const DEFAULT_GATEWAY_PORT = 8790
const MAX_LOG_LINES = 200
const RESTART_DELAYS = [1000, 2000, 5000, 15_000, 30_000]
const COOKIE_REFRESH_MS = 30 * 60 * 1000
const COOKIE_RETRY_MS = 60 * 1000

function resolveHome(env = process.env) {
  if (env.DSH_MOBILE_CONFIG_DIR) return path.resolve(env.DSH_MOBILE_CONFIG_DIR)
  if (env.DSH_MOBILE_HOME) return path.resolve(env.DSH_MOBILE_HOME)
  return path.join(env.HOME || os.homedir(), '.dsh-mobile')
}

function resolveBinHost(env = process.env) {
  return String(env.DSH_MOBILE_GATEWAY_HOST || '0.0.0.0').trim() || '0.0.0.0'
}

function upstreamUrlForListener(webServer) {
  let target = String(webServer?.host || '127.0.0.1').replace(/^\[|\]$/g, '')
  if (target === '0.0.0.0') target = '127.0.0.1'
  if (target === '::') target = '::1'
  const port = Number(webServer?.port) || 3080
  return `http://${target.includes(':') ? `[${target}]` : target}:${port}`
}

function resolveGatewayScript() {
  const candidates = [path.join(PLUGIN_DIR, 'gateway', 'gateway.mjs'), path.join(PLUGIN_DIR, '..', 'gateway', 'gateway.mjs')]
  for (const candidate of candidates) {
    try {
      if (fs.statSync(candidate).isFile()) return candidate
    } catch {
      /* try next */
    }
  }
  return null
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
  try {
    fs.chmodSync(dir, 0o700)
  } catch {
    /* ignore */
  }
}

function writeFilePrivate(file, text) {
  ensureDir(path.dirname(file))
  const tmp = `${file}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`
  fs.writeFileSync(tmp, text, { mode: 0o600 })
  fs.chmodSync(tmp, 0o600)
  fs.renameSync(tmp, file)
  fs.chmodSync(file, 0o600)
}

function readTextFile(file) {
  try {
    return fs.readFileSync(file, 'utf8')
  } catch {
    return null
  }
}

function readJsonFile(file) {
  const text = readTextFile(file)
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function safeJson(res, status, payload) {
  if (res.headersSent || res.writableEnded) return
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
  })
  res.end(body)
}

function safeFile(res, file, type) {
  let body
  try {
    body = fs.readFileSync(file)
  } catch {
    return safeJson(res, 404, { error: 'not-found' })
  }
  res.writeHead(200, {
    'content-type': type,
    'content-length': body.length,
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
    'content-security-policy': "default-src 'self'; style-src 'unsafe-inline'; script-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'",
  })
  res.end(body)
}

function readBodyJson(req, limit = 64 * 1024) {
  return new Promise((resolve) => {
    const chunks = []
    let size = 0
    let done = false
    req.on('data', (chunk) => {
      if (done) return
      size += chunk.length
      if (size > limit) {
        done = true
        req.resume()
        resolve(null)
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      if (done) return
      done = true
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {})
      } catch {
        resolve(null)
      }
    })
    req.on('error', () => {
      if (!done) {
        done = true
        resolve(null)
      }
    })
  })
}

/** 附件引用 id：sha256 的 64 位小写 hex。 */
const ATTACHMENT_ID_RE = /^[a-f0-9]{64}$/

function sendBinaryError(res, status, code) {
  if (res.headersSent || res.writableEnded) return
  const body = JSON.stringify({ ok: false, code })
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  })
  res.end(body)
}

/**
 * 读取会话内附件字节（图片/文件）并流式返回给 App。
 *
 * 背景（task-16）：消息里的图片/文件是 `{type:'image'|'file', attachment:{attachmentId:'sha256:<hex>',...}}`
 * 引用，DSH 的 session/attachment RPC 经 HTTP 是 multipart，普通 JSON 代理拿不到字节。
 * 这里在插件进程内用 sessionController.inspect 找到引用，再用 attachments 服务读出字节。
 *
 * 读取策略：
 *  - image 引用 → `attachments.readImage(ref)`（图片对象存放于 objects/，readFileStream 只接受
 *    FileAttachmentRef，直接用它读图片会找不到对象）；
 *  - file 引用 → `attachments.readFileStream(ref, signal)` 以有界 chunk 流式返回（不在内存收集整文件）。
 * 语义参考旧插件 dsh-remote-plugin/session-file.mjs（MIT），按新 DSH 0.2.1 附件 API 调整。
 */
async function serveSessionFile(ctx, req, res, url) {
  // 同源鉴权由调用方（/mobile/api/* 统一入口）已完成。
  if (req.method !== 'GET' && req.method !== 'HEAD') return sendBinaryError(res, 405, 'read-only')
  const keys = [...url.searchParams.keys()]
  if (
    keys.some((key) => key !== 'sessionId' && key !== 'attachmentId') ||
    url.searchParams.getAll('sessionId').length !== 1 ||
    url.searchParams.getAll('attachmentId').length !== 1
  ) {
    return sendBinaryError(res, 400, 'invalid-reference')
  }
  const sessionId = url.searchParams.get('sessionId') || ''
  const attachmentId = url.searchParams.get('attachmentId') || ''
  if (!sessionId || sessionId.length > 200 || /[\u0000-\u001f]/.test(sessionId) || !ATTACHMENT_ID_RE.test(attachmentId)) {
    return sendBinaryError(res, 400, 'invalid-reference')
  }

  let controller
  let attachments
  try {
    controller = ctx.get?.('sessionController')
    attachments = ctx.get?.('attachments')
  } catch {
    controller = null
    attachments = null
  }
  if (!controller?.inspect || !attachments) return sendBinaryError(res, 503, 'files-unsupported')

  const abort = new AbortController()
  const cancel = () => abort.abort()
  res.once('close', cancel)
  try {
    const inspection = await controller.inspect(sessionId, abort.signal)
    const wanted = `sha256:${attachmentId}`
    let ref = null
    let kind = null
    for (const event of inspection?.events || []) {
      if (!event || !['user/message', 'assistant/message', 'tool/result'].includes(event.type)) continue
      const blocks = event.data?.message?.content ?? event.data?.content
      if (!Array.isArray(blocks)) continue
      for (const block of blocks) {
        if (!block || (block.type !== 'image' && block.type !== 'file')) continue
        if (block.attachment?.attachmentId === wanted) {
          ref = block.attachment
          kind = block.type
          break
        }
      }
      if (ref) break
    }
    if (!ref) return sendBinaryError(res, 404, 'file-not-in-session')
    if (!Number.isSafeInteger(ref.bytes) || ref.bytes < 0) return sendBinaryError(res, 502, 'invalid-host-reference')

    const mediaType = typeof ref.mediaType === 'string' && /^[\w.+-]+\/[\w.+-]+$/.test(ref.mediaType)
      ? ref.mediaType
      : 'application/octet-stream'
    const baseHeaders = {
      'content-type': mediaType,
      'cache-control': 'private, no-store',
      'x-content-type-options': 'nosniff',
    }

    if (kind === 'image' && typeof attachments.readImage === 'function') {
      const stored = await attachments.readImage(ref, abort.signal)
      const data = Buffer.from(stored?.data ?? stored?.bytes ?? stored ?? [])
      if (!data.length) return sendBinaryError(res, 502, 'file-unavailable')
      res.writeHead(200, { ...baseHeaders, 'content-length': String(data.length) })
      if (req.method === 'HEAD') return res.end()
      return res.end(data)
    }

    if (typeof attachments.readFileStream !== 'function') return sendBinaryError(res, 503, 'files-unsupported')
    const iterator = attachments.readFileStream(ref, abort.signal)[Symbol.asyncIterator]()
    const first = await iterator.next()
    if (first.done && ref.bytes > 0) return sendBinaryError(res, 502, 'file-unavailable')
    res.writeHead(200, { ...baseHeaders, 'content-length': String(ref.bytes) })
    if (req.method === 'HEAD') {
      await iterator.return?.()
      return res.end()
    }
    let written = 0
    let next = first
    try {
      while (!next.done) {
        abort.signal.throwIfAborted()
        const chunk = Buffer.from(next.value)
        written += chunk.length
        if (written > ref.bytes) throw new Error('attachment-size-mismatch')
        if (!res.write(chunk)) await new Promise((resolve, reject) => {
          res.once('drain', resolve)
          res.once('close', () => reject(new Error('client-closed')))
        })
        next = await iterator.next()
      }
      if (written !== ref.bytes) throw new Error('attachment-size-mismatch')
      res.end()
    } finally {
      await iterator.return?.()
    }
    return undefined
  } catch (err) {
    if (res.headersSent) {
      try {
        res.destroy()
      } catch {
        /* ignore */
      }
      return
    }
    return sendBinaryError(res, abort.signal.aborted ? 499 : 502, 'file-unavailable')
  } finally {
    res.off('close', cancel)
  }
}

export function apply(ctx, config = {}) {
  const env = process.env
  const home = resolveHome(env)
  const gatewayPort = Number(config?.port) || Number(env.DSH_MOBILE_GATEWAY_PORT) || DEFAULT_GATEWAY_PORT
  const bindHost = resolveBinHost(env)
  const upstreamBase = upstreamUrlForListener(ctx.webServer)
  const gatewayScript = resolveGatewayScript()

  const log = {
    info: (message) => (typeof ctx.logger?.info === 'function' ? ctx.logger.info(message) : console.log(`[dsh-mobile] ${message}`)),
    warn: (message) => (typeof ctx.logger?.warn === 'function' ? ctx.logger.warn(message) : console.warn(`[dsh-mobile] ${message}`)),
    error: (message) => (typeof ctx.logger?.error === 'function' ? ctx.logger.error(message) : console.error(`[dsh-mobile] ${message}`)),
  }

  const state = {
    child: null,
    childLogs: [],
    restarts: 0,
    lastError: null,
    lastExitAt: null,
    stopped: false,
    manualRestart: false,
    restartTimer: null,
    cookieTimer: null,
    cookieAt: 0,
    cookieError: null,
    cookieRefreshing: false,
    adminToken: crypto.randomBytes(32).toString('base64url'),
  }

  function pushLog(line) {
    const text = String(line).replace(/\s+$/, '')
    if (!text) return
    // 网关自身日志已脱敏；这里只做长度限制，避免面板被超长行撑爆。
    state.childLogs.push(text.slice(0, 2000))
    while (state.childLogs.length > MAX_LOG_LINES) state.childLogs.shift()
  }

  function readConfig() {
    return readJsonFile(path.join(home, 'config.json'))
  }

  function mustChangePassword() {
    const configFile = readConfig()
    if (configFile && typeof configFile.mustChangePassword === 'boolean') return configFile.mustChangePassword
    return true
  }

  function readInitialPassword() {
    const text = readTextFile(path.join(home, 'initial-password.txt'))
    if (!text) return null
    const trimmed = text.trim()
    return trimmed && trimmed.length <= 128 ? trimmed : null
  }

  /** DSH 启动令牌 → 上游 Cookie（303 + Set-Cookie），写 ~/.dsh-mobile/upstream.cookie(0600)。 */
  async function refreshCookie() {
    if (state.cookieRefreshing) return false
    if (typeof ctx.connection?.authenticatedUrl !== 'function') {
      state.cookieError = 'authenticated-url-unavailable'
      log.warn('connection.authenticatedUrl 不可用，跳过上游 Cookie 交换')
      return false
    }
    state.cookieRefreshing = true
    try {
      const loginUrl = ctx.connection.authenticatedUrl(upstreamBase)
      const response = await fetch(loginUrl, {
        redirect: 'manual',
        signal: AbortSignal.timeout(3000),
      })
      const setCookies = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [response.headers.get('set-cookie')].filter(Boolean)
      const cookie = String(setCookies[0] || '').split(';', 1)[0].trim()
      if (response.status !== 303 || !cookie.includes('=') || cookie.length > 4096 || /[\r\n\u0000]/.test(cookie)) {
        throw new Error(`cookie-exchange-http-${response.status}`)
      }
      ensureDir(home)
      writeFilePrivate(path.join(home, 'upstream.cookie'), `${cookie}\n`)
      writeFilePrivate(path.join(home, 'upstream.json'), `${JSON.stringify({ base: upstreamBase })}\n`)
      state.cookieAt = Date.now()
      state.cookieError = null
      return true
    } catch (err) {
      // 绝不记录 token/URL/Cookie；只记录不含凭据的错误类型。
      state.cookieError = err?.code || (err?.message && !/token|secret|cookie=/i.test(err.message) ? err.message : 'cookie-exchange-failed')
      return false
    } finally {
      state.cookieRefreshing = false
    }
  }

  function scheduleCookieRefresh(delayMs = COOKIE_REFRESH_MS) {
    if (state.cookieTimer) clearTimeout(state.cookieTimer)
    state.cookieTimer = setTimeout(async () => {
      const ok = await refreshCookie().catch(() => false)
      if (!state.stopped) scheduleCookieRefresh(ok ? COOKIE_REFRESH_MS : COOKIE_RETRY_MS)
    }, delayMs)
    state.cookieTimer.unref?.()
  }

  function gatewayEnv() {
    return {
      ...process.env,
      DSH_MOBILE_GATEWAY_PORT: String(gatewayPort),
      DSH_MOBILE_GATEWAY_HOST: bindHost,
      DSH_MOBILE_UPSTREAM: upstreamBase,
      DSH_MOBILE_ROOTS: JSON.stringify([process.cwd()]),
      DSH_MOBILE_ADMIN_TOKEN: state.adminToken,
    }
  }

  function scheduleGatewayRestart() {
    if (state.stopped || state.restartTimer) return
    const delay = RESTART_DELAYS[Math.min(state.restarts, RESTART_DELAYS.length - 1)]
    state.restarts += 1
    state.restartTimer = setTimeout(() => {
      state.restartTimer = null
      startGateway()
    }, delay)
    state.restartTimer.unref?.()
  }

  function startGateway() {
    if (state.stopped || state.child) return
    if (!gatewayScript) {
      state.lastError = 'gateway-script-missing'
      log.error('找不到 gateway/gateway.mjs，无法拉起网关')
      return
    }
    let child
    try {
      child = spawn(process.execPath, [gatewayScript], {
        env: gatewayEnv(),
        stdio: ['ignore', 'pipe', 'pipe'],
      })
    } catch (err) {
      state.lastError = err?.code || 'spawn-failed'
      scheduleGatewayRestart()
      return
    }
    state.child = child
    state.lastError = null
    let stdoutBuffer = ''
    let stderrBuffer = ''
    const consume = (chunk, stream) => {
      const text = String(chunk)
      const buffer = (stream === 'out' ? (stdoutBuffer += text) : (stderrBuffer += text))
      const parts = buffer.split(/\r?\n/)
      const rest = parts.pop()
      if (stream === 'out') stdoutBuffer = rest
      else stderrBuffer = rest
      for (const line of parts) pushLog(line)
    }
    child.stdout?.on('data', (chunk) => consume(chunk, 'out'))
    child.stderr?.on('data', (chunk) => consume(chunk, 'err'))
    child.on('error', (err) => {
      state.lastError = err?.code || 'gateway-spawn-error'
    })
    child.on('exit', (code, signal) => {
      state.child = null
      state.lastExitAt = Date.now()
      if (state.stopped) return
      if (state.manualRestart) {
        state.manualRestart = false
        state.restarts = 0
        startGateway()
        return
      }
      state.lastError = signal ? `signal-${signal}` : `exit-${code}`
      log.warn(`网关进程退出（${state.lastError}），准备重启`)
      scheduleGatewayRestart()
    })
    const healthUrl = `http://127.0.0.1:${gatewayPort}/health`
    let probeCount = 0
    const probe = setInterval(async () => {
      probeCount += 1
      try {
        const response = await fetch(healthUrl, { signal: AbortSignal.timeout(1500) })
        if (response.ok) {
          clearInterval(probe)
          state.restarts = 0
          log.info(`dsh-mobile 网关已就绪：${bindHost}:${gatewayPort}`)
        }
      } catch {
        /* 网关还在启动 */
      }
      if (probeCount > 40) clearInterval(probe)
    }, 250)
    probe.unref?.()
  }

  function stopGateway() {
    state.stopped = true
    if (state.restartTimer) {
      clearTimeout(state.restartTimer)
      state.restartTimer = null
    }
    if (state.cookieTimer) {
      clearTimeout(state.cookieTimer)
      state.cookieTimer = null
    }
    const child = state.child
    state.child = null
    if (child && !child.killed) {
      try {
        child.kill('SIGTERM')
      } catch {
        /* ignore */
      }
      const timer = setTimeout(() => {
        try {
          if (!child.killed) child.kill('SIGKILL')
        } catch {
          /* ignore */
        }
      }, 3000)
      timer.unref?.()
    }
  }

  async function restartGateway() {
    if (state.restartTimer) {
      clearTimeout(state.restartTimer)
      state.restartTimer = null
    }
    state.stopped = false
    state.restarts = 0
    const child = state.child
    if (child) {
      state.manualRestart = true
      try {
        child.kill('SIGTERM')
      } catch {
        state.manualRestart = false
        startGateway()
      }
    } else {
      startGateway()
    }
    return true
  }

  function lanAddresses() {
    if (bindHost !== '0.0.0.0' && bindHost !== '::') {
      const host = bindHost.includes(':') ? `[${bindHost}]` : bindHost
      return [{ iface: 'bind', address: bindHost, url: `http://${host}:${gatewayPort}` }]
    }
    const out = []
    const interfaces = os.networkInterfaces()
    for (const [iface, list] of Object.entries(interfaces)) {
      for (const info of list || []) {
        if (info.family !== 'IPv4' && info.family !== 4) continue
        if (info.internal) continue
        out.push({ iface, address: info.address, url: `http://${info.address}:${gatewayPort}` })
      }
    }
    out.push({ iface: 'loopback', address: '127.0.0.1', url: `http://127.0.0.1:${gatewayPort}` })
    return out
  }

  async function probeGateway() {
    try {
      const response = await fetch(`http://127.0.0.1:${gatewayPort}/health`, { signal: AbortSignal.timeout(1500) })
      if (!response.ok) return { running: false, status: response.status, health: null }
      const health = await response.json()
      return { running: true, status: response.status, health }
    } catch {
      return { running: false, status: 0, health: null }
    }
  }

  /** 带 adminToken 调网关插件侧管理接口（仅本机回环，不暴露给浏览器）。 */
  async function gatewayAdminFetch(pathname, { method = 'GET', body } = {}) {
    const headers = { 'x-admin-token': state.adminToken }
    if (body !== undefined) headers['content-type'] = 'application/json'
    try {
      const response = await fetch(`http://127.0.0.1:${gatewayPort}${pathname}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(2500),
      })
      let json = null
      try {
        json = await response.json()
      } catch {
        json = null
      }
      return { status: response.status, json }
    } catch (err) {
      return { status: 0, json: null, error: err?.code || 'gateway-unreachable' }
    }
  }

  function configDirMode() {
    try {
      const mode = fs.statSync(home).mode & 0o777
      return mode.toString(8)
    } catch {
      return null
    }
  }

  async function statusPayload() {
    const [gateway, overview] = await Promise.all([
      probeGateway(),
      gatewayPort ? gatewayAdminFetch('/api/admin/overview') : Promise.resolve({ status: 0, json: null }),
    ])
    const adminOverview = overview && overview.status === 200 && overview.json ? overview.json : null
    const changePassword = mustChangePassword()
    return {
      ok: true,
      plugin: { name, version: PLUGIN_VERSION },
      gateway: {
        running: gateway.running,
        port: gatewayPort,
        host: bindHost,
        pid: state.child?.pid ?? null,
        restarts: state.restarts,
        lastError: state.lastError,
        lastExitAt: state.lastExitAt,
        script: gatewayScript,
        health: gateway.health,
      },
      host: { gatewayVersion: GATEWAY_VERSION, upstreamBase },
      upstream: {
        base: upstreamBase,
        cookiePresent: Boolean(readTextFile(path.join(home, 'upstream.cookie'))),
        cookieAt: state.cookieAt || null,
        error: state.cookieError,
      },
      config: { dir: home, mustChangePassword: changePassword, dirMode: configDirMode() },
      initialPassword: changePassword ? readInitialPassword() : null,
      // 流中心状态（长轮询主通道）与设备计数（面板/排查用）
      streamHub: adminOverview?.streamHub ?? { connected: false, streamCount: 0, lastError: null },
      devices: adminOverview?.devices ?? { total: 0, online: 0 },
      lan: lanAddresses(),
      remoteAccess: {
        gatewayPort,
        note: '在 Cloudflare 控制台把域名（如 https://dsh.example.com）转发到本机 8790 端口即可；网关不管理 Cloudflare/cloudflared，也不保存任何隧道凭据。',
      },
      logs: state.childLogs.slice(-100),
    }
  }

  async function handleMobileApi(req, res, url) {
    // 面板接口只允许 DSH 同源会话调用（webServer 路由不经过 Connection 栅栏，需显式校验）。
    const rejection = typeof ctx.connection?.requestRejection === 'function' ? ctx.connection.requestRejection(req) : undefined
    if (rejection) return safeJson(res, rejection, { error: 'unauthorized' })
    if (typeof ctx.connection?.requestRejection !== 'function') {
      const remote = String(req.socket?.remoteAddress || '')
      const loopback = remote === '127.0.0.1' || remote === '::1' || remote === '::ffff:127.0.0.1'
      if (!loopback) return safeJson(res, 403, { error: 'forbidden' })
    }
    const pathname = url.pathname
    if (pathname === '/mobile/api/status' && req.method === 'GET') {
      return safeJson(res, 200, await statusPayload())
    }
    if (pathname === '/mobile/api/session-file' && (req.method === 'GET' || req.method === 'HEAD')) {
      return serveSessionFile(ctx, req, res, url)
    }
    if (pathname === '/mobile/api/refresh-cookie' && req.method === 'POST') {
      const ok = await refreshCookie().catch(() => false)
      return safeJson(res, ok ? 200 : 502, { ok, cookieAt: state.cookieAt || null, error: state.cookieError })
    }
    if (pathname === '/mobile/api/restart-gateway' && req.method === 'POST') {
      await restartGateway()
      return safeJson(res, 200, { ok: true })
    }
    if (pathname === '/mobile/api/devices' && req.method === 'GET') {
      const result = await gatewayAdminFetch('/api/admin/devices')
      if (result.status === 200) return safeJson(res, 200, result.json)
      if (result.status === 0) return safeJson(res, 502, { error: 'gateway-unavailable' })
      return safeJson(res, 502, { error: 'gateway-rejected-admin' })
    }
    if (pathname === '/mobile/api/devices/revoke' && req.method === 'POST') {
      const body = await readBodyJson(req)
      if (!body || typeof body.id !== 'string' || !body.id) return safeJson(res, 400, { error: 'bad-request' })
      const result = await gatewayAdminFetch('/api/admin/devices/revoke', { method: 'POST', body: { id: body.id } })
      if (result.status === 200) return safeJson(res, 200, result.json)
      if (result.status === 404) return safeJson(res, 404, { error: 'device-not-found' })
      if (result.status === 0) return safeJson(res, 502, { error: 'gateway-unavailable' })
      return safeJson(res, 502, { error: 'gateway-rejected-admin' })
    }
    if (pathname === '/mobile/api/logs' && req.method === 'GET') {
      return safeJson(res, 200, { lines: state.childLogs.slice(-100) })
    }
    return safeJson(res, 404, { error: 'not-found' })
  }

  function mobileHandler(req, res) {
    let url
    try {
      url = new URL(req.url || '/mobile', 'http://localhost')
    } catch {
      return safeJson(res, 400, { error: 'bad-request' })
    }
    const pathname = url.pathname
    if (pathname.startsWith('/mobile/api/')) {
      handleMobileApi(req, res, url).catch(() => safeJson(res, 500, { error: 'internal-error' }))
      return
    }
    if (pathname === '/mobile' || pathname === '/mobile/' || pathname === '/mobile/index.html') {
      return safeFile(res, path.join(PLUGIN_DIR, 'admin.html'), 'text/html; charset=utf-8')
    }
    if (pathname === '/mobile/admin.js') {
      return safeFile(res, path.join(PLUGIN_DIR, 'admin.js'), 'application/javascript; charset=utf-8')
    }
    return safeJson(res, 404, { error: 'not-found' })
  }

  // 生命周期：先兑换 Cookie，再拉起并守护网关；卸载时停止。
  ctx.effect(() => {
    state.stopped = false
    refreshCookie()
      .catch(() => false)
      .then((ok) => {
        if (!state.stopped) {
          startGateway()
          scheduleCookieRefresh(ok ? COOKIE_REFRESH_MS : COOKIE_RETRY_MS)
        }
      })
    return () => {
      stopGateway()
    }
  }, 'dsh-mobile: gateway lifecycle')

  // /mobile 面板与 /mobile/api/*
  ctx.effect(
    () =>
      ctx.webServer.register({
        kind: 'prefix',
        path: '/mobile',
        handler: mobileHandler,
      }),
    'dsh-mobile: /mobile panel',
  )

  log.info(`dsh-mobile 插件已加载（网关端口 ${gatewayPort}，上游 ${upstreamBase}）`)
}
