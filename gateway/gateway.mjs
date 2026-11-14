/**
 * gateway/gateway.mjs — dsh-mobile 网关入口（零 npm 依赖，默认 0.0.0.0:8790）。
 *
 * v3 形态（SPEC v3 / docs/interfaces.md v3）：
 *  - 口令登录 + 设备 token + 限速 + WS ticket；
 *  - POST /api/rpc → 上游现代协议（方法白名单，只透传 args）；
 *  - 流中心：单条上游 WS + 逻辑流 + HTTP 长轮询（/api/stream/open|poll|cancel|streams），
 *    WS /api/ws 作为可选实时通道；
 *  - /api/pending + /api/respond（审批/提问，outcome 为任意 JSON 值）；
 *  - /api/files/*（realpath 根校验 / Range / 2GiB 上传）；
 *  - 不做 Cloudflare/cloudflared 管理；接受任意 Host 与 X-Forwarded-*（供用户自建通道反代）。
 */
import http from 'node:http'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { pathToFileURL } from 'node:url'
import { AuthService, timingSafeEqualString } from './auth.mjs'
import {
  DEFAULT_PORT,
  VERSION,
  loadConfig,
  readInitialPassword,
  removeInitialPassword,
  resolveBindHost,
  resolveHome,
  resolveRoots,
  saveConfig,
} from './config.mjs'
import { createUpstream, PendingStore, RPC_ALLOWLIST, STREAM_ALLOWLIST, UpstreamError, validateClientFrame } from './upstream.mjs'
import { StreamHub } from './streams.mjs'
import { FileService, sendError as sendFileError } from './files.mjs'
import { createLogger, silentLogger } from './log.mjs'
import { acceptUpgrade, rejectUpgrade } from './ws.mjs'
import { createProxyTrust } from './proxy-trust.mjs'

const JSON_BODY_LIMIT = 1024 * 1024
// /api/rpc 需要承载 App 内联图片（单图 base64 ≤3.5MB × 最多 4 张），单独放宽到 32 MiB；
// 其他端点保持小上限，避免任意 JSON 端点被大 body 打爆。
const RPC_BODY_LIMIT = 32 * 1024 * 1024
const SMALL_BODY_LIMIT = 16 * 1024

class HttpError extends Error {
  constructor(status, code) {
    super(code)
    this.status = status
    this.code = code
  }
}

function sendJson(res, status, payload, headers = {}) {
  if (res.headersSent || res.writableEnded) return
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    ...headers,
  })
  res.end(body)
}

function sendHtml(res, status, html) {
  if (res.headersSent || res.writableEnded) return
  res.writeHead(status, {
    'content-type': 'text/html; charset=utf-8',
    'content-length': Buffer.byteLength(html),
    'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  })
  res.end(html)
}

/** 统一安全头：反代/HTTP/长轮询/WS 握手都适用。 */
function applySecurityHeaders(res) {
  res.setHeader('x-content-type-options', 'nosniff')
  res.setHeader('referrer-policy', 'no-referrer')
  res.setHeader('cache-control', 'no-store')
  res.setHeader('x-frame-options', 'DENY')
}

function bearerToken(req) {
  const header = req.headers.authorization
  if (typeof header !== 'string') return null
  const match = /^Bearer\s+(.+)$/i.exec(header.trim())
  return match ? match[1].trim() : null
}

function readBody(req, limit) {
  // 非法 Content-Length 直接 400；声明超限直接 413（不读完整个 body）。
  const declared = req.headers['content-length']
  if (declared !== undefined) {
    const text = Array.isArray(declared) ? declared[0] : String(declared)
    if (!/^\d+$/.test(text)) {
      req.resume()
      return Promise.reject(new HttpError(400, 'bad-request'))
    }
    if (Number(text) > limit) {
      req.resume()
      return Promise.reject(new HttpError(413, 'body-too-large'))
    }
  }
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > limit) {
        // 不 destroy 连接：让响应能正常写回 413；后续数据丢弃。
        req.removeAllListeners('data')
        req.resume()
        reject(new HttpError(413, 'body-too-large'))
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', (err) => reject(err))
    req.on('aborted', () => reject(new HttpError(400, 'request-aborted')))
  })
}

