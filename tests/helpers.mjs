/**
 * tests/helpers.mjs — 测试夹具：临时 HOME、假上游（HTTP + /api/remote.mux WS）、网关启动、轮询等待。
 * 隔离：每个测试使用 mkdtemp 的临时目录作为 HOME / 配置目录，绝不触碰真实 ~/.dsh-mobile。
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { acceptUpgrade } from '../gateway/ws.mjs'
import { createGateway } from '../gateway/gateway.mjs'
import { writeUpstreamCookie } from '../gateway/config.mjs'
import { silentLogger } from '../gateway/log.mjs'

export function makeTempDir(prefix = 'dsh-mobile-test-') {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix))
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export async function waitFor(fn, { timeout = 4000, interval = 20, message = 'condition' } = {}) {
  const deadline = Date.now() + timeout
  let lastError
  while (Date.now() < deadline) {
    try {
      const value = await fn()
      if (value) return value
    } catch (err) {
      lastError = err
    }
    await sleep(interval)
  }
  throw new Error(`waitFor timeout: ${message}${lastError ? ` (${lastError.message})` : ''}`)
}

export async function readJson(response) {
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = null
  }
  return { status: response.status, headers: response.headers, json, text }
}

function defaultRpcResult(method) {
  if (method === 'session/list') {
    return { ok: true, value: { items: [{ id: 's1', title: '测试会话', updatedAt: 1 }] } }
  }
  if (method === 'session/modelCatalog') return { ok: true, value: { providers: [{ id: 'deepseek' }] } }
  if (method === '$events/result') return { ok: true, value: { accepted: true } }
  return { ok: true, value: { method } }
}

/**
 * 假上游 DSH：
 *  - POST /api/<method> 收到现代协议 client-request，按 rpcHandlers 返回 server-response；
 *  - WS /api/remote.mux 校验 Cookie，收到 open 后按 endpoint 自动回 ready/snapshot，
 *    支持 push/pushAll/closeAllConnections 模拟事件与断线。
 */
export async function startFakeUpstream(options = {}) {
  const cookie = options.cookie ?? 'dsh_session=fake-session'
  const rpcHandlers = options.rpcHandlers || {}
  const snapshotDelayMs = options.snapshotDelayMs ?? 0
  const eventsAfterReady = options.eventsAfterReady || []
  const state = {
    rpcCalls: [],
    opened: [],
    cancelled: [],
    connections: new Set(),
    streams: new Map(),
    clientCounter: 0,
    wsUpgrades: 0,
    wsCookie: null,
  }

  const server = http.createServer(async (req, res) => {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const raw = Buffer.concat(chunks).toString('utf8')
    let body = null
    try {
      body = raw ? JSON.parse(raw) : null
    } catch {
      body = null
    }
    const match = /^\/api\/(.+)$/.exec(req.url || '')
    const method = match ? decodeURIComponent(match[1]) : null
    const call = { method, body, cookie: req.headers.cookie || null, headers: req.headers }
    state.rpcCalls.push(call)
    const handler = rpcHandlers[method]
    let result
    if (typeof handler === 'function') {
      result = await handler(call)
    } else if (handler && typeof handler === 'object') {
      result = handler
    } else {
      result = defaultRpcResult(method)
    }
    const payload = { type: 'server-response', rpcId: body?.rpcId ?? null, result }
    const text = JSON.stringify(payload)
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(text) })
    res.end(text)
  })

  function sendTo(stream, value) {
    try {
      stream.conn.sendText(JSON.stringify({ type: 'item', streamId: stream.streamId, value }))
      return true
    } catch {
      return false
    }
  }

  server.on('upgrade', (req, socket, head) => {
    state.wsUpgrades += 1
    state.wsCookie = req.headers.cookie || null
    if (!(req.headers.cookie || '').includes(cookie)) {
      try {
        socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
      } catch {
        /* ignore */
      }
      return
    }
    let conn
    try {
      conn = acceptUpgrade(req, socket, head)
    } catch {
      return
    }
    state.connections.add(conn)
    conn.on('close', () => state.connections.delete(conn))
    conn.on('text', (text) => {
      let message
      try {
        message = JSON.parse(text)
      } catch {
        return
      }
      if (!message || typeof message !== 'object') return
      if (message.type === 'open') {
        const stream = {
          streamId: message.streamId,
          endpoint: message.endpoint,
          args: message.payload?.args ?? {},
          conn,
        }
        state.streams.set(message.streamId, stream)
        state.opened.push({ streamId: message.streamId, endpoint: message.endpoint, args: stream.args })
        const openHandler = options.onOpen
        if (typeof openHandler === 'function') {
          try {
            openHandler({ stream, send: (value) => sendTo(stream, value), state })
          } catch {
            /* ignore */
          }
        }
        if (message.endpoint === '$events') {
          state.clientCounter += 1
          sendTo(stream, {
            type: 'ready',
            clientId: `fake-client-${state.clientCounter}`,
            host: { home: '/tmp/home', cwd: '/tmp', version: '0.2.1-rc.2', attachedSessions: [] },
          })
          let index = 0
          for (const value of eventsAfterReady) {
            index += 1
            setTimeout(() => {
              if (state.streams.has(stream.streamId)) sendTo(stream, value)
            }, 20 * index).unref?.()
          }
        } else if (message.endpoint === 'session/follow') {
          const snapshot = {
            type: 'snapshot',
            header: { address: stream.args?.request?.address ?? null },
            cursor: 1,
            records: [{ type: 'event', event: { type: 'user/message', seq: 1, data: { content: [{ type: 'text', text: 'hi' }] } } }],
            hasMore: false,
            projections: {},
          }
          if (snapshotDelayMs > 0) {
            setTimeout(() => sendTo(stream, snapshot), snapshotDelayMs).unref?.()
          } else {
            sendTo(stream, snapshot)
          }
        }
        return
      }
      if (message.type === 'cancel') {
        state.cancelled.push(message.streamId)
        state.streams.delete(message.streamId)
      }
    })
  })

  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject)
      resolve()
    })
  })
  const port = server.address().port

  return {
    url: `http://127.0.0.1:${port}`,
    wsUrl: `ws://127.0.0.1:${port}/api/remote.mux`,
    cookie,
    state,
    get rpcCalls() {
      return state.rpcCalls
    },
    get opened() {
      return state.opened
    },
    lastRpc(method) {
      for (let i = state.rpcCalls.length - 1; i >= 0; i--) {
        if (state.rpcCalls[i].method === method) return state.rpcCalls[i]
      }
      return null
    },
    rpcCount(method) {
      return state.rpcCalls.filter((call) => call.method === method).length
    },
    /** 向某个已打开的流推一条 value。 */
    push(streamId, value) {
      const stream = state.streams.get(streamId)
      if (!stream) return false
      return sendTo(stream, value)
    },
    /** 向所有已打开流推一条 value。 */
    pushAll(value) {
      let sent = 0
      for (const stream of state.streams.values()) if (sendTo(stream, value)) sent += 1
      return sent
    },
    findStream(endpoint) {
      for (const stream of state.streams.values()) if (stream.endpoint === endpoint) return stream
      return null
    },
    /** 模拟上游 WS 断线（保留 HTTP 服务）。 */
    closeAllConnections() {
      for (const conn of state.connections) {
        try {
          conn.terminate()
        } catch {
          /* ignore */
        }
      }
      state.connections.clear()
      state.streams.clear()
    },
    async close() {
      for (const conn of state.connections) {
        try {
          conn.terminate()
        } catch {
          /* ignore */
        }
      }
      state.connections.clear()
      state.streams.clear()
      await new Promise((resolve) => {
        server.close(() => resolve())
        server.closeAllConnections?.()
      })
    },
  }
}

