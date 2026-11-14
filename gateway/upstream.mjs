/**
 * gateway/upstream.mjs — 上游 DSH 代理（现代协议）。
 *
 *  - RPC：POST /api/rpc {method,args} → 上游 POST /api/<method>
 *          body {type:'client-request',rpcId,method,payload:{args}}；返回上游 result 原样。
 *    网关只做方法白名单，不做参数改写（docs/interfaces.md §2.2/§4）。
 *  - 流：由 gateway/streams.mjs 的 StreamHub 统一管理（单条上游 WS + 逻辑流 + 长轮询）。
 *  - 持久 $events 订阅：PendingStore 消费 hub 内部 $events 流的 waterfall 帧，缓存为 pending
 *    供 App 后台轮询 /api/pending 与 /api/respond（§2.4）。
 *  - Cookie：每次请求读取 ~/.dsh-mobile/upstream.cookie（插件刷新后无需重启）。
 */
import crypto from 'node:crypto'
import { connectWebSocket } from './ws.mjs'
import { readUpstreamBase, readUpstreamCookie } from './config.mjs'
import { silentLogger } from './log.mjs'

/**
 * docs/interfaces.md §4 方法白名单（v3：补 goals/*、workspace/*、session/projections）。
 * 注意：session/follow 是流，不属于 /api/rpc 白名单；这里不包含它。
 */
export const RPC_ALLOWLIST = new Set([
  'session/list',
  'session/modelCatalog',
  'session/page',
  'session/projections',
  'session/prompt',
  'session/cancel',
  'session/create',
  'session/rename',
  'session/search',
  'session/selectModel',
  'session/updateQueue',
  'session/fork',
  'session/attachment',
  'goals/get',
  'goals/pause',
  'goals/resume',
  'goals/complete',
  'goals/clear',
  'workspace/archiveSession',
  'workspace/unarchiveSession',
  'workspace/create',
  // v0.1.5：排队消息 / 权限预设 / 斜杠指令（真实 DSH 已确认存在，参数原样透传）
  'commands/list',
  'commands/execute',
  'permissionPresets/catalog',
  '$events/result',
])

/** docs/interfaces.md §4 流白名单。 */
export const STREAM_ALLOWLIST = new Set(['$events', 'session/follow'])

export class UpstreamError extends Error {
  constructor(code, message) {
    super(message || code)
    this.name = 'UpstreamError'
    this.code = code || 'upstream-unavailable'
  }
}

function safeMethodPath(method) {
  // 白名单内方法只含字母数字、/、$、-；这里再收紧一次，避免路径注入。
  return String(method)
    .split('/')
    .map((segment) => segment.replace(/[^A-Za-z0-9_$-]/g, ''))
    .filter(Boolean)
    .join('/')
}

export function createUpstream({ home, env = process.env, fetchImpl = fetch, logger = silentLogger, timeoutMs = 30_000, connectImpl = connectWebSocket } = {}) {
  return new UpstreamProxy({ home, env, fetchImpl, logger, timeoutMs, connectImpl })
}

export class UpstreamProxy {
  constructor({ home, env = process.env, fetchImpl = fetch, logger = silentLogger, timeoutMs = 30_000, connectImpl = connectWebSocket }) {
    this.home = home
    this.env = env
    this.fetchImpl = fetchImpl
    this.logger = logger
    this.timeoutMs = timeoutMs
    this.connectImpl = connectImpl
    this.lastOkAt = 0
    this.lastError = null
    this.lastErrorAt = 0
  }

  baseUrl() {
    return readUpstreamBase(this.home, this.env)
  }

  cookie() {
    return readUpstreamCookie(this.home)
  }

  get healthy() {
    return Boolean(this.lastOkAt && Date.now() - this.lastOkAt < 60_000)
  }

  snapshot() {
    return {
      ok: this.healthy,
      lastOkAt: this.lastOkAt || null,
      lastError: this.lastError,
      lastErrorAt: this.lastErrorAt || null,
    }
  }

  _markOk() {
    this.lastOkAt = Date.now()
  }