async function readJsonBody(req, limit = JSON_BODY_LIMIT) {
  const buf = await readBody(req, limit)
  if (!buf.length) return {}
  let parsed
  try {
    parsed = JSON.parse(buf.toString('utf8'))
  } catch {
    throw new HttpError(400, 'bad-request')
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new HttpError(400, 'bad-request')
  return parsed
}

function isTrueParam(value) {
  return value === '1' || value === 'true' || value === 'yes'
}

/**
 * 创建网关（不自动监听）：返回 {server, port, host, home, config, auth, upstream, streams, pending, files, close, url, initialPassword}。
 * options: home/env/port/host/fetchImpl/connectImpl/now/ticketSecret/authLimits/hubOptions/logger/adminToken/disableStreams。
 */
export async function createGateway(options = {}) {
  const env = options.env || process.env
  const logger = options.logger || createLogger({ level: env.DSH_MOBILE_LOG_LEVEL || 'info' })
  const home = options.home || resolveHome(env)
  const { config, created, initialPassword: createdPassword } = loadConfig(home, { env })
  const persist = () => saveConfig(home, config)

  const auth = new AuthService({
    config,
    persist,
    now: options.now,
    secret: options.ticketSecret,
    limits: options.authLimits || {},
  })

  const upstream = createUpstream({
    home,
    env,
    fetchImpl: options.fetchImpl,
    connectImpl: options.connectImpl,
    logger: logger.child('upstream'),
  })

  const files = new FileService({
    roots: resolveRoots({ home, config, env }),
    home: env.HOME || undefined,
    logger: logger.child('files'),
  })

  const pending = new PendingStore({ upstream, logger: logger.child('pending') })

  let hub = null
  if (!options.disableStreams) {
    hub = new StreamHub({
      upstream,
      logger: logger.child('streams'),
      ...(options.hubOptions || {}),
    })
    hub.onDisconnect(() => pending.markDisconnected())
    // 内部长期订阅 $events：为 pending 缓存 waterfall 帧（含重连后的 ready/waterfall 重放）。
    hub.register({ endpoint: '$events', args: {}, internal: true, streamId: '$events', onItem: (value) => pending.handleValue(value) })
  }

  // 反代可信边界：默认只信回环直连，可用 DSH_MOBILE_TRUST_PROXY_CIDRS 追加可信代理网段。
  // 不可信直连一律忽略 cf-connecting-ip / X-Forwarded-For，防止伪造 IP 绕过登录限速。
  const proxyTrust = options.proxyTrust || createProxyTrust({ env, logger })
  const adminToken = options.adminToken ?? (env.DSH_MOBILE_ADMIN_TOKEN || null)
  const upgradeSockets = new Set()
  // deviceId -> Set<WsConnection>：撤销/登出时关闭该设备已建立的 /api/ws 连接。
  const deviceWebSockets = new Map()

  const trackDeviceSocket = (deviceId, conn) => {
    let set = deviceWebSockets.get(deviceId)
    if (!set) {
      set = new Set()
      deviceWebSockets.set(deviceId, set)
    }
    set.add(conn)
    conn.on('close', () => {
      const current = deviceWebSockets.get(deviceId)
      if (!current) return
      current.delete(conn)
      if (!current.size) deviceWebSockets.delete(deviceId)
    })
  }

  /** 关闭某设备所有已建立 WS（4401 = 凭据已失效）。 */
  const closeDeviceWebSockets = (deviceId, reason = 'device-revoked') => {
    const set = deviceWebSockets.get(deviceId)
    if (!set || !set.size) return 0
    const count = set.size
    for (const conn of [...set]) {
      try {
        conn.close(4401, reason)
      } catch {
        /* ignore */
      }
    }
    return count
  }
  const stopping = { value: false }

  const server = http.createServer((req, res) => {
    applySecurityHeaders(res)
    handleHttp(req, res).catch((err) => {
      if (err instanceof HttpError) {
        return sendJson(res, err.status, { error: err.code })
      }
      if (err instanceof UpstreamError) {
        return sendJson(res, 502, { error: 'upstream-unavailable' })
      }
      logger.error('request handler failed', { code: err?.code || 'internal-error' })
      if (!res.headersSent) sendJson(res, 500, { error: 'internal-error' })
      else {
        try {
          res.destroy()
        } catch {
          /* ignore */
        }
      }
    })
  })

  server.on('connection', (socket) => {
    socket.on('error', () => {})
  })

  server.on('upgrade', (req, socket, head) => {
    handleUpgrade(req, socket, head).catch(() => {
      try {
        rejectUpgrade(socket, 500, 'Internal Server Error')
      } catch {
        /* ignore */
      }
    })
  })

  /** 鉴权：返回 {device, token}，失败已写 401 并返回 null。 */
  function requireDevice(req, res) {
    const token = bearerToken(req)
    const device = token ? auth.authenticate(token) : null
    if (!device) {
      sendJson(res, 401, { error: 'unauthorized' })
      return null
    }
    return { device, token }
  }

  /**
   * 需要“已完成改密”的设备：mustChangePassword=true 时除 me/logout/password 外的
   * /api/*（rpc/stream/files/pending/respond/devices/ws-ticket）返回 403，服务端强制而非仅客户端提示。
   */
  function requireReadyDevice(req, res) {
    const session = requireDevice(req, res)
    if (!session) return null
    if (config.mustChangePassword === true) {
      sendJson(res, 403, { error: 'password-change-required' })
      return null
    }
    return session
  }

  /** 插件侧管理接口鉴权（x-admin-token，来自 spawn 时注入的 env，不暴露给 App）。 */
  function requireAdmin(req, res) {
    const provided = String(req.headers['x-admin-token'] || '')
    if (!adminToken || !timingSafeEqualString(provided, adminToken)) {
      sendJson(res, 401, { error: 'unauthorized' })
      return false
    }
    return true
  }

  /** 设备列表（面板用）：online = 最近 60 秒内有活跃请求；不返回 tokenHash。 */
  function adminDeviceList() {
    const now = typeof auth.now === 'function' ? auth.now() : Date.now()
    return auth
      .listDevices()
      .map((device) => ({ ...device, online: now - Number(device.lastSeenAt || 0) < 60_000 }))
      .sort((a, b) => Number(b.lastSeenAt || 0) - Number(a.lastSeenAt || 0))
  }

  function hostInfo() {
    return { version: null, gatewayVersion: VERSION }
  }

  function healthPayload() {
    return {
      ok: true,
      service: 'dsh-mobile',
      version: VERSION,
      // WS hub 已连接即代表上游可用（空闲 >60s 时 upstream.healthy 会回落，但流中心仍在线）。
      upstream: { ok: upstream.healthy || Boolean(hub?.connected) },
      mustChangePassword: config.mustChangePassword === true,
      proxyTrust: proxyTrust.describe(),
    }
  }

  /**
   * GET/HEAD /api/attachment?sessionId=&attachmentId= → 代理 DSH 插件 /mobile/api/session-file。
   *
   * 图片/文件块是 attachment 引用，DSH 的 session/attachment RPC 经 HTTP 是 multipart，
   * 普通 JSON 代理拿不到字节；这里用上游 Cookie 请求同进程插件路由并把字节流原样透传。
   * 只透传上游的 content-type/content-length，不把客户端的 Authorization/Cookie 发给上游。
   */
  async function proxyAttachment(req, res, url) {
    const sessionId = url.searchParams.get('sessionId') || ''
    const attachmentId = url.searchParams.get('attachmentId') || ''
    if (!sessionId || sessionId.length > 200 || /[\u0000-\u001f]/.test(sessionId) || !/^[a-f0-9]{64}$/.test(attachmentId)) {
      return sendJson(res, 400, { error: 'invalid-reference' })
    }
    const cookie = upstream.cookie()
    if (!cookie) return sendJson(res, 502, { error: 'upstream-unavailable' })
    const base = upstream.baseUrl()
    const target =
      `${base}/mobile/api/session-file?sessionId=${encodeURIComponent(sessionId)}` +
      `&attachmentId=${encodeURIComponent(attachmentId)}`

    const controller = new AbortController()
    const onClose = () => controller.abort()
    res.once('close', onClose)
    // 只限制“拿到响应头”的时间；拿到后按客户端消费速度流式传输。
    const headerTimer = setTimeout(() => controller.abort(new Error('attachment-timeout')), 20_000)
    headerTimer.unref?.()
    let response
    try {
      response = await fetch(target, {
        method: req.method === 'HEAD' ? 'HEAD' : 'GET',
        headers: { cookie, accept: '*/*' },
        redirect: 'manual',
        signal: controller.signal,
      })
    } catch (err) {
      clearTimeout(headerTimer)
      res.off('close', onClose)
      logger.warn('attachment proxy failed', { code: err?.code || 'network' })
      return sendJson(res, 502, { error: 'upstream-unavailable' })
    }
    clearTimeout(headerTimer)

    if (response.status !== 200) {
      try {
        await response.arrayBuffer()
      } catch {
        /* drain */
      }
      res.off('close', onClose)
      if (response.status === 400) return sendJson(res, 400, { error: 'invalid-reference' })
      if (response.status === 404) return sendJson(res, 404, { error: 'attachment-not-found' })
      if (response.status === 503) return sendJson(res, 503, { error: 'attachment-unsupported' })
      return sendJson(res, 502, { error: 'attachment-unavailable' })
    }

    const headers = { 'cache-control': 'private, no-store', 'x-content-type-options': 'nosniff' }
    const contentType = response.headers.get('content-type')
    if (contentType) headers['content-type'] = contentType
    const contentLength = response.headers.get('content-length')
    if (contentLength) headers['content-length'] = contentLength
    res.writeHead(200, headers)
    if (req.method === 'HEAD' || !response.body) {
      try {
        await response.body?.cancel?.()
      } catch {
        /* ignore */
      }
      res.off('close', onClose)
      return res.end()
    }
    try {
      await pipeline(Readable.fromWeb(response.body), res)
    } catch (err) {
      // 客户端中断/上游流失败：连接已不可用，记录类型即可。
      logger.warn('attachment stream interrupted', { code: err?.code || 'stream-error' })
      try {
        res.destroy()
      } catch {
        /* ignore */
      }
    } finally {
      res.off('close', onClose)
    }
    return undefined
  }

  async function handleHttp(req, res) {
    let url
    try {
      url = new URL(req.url || '/', 'http://localhost')
    } catch {
      return sendJson(res, 400, { error: 'bad-request' })
    }
    const method = req.method || 'GET'
    const pathname = url.pathname

    // ---- 无需鉴权 ----
    if ((pathname === '/health' || pathname === '/api/health') && method === 'GET') {
      return sendJson(res, 200, healthPayload())
    }
    if (pathname === '/' && method === 'GET') {
      const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>dsh-mobile</title></head><body style="margin:0;padding:32px;font:15px/1.6 system-ui,sans-serif;color:#1a1a1a;background:#fff"><h1 style="font-size:17px;font-weight:600;margin:0 0 8px">dsh-mobile</h1><p style="color:#8b8b8b;margin:0 0 4px">DSH 移动端网关</p><p style="margin:0 0 4px">service: dsh-mobile · version: ${VERSION}</p><p style="margin:0;color:${upstream.healthy ? '#1a7f37' : '#8b8b8b'}">upstream: ${upstream.healthy ? 'ready' : 'unknown'}</p></body></html>`
      return sendHtml(res, 200, html)
    }
    if (pathname === '/api/login' && method === 'POST') {
      const body = await readJsonBody(req, SMALL_BODY_LIMIT)
      const result = await auth.login({
        password: typeof body.password === 'string' ? body.password : '',
        deviceName: body.deviceName,
        platform: body.platform || req.headers['x-dsh-mobile-platform'] || 'android',
        ip: proxyTrust.resolve(req).ip,
      })
      if (!result.ok) {
        if (result.status === 429) {
          return sendJson(res, 429, { error: 'rate-limited', retryAfterMs: result.retryAfterMs }, {
            'retry-after': String(Math.max(1, Math.ceil((result.retryAfterMs || 0) / 1000))),
          })
        }
        if (result.status === 423) return sendJson(res, 423, { error: 'locked', until: result.until })
        return sendJson(res, 401, { error: 'invalid-password' })
      }
      logger.info('device login', { ip: proxyTrust.resolve(req).ip })
      return sendJson(res, 200, {
        token: result.token,
        deviceId: result.device.id,
        deviceName: result.device.name,
        mustChangePassword: config.mustChangePassword === true,
        host: hostInfo(),
      })
    }

    // ---- 以下全部需要设备 token ----
    if (pathname === '/api/me' && method === 'GET') {
      const session = requireDevice(req, res)
      if (!session) return
      return sendJson(res, 200, { device: session.device, host: hostInfo() })
    }
    if (pathname === '/api/logout' && method === 'POST') {
      const session = requireDevice(req, res)
      if (!session) return
      const deviceId = session.device.id
      auth.logout(session.token)
      hub?.cancelByOwner(deviceId)
      closeDeviceWebSockets(deviceId, 'logged-out')
      return sendJson(res, 200, { ok: true })
    }
    if (pathname === '/api/password' && method === 'POST') {
      const session = requireDevice(req, res)
      if (!session) return
      const body = await readJsonBody(req, SMALL_BODY_LIMIT)
      if (typeof body.current !== 'string' || typeof body.next !== 'string') {
        return sendJson(res, 400, { error: 'bad-request' })
      }
      // 失败复用登录限速/锁定（按解析后客户端 IP，5 次/分钟 → 429；连续失败 → 423）；
      // 同一设备并发改密由 AuthService 重入保护返回 409。
      const result = await auth.changePassword(body.current, body.next, {
        ip: proxyTrust.resolve(req).ip,
        deviceId: session.device.id,
      })
      if (!result.ok) {
        if (result.status === 429) {
          return sendJson(res, 429, { error: 'rate-limited', retryAfterMs: result.retryAfterMs }, {
            'retry-after': String(Math.max(1, Math.ceil((result.retryAfterMs || 0) / 1000))),
          })
        }
        if (result.status === 423) return sendJson(res, 423, { error: 'locked', until: result.until })
        return sendJson(res, result.status || 400, { error: result.error || 'bad-request' })
      }
      removeInitialPassword(home)
      logger.info('admin password changed')
      return sendJson(res, 200, { ok: true })
    }
    if (pathname === '/api/devices' && method === 'GET') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      return sendJson(res, 200, { items: auth.listDevices() })
    }
    if (pathname.startsWith('/api/devices/') && method === 'DELETE') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      const id = decodeURIComponent(pathname.slice('/api/devices/'.length))
      if (!id || id.includes('/')) return sendJson(res, 404, { error: 'not-found' })
      const removed = auth.revokeDevice(id)
      if (!removed) return sendJson(res, 404, { error: 'not-found' })
      hub?.cancelByOwner(id)
      closeDeviceWebSockets(id, 'device-revoked')
      return sendJson(res, 200, { ok: true })
    }
    if (pathname === '/api/ws-ticket' && (method === 'GET' || method === 'POST')) {
      const session = requireReadyDevice(req, res)
      if (!session) return
      const ticket = auth.issueTicket(session.device.id)
      return sendJson(res, 200, ticket)
    }
    if (pathname === '/api/rpc' && method === 'POST') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      const body = await readJsonBody(req, RPC_BODY_LIMIT)
      if (typeof body.method !== 'string') return sendJson(res, 400, { error: 'bad-request' })
      if (!RPC_ALLOWLIST.has(body.method)) return sendJson(res, 403, { error: 'method-not-allowed' })
      const args = body.args && typeof body.args === 'object' ? body.args : {}
      const result = await upstream.rpc(body.method, args)
      return sendJson(res, 200, result)
    }

    // ---- 流中心（HTTP 长轮询为主） ----
    if (pathname === '/api/stream/open' && method === 'POST') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      if (!hub) return sendJson(res, 503, { error: 'streams-unavailable' })
      const body = await readJsonBody(req, SMALL_BODY_LIMIT)
      if (typeof body.endpoint !== 'string' || !STREAM_ALLOWLIST.has(body.endpoint)) {
        return sendJson(res, 403, { error: 'method-not-allowed' })
      }
      const args = body.args && typeof body.args === 'object' ? body.args : {}
      let stream
      try {
        stream = hub.register({ endpoint: body.endpoint, args, ownerDeviceId: session.device.id })
      } catch (err) {
        if (err.message === 'too-many-streams' || err.message === 'too-many-device-streams') {
          return sendJson(res, 429, { error: 'too-many-streams' })
        }
        return sendJson(res, 400, { error: 'bad-request' })
      }
      return sendJson(res, 200, { streamId: stream.streamId })
    }
    if (pathname === '/api/stream/poll' && method === 'GET') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      if (!hub) return sendJson(res, 503, { error: 'streams-unavailable' })
      const streamId = url.searchParams.get('streamId') || ''
      const since = Number(url.searchParams.get('since') || 0)
      const waitParam = url.searchParams.get('wait')
      const waitMs = waitParam === null ? 25_000 : Number(waitParam)
      if (!streamId) return sendJson(res, 400, { error: 'bad-request' })
      const result = await hub.poll(streamId, {
        since: Number.isFinite(since) && since >= 0 ? since : 0,
        waitMs: Number.isFinite(waitMs) && waitMs >= 0 ? waitMs : 25_000,
        ownerDeviceId: session.device.id,
      })
      // 不属于该设备的流 / 内部流 / 不存在：统一 404，不泄露存在性。
      if (result.error?.code === 'stream-not-found') return sendJson(res, 404, { error: 'stream-not-found' })
      return sendJson(res, 200, result)
    }
    if (pathname === '/api/stream/cancel' && method === 'POST') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      if (!hub) return sendJson(res, 503, { error: 'streams-unavailable' })
      const body = await readJsonBody(req, SMALL_BODY_LIMIT)
      if (typeof body.streamId !== 'string' || !body.streamId) return sendJson(res, 400, { error: 'bad-request' })
      const closed = hub.cancel(body.streamId, { ownerDeviceId: session.device.id })
      if (!closed) return sendJson(res, 404, { error: 'stream-not-found' })
      return sendJson(res, 200, { ok: true })
    }
    if (pathname === '/api/streams' && method === 'GET') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      if (!hub) return sendJson(res, 503, { error: 'streams-unavailable' })
      return sendJson(res, 200, { items: hub.list({ ownerDeviceId: session.device.id }), connected: hub.connected })
    }

    // ---- 待处理审批/提问 ----
    if (pathname === '/api/pending' && method === 'GET') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      return sendJson(res, 200, { items: pending.pending() })
    }
    if (pathname === '/api/respond' && method === 'POST') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      const body = await readJsonBody(req, SMALL_BODY_LIMIT)
      if (typeof body.eventId !== 'string' || !body.eventId) return sendJson(res, 400, { error: 'bad-request' })
      if (!Object.hasOwn(body, 'outcome')) return sendJson(res, 400, { error: 'bad-request' })
      const result = await pending.respond(body.eventId, body.outcome)
      return sendJson(res, 200, result)
    }

    // ---- 文件 ----
    if (pathname === '/api/files' && method === 'GET') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      try {
        return sendJson(res, 200, files.list(url.searchParams.get('path') ?? undefined))
      } catch (err) {
        return sendFileError(res, err)
      }
    }
    if (pathname === '/api/files/download' && (method === 'GET' || method === 'HEAD')) {
      const session = requireReadyDevice(req, res)
      if (!session) return
      return files.handleDownload(req, res, url)
    }
    if (pathname === '/api/files/upload' && method === 'POST') {
      const session = requireReadyDevice(req, res)
      if (!session) return
      return files.handleUpload(req, res, url)
    }
    if (pathname === '/api/files/preview' && (method === 'GET' || method === 'HEAD')) {
      const session = requireReadyDevice(req, res)
      if (!session) return
      return files.handlePreview(req, res, url)
    }

    // ---- 附件字节（图片/文件引用） ----
    if (pathname === '/api/attachment' && (method === 'GET' || method === 'HEAD')) {
      const session = requireReadyDevice(req, res)
      if (!session) return
      return proxyAttachment(req, res, url)
    }

    // ---- 插件侧管理接口（x-admin-token，不暴露给 App） ----
    if (pathname === '/api/admin/devices' && method === 'GET') {
      if (!requireAdmin(req, res)) return
      return sendJson(res, 200, { ok: true, items: adminDeviceList() })
    }
    if (pathname === '/api/admin/devices/revoke' && method === 'POST') {
      if (!requireAdmin(req, res)) return
      const body = await readJsonBody(req, SMALL_BODY_LIMIT)
      if (typeof body.id !== 'string' || !body.id) return sendJson(res, 400, { error: 'bad-request' })
      const removed = auth.revokeDevice(body.id)
      if (!removed) return sendJson(res, 404, { error: 'device-not-found' })
      hub?.cancelByOwner(body.id)
      const wsClosed = closeDeviceWebSockets(body.id, 'device-revoked')
      logger.info('device revoked from plugin panel', { wsClosed })
      return sendJson(res, 200, { ok: true, revoked: true })
    }
    if (pathname === '/api/admin/overview' && method === 'GET') {
      if (!requireAdmin(req, res)) return
      const devices = adminDeviceList()
      return sendJson(res, 200, {
        ok: true,
        streamHub: {
          connected: Boolean(hub?.connected),
          streamCount: hub ? hub.streamCount : 0,
          lastError: hub?.lastError ?? null,
        },
        devices: { total: devices.length, online: devices.filter((device) => device.online).length },
        upstream: { ok: upstream.healthy || Boolean(hub?.connected), lastError: upstream.lastError ?? null },
      })
    }
    if (pathname === '/api/admin/restart-gateway' && method === 'POST') {
      if (!requireAdmin(req, res)) return
      sendJson(res, 200, { ok: true })
      logger.info('gateway restart requested by plugin')
      setTimeout(() => process.exit(0), 100).unref?.()
      return
    }

    if (pathname.startsWith('/api/')) return sendJson(res, 404, { error: 'not-found' })
    return sendJson(res, 404, { error: 'not-found' })
  }

  async function handleUpgrade(req, socket, head) {
    let url
    try {
      url = new URL(req.url || '/', 'http://localhost')
    } catch {
      return rejectUpgrade(socket, 400, 'Bad Request')
    }
    if (url.pathname !== '/api/ws') return rejectUpgrade(socket, 404, 'Not Found')
    if (!hub) return rejectUpgrade(socket, 503, 'Service Unavailable')
    const deviceId = auth.verifyTicket(url.searchParams.get('ticket') || '')
    if (!deviceId) return rejectUpgrade(socket, 401, 'Unauthorized')
    if (config.mustChangePassword === true) return rejectUpgrade(socket, 403, 'Password Change Required')
    let conn
    try {
      conn = acceptUpgrade(req, socket, head)
    } catch {
      return
    }
    upgradeSockets.add(socket)
    socket.on('close', () => upgradeSockets.delete(socket))
    trackDeviceSocket(deviceId, conn)
    const subscriptions = new Map() // app streamId -> {streamId, off}
    const subsDelete = (appStreamId) => {
      const sub = subscriptions.get(appStreamId)
      if (!sub) return
      sub.off()
      subscriptions.delete(appStreamId)
    }
    const closeAll = () => {
      for (const sub of subscriptions.values()) {
        sub.off()
        hub.cancel(sub.streamId)
      }
      subscriptions.clear()
      upgradeSockets.delete(socket)
    }
    conn.on('text', (text) => {
      const check = validateClientFrame(text)
      if (!check.ok) {
        conn.sendText(JSON.stringify({ type: 'error', streamId: check.streamId ?? null, error: { code: check.code, message: check.message } }))
        return
      }
      let message
      try {
        message = JSON.parse(text)
      } catch {
        return
      }
      if (message.type === 'open') {
        if (subscriptions.has(message.streamId)) return
        // 每次 open 复查设备：已撤销/过期则直接关闭连接，不允许再开新流。
        if (typeof auth.isDeviceActive === 'function' ? !auth.isDeviceActive(deviceId) : !auth.findDevice(deviceId)) {
          conn.sendText(JSON.stringify({ type: 'error', streamId: message.streamId, error: { code: 'unauthorized', message: 'device revoked' } }))
          conn.close(4401, 'device-revoked')
          return
        }
        let stream
        try {
          stream = hub.register({ endpoint: message.endpoint, args: message.payload?.args || {}, ownerDeviceId: deviceId })
        } catch {
          conn.sendText(JSON.stringify({ type: 'error', streamId: message.streamId, error: { code: 'stream-open-failed', message: 'failed to open stream' } }))
          return
        }
        const off = hub.subscribe(stream.streamId, (event) => {
          if (event.type === 'item') {
            conn.sendText(JSON.stringify({ type: 'item', streamId: message.streamId, value: event.value }))
          } else if (event.type === 'error') {
            conn.sendText(JSON.stringify({ type: 'error', streamId: message.streamId, error: event.error }))
          } else if (event.type === 'close') {
            subsDelete(message.streamId)
            conn.sendText(
              JSON.stringify({ type: 'error', streamId: message.streamId, error: { code: 'stream-closed', message: 'stream closed' } }),
            )
          }
        })
        subscriptions.set(message.streamId, { streamId: stream.streamId, off })
      } else if (message.type === 'cancel') {
        const sub = subscriptions.get(message.streamId)
        if (sub) {
          sub.off()
          hub.cancel(sub.streamId)
          subscriptions.delete(message.streamId)
        }
      }
    })
    conn.on('close', closeAll)
    conn.on('error', closeAll)
  }

  const listenPort = options.port ?? (Number(env.DSH_MOBILE_GATEWAY_PORT) || DEFAULT_PORT)
  const listenHost = resolveBindHost(env, options.host)
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(listenPort, listenHost, () => {
      server.off('error', reject)
      resolve()
    })
  })
  const actualPort = server.address().port
  hub?.start()

  function close() {
    if (stopping.value) return Promise.resolve()
    stopping.value = true
    hub?.stop()
    for (const socket of upgradeSockets) {
      try {
        socket.destroy()
      } catch {
        /* ignore */
      }
    }
    upgradeSockets.clear()
    return new Promise((resolve) => {
      server.close(() => resolve())
      server.closeAllConnections?.()
    })
  }

  return {
    server,
    host: listenHost,
    port: actualPort,
    home,
    config,
    auth,
    upstream,
    streams: hub,
    pending,
    files,
    close,
    url: `http://${listenHost === '0.0.0.0' ? '127.0.0.1' : listenHost.includes(':') ? `[${listenHost}]` : listenHost}:${actualPort}`,
    initialPassword: created ? createdPassword : readInitialPassword(home),
    created,
  }
}

/** CLI 入口：node gateway/gateway.mjs */
export async function startGatewayFromEnv(env = process.env) {
  const logger = createLogger({ level: env.DSH_MOBILE_LOG_LEVEL || 'info' })
  const gateway = await createGateway({ env, logger })
  logger.info(`gateway listening on ${gateway.host}:${gateway.port} (config ${gateway.home})`)
  if (gateway.created) logger.info('first run: admin password generated (see initial-password.txt in config dir / DSH plugin panel)')
  let closing = false
  const shutdown = async (signal) => {
    if (closing) return
    closing = true
    logger.info('shutting down', { signal })
    try {
      await gateway.close()
    } finally {
      process.exit(0)
    }
  }
  process.on('SIGINT', () => shutdown('SIGINT'))
  process.on('SIGTERM', () => shutdown('SIGTERM'))
  process.on('uncaughtException', (err) => {
    logger.error('uncaught exception', { code: err?.code || 'internal-error' })
    process.exit(1)
  })
  process.on('unhandledRejection', () => {
    logger.error('unhandled rejection')
    process.exit(1)
  })
  return gateway
}

const invokedDirectly = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (invokedDirectly) {
  startGatewayFromEnv(process.env).catch((err) => {
    process.stderr.write(`[dsh-mobile] gateway failed to start: ${err?.code || err?.message || 'error'}\n`)
    process.exit(1)
  })
}