/** 启动一个隔离的网关实例（HOME=临时目录，绑定 127.0.0.1，随机端口）。 */
export async function startTestGateway(t, { upstream, env = {}, gatewayOptions = {} } = {}) {
  const home = makeTempDir()
  if (upstream) writeUpstreamCookie(home, upstream.cookie)
  const baseEnv = {
    HOME: home,
    ...(upstream ? { DSH_MOBILE_UPSTREAM: upstream.url } : {}),
    ...env,
  }
  const gateway = await createGateway({
    home,
    env: baseEnv,
    host: '127.0.0.1',
    port: 0,
    logger: silentLogger,
    ...gatewayOptions,
  })
  t.after(async () => {
    await gateway.close()
  })
  if (upstream) {
    t.after(async () => {
      await upstream.close()
    })
  }
  return { gateway, home, env: baseEnv }
}

export async function login(gateway, { password = gateway.initialPassword, deviceName = 'test-device', headers = {} } = {}) {
  const response = await fetch(`${gateway.url}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify({ password, deviceName }),
  })
  return readJson(response)
}

/**
 * 登录并（首次）完成强制改密，返回可正常调用业务 API 的 session。
 * 测试夹具约定：改密后的口令为 TEST_NEXT_PASSWORD，后续 loginReady 默认使用它。
 */
export const TEST_NEXT_PASSWORD = 'test-strong-password-1'

export async function loginReady(gateway, options = {}) {
  const password = options.password ?? gateway.__testPassword ?? gateway.initialPassword
  const session = await login(gateway, { ...options, password })
  if (session.status === 200 && session.json?.mustChangePassword) {
    const changed = await readJson(
      await api(gateway, session.json.token, '/api/password', {
        method: 'POST',
        body: { current: password, next: TEST_NEXT_PASSWORD },
      }),
    )
    if (changed.status !== 200) {
      throw new Error(`loginReady: change-password failed status=${changed.status}`)
    }
    gateway.__testPassword = TEST_NEXT_PASSWORD
  }
  return session
}

/** 带设备 token 的请求；body 为对象时自动 JSON 序列化。 */
export async function api(gateway, token, apiPath, init = {}) {
  const headers = { ...(init.headers || {}) }
  if (token) headers.authorization = `Bearer ${token}`
  let body = init.body
  if (body && typeof body !== 'string' && !Buffer.isBuffer(body)) {
    headers['content-type'] = 'application/json'
    body = JSON.stringify(body)
  }
  return fetch(`${gateway.url}${apiPath}`, { ...init, headers, body })
}

export function newDeviceToken() {
  return crypto.randomBytes(32).toString('base64url')
}