  _markFail(kind, err) {
    this.lastError = `${kind}${err?.code ? `:${err.code}` : ''}`
    this.lastErrorAt = Date.now()
    this.logger.warn('upstream request failed', { kind })
  }

  /** 转发一次 RPC，返回上游 result 对象（{ok,value}|{ok:false,error}）。 */
  async rpc(method, args) {
    if (!RPC_ALLOWLIST.has(method)) throw new UpstreamError('method-not-allowed', 'rpc method not allowed')
    const cookie = this.cookie()
    if (!cookie) {
      this._markFail('missing-cookie')
      throw new UpstreamError('upstream-unavailable', 'upstream cookie missing')
    }
    const base = this.baseUrl()
    const body = JSON.stringify({
      type: 'client-request',
      rpcId: crypto.randomUUID(),
      method,
      payload: { args: args && typeof args === 'object' ? args : {} },
    })
    let response
    try {
      response = await this.fetchImpl(`${base}/api/${safeMethodPath(method)}`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          accept: 'application/json',
          cookie,
        },
        body,
        signal: AbortSignal.timeout(this.timeoutMs),
        redirect: 'manual',
      })
    } catch (err) {
      this._markFail('network', err)
      throw new UpstreamError('upstream-unavailable', 'upstream unreachable')
    }
    if (response.status !== 200) {
      this._markFail(`http-${response.status}`)
      throw new UpstreamError('upstream-unavailable', `upstream http ${response.status}`)
    }
    let payload
    try {
      payload = await response.json()
    } catch (err) {
      this._markFail('bad-json', err)
      throw new UpstreamError('upstream-unavailable', 'upstream response is not json')
    }
    this._markOk()
    if (payload && typeof payload === 'object' && payload.result && typeof payload.result === 'object') {
      return payload.result
    }
    // 兼容上游直接返回 result 形状的实现。
    if (payload && typeof payload === 'object' && typeof payload.ok === 'boolean') return payload
    this._markFail('bad-shape')
    throw new UpstreamError('upstream-unavailable', 'unexpected upstream response')
  }

  /** 建立到上游 /api/remote.mux 的 WS 连接（注入 Cookie）。 */
  async connectMux() {
    const cookie = this.cookie()
    if (!cookie) {
      this._markFail('missing-cookie')
      throw new UpstreamError('upstream-unavailable', 'upstream cookie missing')
    }
    const base = this.baseUrl()
    const wsUrl = `${base.replace(/^http:/, 'ws:').replace(/^https:/, 'wss:')}/api/remote.mux`
    try {
      const conn = await this.connectImpl(wsUrl, { headers: { cookie }, timeoutMs: 10_000 })
      this._markOk()
      return conn
    } catch (err) {
      this._markFail('ws-connect', err)
      throw new UpstreamError('upstream-unavailable', 'upstream websocket unreachable')
    }
  }
}

/** 校验 App → 网关的一帧（仅 WS 可选通道使用；只接受 open/cancel）。 */
export function validateClientFrame(text) {
  if (typeof text !== 'string') return { ok: false, code: 'bad-frame', message: 'frame must be text' }
  if (text.length > 2 * 1024 * 1024) return { ok: false, code: 'frame-too-large', message: 'frame too large' }
  let message
  try {
    message = JSON.parse(text)
  } catch {
    return { ok: false, code: 'bad-json', message: 'frame is not valid json' }
  }
  if (!message || typeof message !== 'object' || Array.isArray(message)) {
    return { ok: false, code: 'bad-frame', message: 'frame must be an object' }
  }
  if (message.type === 'open') {
    if (typeof message.streamId !== 'string' || !message.streamId || message.streamId.length > 64) {
      return { ok: false, code: 'bad-stream-id', message: 'streamId must be a string' }
    }
    if (typeof message.endpoint !== 'string' || !STREAM_ALLOWLIST.has(message.endpoint)) {
      return { ok: false, streamId: message.streamId, code: 'method-not-allowed', message: 'stream endpoint not allowed' }
    }
    return { ok: true }
  }
  if (message.type === 'cancel') {
    if (typeof message.streamId !== 'string' || !message.streamId) {
      return { ok: false, code: 'bad-stream-id', message: 'streamId must be a string' }
    }
    return { ok: true }
  }
  return { ok: false, code: 'bad-frame', message: 'unsupported frame type' }
}

/**
 * 待处理审批/提问缓存（后台通知用）。
 *
 * 数据来源：StreamHub 内部长期订阅的 `$events` 流（ready / waterfall / cancel 帧）。
 * respond 时用该流的 clientId 调上游 `$events/result`；outcome 是任意 JSON 值：
 *   approval → "allowed-once" | "rejected"；question → {answers:[{id,selected:[],custom?}]}
 */
export class PendingStore {
  constructor({ upstream, logger = silentLogger, maxPending = 200, now = Date.now }) {
    this.upstream = upstream
    this.logger = logger
    this.maxPending = maxPending
    this.now = now
    this.clientId = null
    this.ready = false
    this.pendingById = new Map()
  }

  /** 消费内部 $events 流的一个 value。 */
  handleValue(value) {
    if (!value || typeof value !== 'object') return
    if (value.type === 'ready') {
      this.ready = true
      this.clientId = typeof value.clientId === 'string' ? value.clientId : null
      this.logger.info('events stream ready')
      return
    }
    if (value.type === 'waterfall') {
      this._addWaterfall(value)
      return
    }
    if (value.type === 'cancel' && typeof value.eventId === 'string') {
      this.pendingById.delete(value.eventId)
    }
  }

  /**
   * 上游 WS 断开/重连时清空 ready 状态与 pending 缓存。
   * 依据上游语义：$events 重开会重放仍未处理的 waterfall，因此清空后由重放重建，
   * 避免“僵尸审批卡片”（例如已从 DSH Web 端处理、上游不再重放的事件）。
   */
  markDisconnected() {
    this.ready = false
    this.clientId = null
    this.pendingById.clear()
  }

  pending() {
    return [...this.pendingById.values()].sort((a, b) => a.at - b.at)
  }

  snapshot() {
    return { ready: this.ready, pendingCount: this.pendingById.size }
  }

  _addWaterfall(value) {
    const eventId = typeof value.eventId === 'string' ? value.eventId : null
    if (!eventId) return
    const event = String(value.event || '')
    const request = value.request && typeof value.request === 'object' ? value.request : {}
    const sessionId = typeof value.agentId === 'string' ? value.agentId : typeof request.sessionId === 'string' ? request.sessionId : null
    const at = this.now()
    if (event === 'approval/request') {
      this._put({
        kind: 'approval',
        eventId,
        sessionId,
        toolName: typeof request.toolName === 'string' ? request.toolName : null,
        reason: typeof request.reason === 'string' ? request.reason : null,
        at,
      })
      return
    }
    if (event === 'question/request' || event === 'user-questions/request' || (event.endsWith('/request') && event.includes('question'))) {
      this._put({
        kind: 'question',
        eventId,
        sessionId,
        questions: Array.isArray(request.questions) ? request.questions : Array.isArray(value.questions) ? value.questions : [],
        at,
      })
    }
  }

  _put(item) {
    this.pendingById.set(item.eventId, item)
    while (this.pendingById.size > this.maxPending) {
      const oldest = this.pendingById.keys().next().value
      this.pendingById.delete(oldest)
    }
  }

  /** 回答审批/提问（outcome 为任意 JSON 值）。 */
  async respond(eventId, outcome) {
    if (typeof eventId !== 'string' || !eventId) return { accepted: false, error: 'bad-event-id' }
    if (!this.ready || !this.clientId) return { accepted: false, error: 'events-not-ready' }
    let result
    try {
      result = await this.upstream.rpc('$events/result', {
        clientId: this.clientId,
        eventId,
        outcome: { kind: 'result', value: outcome },
      })
    } catch (err) {
      return { accepted: false, error: err?.code || 'upstream-unavailable' }
    }
    if (result?.ok) {
      this.pendingById.delete(eventId)
      return { accepted: true }
    }
    return { accepted: false, error: result?.error?.code || 'upstream-error' }
  }
}
