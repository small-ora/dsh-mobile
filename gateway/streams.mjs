/**
 * gateway/streams.mjs — 流中心（HTTP 长轮询为主，WebSocket 可选）。
 *
 * v3 背景（SPEC v3 §7、docs/interfaces.md §2.3）：用户自建 Cloudflare 通道可能不透传
 * WebSocket，所以 App 只用 HTTP 长轮询；网关内部维护**一条**到 DSH 的
 * `ws://<dsh>/api/remote.mux` 连接，在上面开多个逻辑流（streamId 由网关分配），
 * 把每个流的 `value` 环形缓冲，App 用:
 *   POST /api/stream/open → {streamId}
 *   GET  /api/stream/poll?streamId=&since=&wait=  → {items:[{seq,value}], next, closed, reset?}
 *   POST /api/stream/cancel
 * 拉取。断线 1.5s→60s 退避重连，重连后自动重开所有已登记流，并给流打 reset 标记
 * （App 清空本地状态、等新快照/ready）。
 */
import crypto from 'node:crypto'
import { connectWebSocket, startHeartbeat } from './ws.mjs'
import { STREAM_ALLOWLIST } from './upstream.mjs'
import { silentLogger } from './log.mjs'

export const DEFAULT_RETRY_DELAYS = [1500, 3000, 6000, 12_000, 30_000, 60_000]
export const DEFAULT_POLL_WAIT_MS = 25_000
export const MAX_POLL_WAIT_MS = 25_000
export const MAX_STREAM_ITEMS = 2000
export const MAX_STREAM_BYTES = 2 * 1024 * 1024
export const MAX_STREAMS = 200
export const DEFAULT_IDLE_TIMEOUT_MS = 10 * 60 * 1000

function serializedBytes(value) {
  try {
    return Buffer.byteLength(JSON.stringify(value) ?? '', 'utf8')
  } catch {
    return 1024
  }
}

export class StreamHub {
  constructor({
    upstream,
    logger = silentLogger,
    retryDelays = DEFAULT_RETRY_DELAYS,
    maxItems = MAX_STREAM_ITEMS,
    maxBytes = MAX_STREAM_BYTES,
    maxStreams = MAX_STREAMS,
    maxStreamsPerDevice = 20,
    heartbeatMs = 30_000,
    idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS,
    now = Date.now,
    connectImpl = connectWebSocket,
  } = {}) {
    this.upstream = upstream
    this.logger = logger
    this.retryDelays = retryDelays
    this.maxItems = maxItems
    this.maxBytes = maxBytes
    this.maxStreams = maxStreams
    this.maxStreamsPerDevice = maxStreamsPerDevice
    this.heartbeatMs = heartbeatMs
    this.idleTimeoutMs = idleTimeoutMs
    this.now = now
    this.connectImpl = connectImpl

    this.streams = new Map() // streamId -> stream
    this.deviceStreamCounts = new Map() // deviceId -> count（不含内部流）
    this.conn = null
    this.stopped = true
    this.connected = false
    this.retryIndex = 0
    this.retryTimer = null
    this.stopHeartbeat = () => {}
    this.lastError = null
    this.connectedAt = 0
    this._idleTimer = null
    this._disconnectListeners = new Set()
    this._connectListeners = new Set()
  }

  start() {
    if (!this.stopped) return
    this.stopped = false
    this._connect()
    this._idleTimer = setInterval(() => this._sweepIdle(), 60_000)
    this._idleTimer.unref?.()
  }

  stop() {
    this.stopped = true
    if (this.retryTimer) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    if (this._idleTimer) {
      clearInterval(this._idleTimer)
      this._idleTimer = null
    }
    this.stopHeartbeat()
    const conn = this.conn
    this.conn = null
    this.connected = false
    if (conn) {
      try {
        conn.close(1000, 'hub-stopped')
      } catch {
        /* ignore */
      }
    }
    for (const stream of this.streams.values()) {
      stream.closed = true
      this._wake(stream)
    }
    this.streams.clear()
    this.deviceStreamCounts.clear()
  }

  get streamCount() {
    return this.streams.size
  }

  _releaseOwner(stream) {
    if (!stream.ownerDeviceId) return
    const count = (this.deviceStreamCounts.get(stream.ownerDeviceId) || 1) - 1
    if (count <= 0) this.deviceStreamCounts.delete(stream.ownerDeviceId)
    else this.deviceStreamCounts.set(stream.ownerDeviceId, count)
  }

  /** 关闭某个设备名下所有流（撤销设备/登出时调用）。返回关闭数量。 */
  cancelByOwner(ownerDeviceId) {
    if (!ownerDeviceId) return 0
    let closed = 0
    for (const stream of [...this.streams.values()]) {
      if (stream.internal || stream.ownerDeviceId !== ownerDeviceId) continue
      if (this.cancel(stream.streamId, { ownerDeviceId })) closed += 1
    }
    return closed
  }

  /** 上游 WS 断线/重连通知（PendingStore 用它清空 ready 状态）。返回取消函数。 */
  onDisconnect(listener) {
    if (typeof listener !== 'function') return () => {}
    this._disconnectListeners.add(listener)
    return () => this._disconnectListeners.delete(listener)
  }

  onConnect(listener) {
    if (typeof listener !== 'function') return () => {}
    this._connectListeners.add(listener)
    return () => this._connectListeners.delete(listener)
  }

  snapshot() {
    return {
      connected: this.connected,
      streamCount: this.streams.size,
      retryIndex: this.retryIndex,
      lastError: this.lastError,
      connectedAt: this.connectedAt || null,
    }
  }

  /**
   * 登记一个逻辑流（连接未就绪时先登记，连上后自动 open）。
   * onItem 用于内部流（如持久 $events → PendingStore）实时消费 value。
   */
  register({ endpoint, args = {}, internal = false, onItem = null, streamId = null, upstreamStreamId = null, ownerDeviceId = null } = {}) {
    if (!STREAM_ALLOWLIST.has(endpoint)) throw new Error('method-not-allowed')
    if (this.streams.size >= this.maxStreams) throw new Error('too-many-streams')
    const owner = internal ? null : ownerDeviceId
    if (owner) {
      const count = this.deviceStreamCounts.get(owner) || 0
      if (count >= this.maxStreamsPerDevice) throw new Error('too-many-device-streams')
    }
    const id = streamId || crypto.randomUUID()
    if (this.streams.has(id)) throw new Error('stream-id-conflict')
    const stream = {
      streamId: id,
      upstreamStreamId: upstreamStreamId || id,
      endpoint,
      args: args && typeof args === 'object' ? args : {},
      internal,
      ownerDeviceId: owner,
      onItem: typeof onItem === 'function' ? onItem : null,
      seq: 0,
      items: [],
      bytes: 0,
      dropped: 0,
      closed: false,
      error: null,
      resetPending: false,
      openedAt: this.now(),
      lastActivityAt: this.now(),
      waiters: new Set(),
      listeners: new Set(),
    }
    this.streams.set(id, stream)
    if (owner) this.deviceStreamCounts.set(owner, (this.deviceStreamCounts.get(owner) || 0) + 1)
    if (this.connected) this._sendOpen(stream)
    return stream
  }

  get(streamId) {
    return this.streams.get(streamId) || null
  }

  cancel(streamId, { ownerDeviceId = null } = {}) {
    const stream = this.streams.get(streamId)
    if (!stream) return false
    // 归属校验：非 owner 一律当作不存在（不泄露存在性）；内部流不允许外部取消。
    if (stream.internal) return false
    if (ownerDeviceId !== null && stream.ownerDeviceId !== ownerDeviceId) return false
    this.streams.delete(streamId)
    this._releaseOwner(stream)
    stream.closed = true
    if (this.connected && this.conn) {
      try {
        this.conn.sendText(JSON.stringify({ type: 'cancel', streamId: stream.upstreamStreamId }))
      } catch {
        /* ignore */
      }
    }
    for (const listener of stream.listeners) safeCall(listener, { type: 'close' })
    stream.listeners.clear()
    this._wake(stream)
    return true
  }

  list({ ownerDeviceId = null } = {}) {
    let streams = [...this.streams.values()]
    if (ownerDeviceId !== null) streams = streams.filter((stream) => !stream.internal && stream.ownerDeviceId === ownerDeviceId)
    return streams.map((stream) => ({
      streamId: stream.streamId,
      endpoint: stream.endpoint,
      seq: stream.seq,
      closed: stream.closed,
      ownerDeviceId: stream.ownerDeviceId,
      at: stream.openedAt,
      lastActivityAt: stream.lastActivityAt,
    }))
  }

  /** 实时订阅（可选 WS 通道使用）；replay=true 时先把已缓冲项补发一遍。 */
  subscribe(streamId, listener, { replay = true } = {}) {
    const stream = this.streams.get(streamId)
    if (!stream || typeof listener !== 'function') return () => {}
    stream.listeners.add(listener)
    if (replay) {
      for (const item of stream.items) safeCall(listener, { type: 'item', seq: item.seq, value: item.value })
    }
    return () => stream.listeners.delete(listener)
  }

  /**
   * 长轮询：有数据立即返回；无数据最多等 waitMs；流缓冲截断时 reset:true。
   * 返回 {items:[{seq,value}], next, closed, reset?, error?}
   */
  poll(streamId, { since = 0, waitMs = DEFAULT_POLL_WAIT_MS, ownerDeviceId = null } = {}) {
    const stream = this.streams.get(streamId)
    // 归属校验 + 内部流不可被客户端轮询；统一返回 stream-not-found（不泄露存在性）。
    if (!stream || stream.internal || (ownerDeviceId !== null && stream.ownerDeviceId !== ownerDeviceId)) {
      return Promise.resolve({ items: [], next: 0, closed: true, error: { code: 'stream-not-found', message: 'stream not found' } })
    }
    stream.lastActivityAt = this.now()
    const clampedWait = Math.max(0, Math.min(MAX_POLL_WAIT_MS, Number(waitMs) || 0))
    const immediate = this._collect(stream, since)
    if (immediate.items.length || immediate.reset || immediate.closed || clampedWait === 0) {
      return Promise.resolve(immediate)
    }
    return new Promise((resolve) => {
      const waiter = { since, resolve, timer: null }
      waiter.timer = setTimeout(() => {
        stream.waiters.delete(waiter)
        resolve(this._collect(stream, since))
      }, clampedWait)
      waiter.timer.unref?.()
      stream.waiters.add(waiter)
    })
  }

  _collect(stream, since) {
    const base = { closed: stream.closed }
    if (stream.error) base.error = stream.error
    if (stream.resetPending) {
      stream.resetPending = false
      return { items: [], next: stream.seq + 1, closed: stream.closed, ...(stream.error ? { error: stream.error } : {}), reset: true }
    }
    // since 语义 = “下一条期望的 seq”（即上一次返回的 next）。因此用 >= 取项：
    //   since=0 → 从 seq 1 开始；返回 next=2 后 App 传 since=2 → 得到 seq>=2，不会漏帧。
    // （App 若传“最后收到的 seq”，可能重复返回一帧，由 App 按 seq 去重，安全侧选择“不漏”。）
    const from = Number.isFinite(Number(since)) ? Math.max(0, Math.floor(Number(since))) : 0
    const items = stream.items.filter((item) => item.seq >= from).map((item) => ({ seq: item.seq, value: item.value }))
    const firstSeq = stream.items.length ? stream.items[0].seq : stream.seq + 1
    // 只有确实发生过环形缓冲淘汰、且 from 落在被淘汰区间时才 reset。
    const truncated = stream.dropped > 0 && from < firstSeq
    let next
    if (items.length) next = items[items.length - 1].seq + 1
    else if (from > stream.seq) next = from
    else next = stream.seq + 1
    return { items, next, ...base, ...(truncated ? { reset: true } : {}) }
  }

  _wake(stream) {
    if (!stream.waiters.size) return
    for (const waiter of [...stream.waiters]) {
      stream.waiters.delete(waiter)
      if (waiter.timer) clearTimeout(waiter.timer)
      try {
        waiter.resolve(this._collect(stream, waiter.since))
      } catch {
        /* ignore */
      }
    }
  }

  _sweepIdle() {
    const now = this.now()
    for (const stream of [...this.streams.values()]) {
      if (stream.internal) continue
      if (now - stream.lastActivityAt > this.idleTimeoutMs && stream.waiters.size === 0) {
        this.logger.info('closing idle stream')
        this.cancel(stream.streamId)
      }
    }
  }

  _sendOpen(stream) {
    if (!this.conn || !this.connected) return
    try {
      this.conn.sendText(
        JSON.stringify({
          type: 'open',
          streamId: stream.upstreamStreamId,
          endpoint: stream.endpoint,
          payload: { args: stream.args },
        }),
      )
    } catch (err) {
      this.logger.warn('failed to open upstream stream', { code: err?.code })
      this._onUpstreamClosed('open-failed')
    }
  }

  _scheduleReconnect() {
    if (this.stopped || this.retryTimer) return
    const delay = this.retryDelays[Math.min(this.retryIndex, this.retryDelays.length - 1)] ?? 1500
    this.retryIndex = Math.min(this.retryIndex + 1, this.retryDelays.length - 1)
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      this._connect()
    }, delay)
    this.retryTimer.unref?.()
  }

  async _connect() {
    if (this.stopped) return
    let conn
    try {
      conn = await this.upstream.connectMux()
    } catch (err) {
      this.lastError = err?.code || 'connect-failed'
      this._scheduleReconnect()
      return
    }
    if (this.stopped) {
      conn.terminate()
      return
    }
    this.conn = conn
    this.connected = true
    this.connectedAt = this.now()
    conn.on('text', (text) => this._onText(text))
    conn.on('close', () => this._onUpstreamClosed('socket-closed'))
    conn.on('error', () => this._onUpstreamClosed('socket-error'))
    this.stopHeartbeat = startHeartbeat(conn, {
      intervalMs: this.heartbeatMs,
      onDead: () => this._onUpstreamClosed('heartbeat-timeout'),
    })
    this.logger.info('stream hub connected')
    for (const listener of this._connectListeners) safeCall(listener)
    // 重连后自动重开所有已登记流。
    for (const stream of this.streams.values()) this._sendOpen(stream)
  }

  _onUpstreamClosed(reason) {
    if (this.conn) {
      try {
        this.conn.terminate()
      } catch {
        /* ignore */
      }
    }
    this.conn = null
    this.connected = false
    this.stopHeartbeat()
    this.stopHeartbeat = () => {}
    if (this.stopped) return
    this.lastError = reason
    this.logger.warn('stream hub disconnected', { reason })
    for (const listener of this._disconnectListeners) safeCall(listener)
    // 所有流打 reset：App 清空本地状态，等重连后的新 ready/snapshot。
    for (const stream of this.streams.values()) {
      stream.resetPending = true
      stream.items = []
      stream.bytes = 0
      stream.closed = false
      stream.error = null
      for (const listener of stream.listeners) safeCall(listener, { type: 'reset' })
      this._wake(stream)
    }
    this._scheduleReconnect()
  }

  _onText(text) {
    let message
    try {
      message = JSON.parse(text)
    } catch {
      this.logger.warn('upstream sent invalid json frame')
      return
    }
    if (!message || typeof message !== 'object') return
    if (message.type === 'item' && typeof message.streamId === 'string') {
      const stream = this.streams.get(message.streamId) || this._findByUpstreamId(message.streamId)
      if (!stream) return
      this._push(stream, message.value)
      return
    }
    if (message.type === 'error' && typeof message.streamId === 'string') {
      const stream = this.streams.get(message.streamId) || this._findByUpstreamId(message.streamId)
      if (!stream) return
      stream.closed = true
      stream.error = message.error && typeof message.error === 'object' ? message.error : { code: 'upstream-error', message: 'upstream error' }
      for (const listener of stream.listeners) safeCall(listener, { type: 'error', error: stream.error })
      this._wake(stream)
    }
  }

  _findByUpstreamId(upstreamStreamId) {
    for (const stream of this.streams.values()) {
      if (stream.upstreamStreamId === upstreamStreamId) return stream
    }
    return null
  }

  _push(stream, value) {
    if (stream.closed) return
    const seq = ++stream.seq
    stream.lastActivityAt = this.now()
    const bytes = serializedBytes(value)
    stream.items.push({ seq, value, bytes })
    stream.bytes += bytes
    while (stream.items.length > this.maxItems || stream.bytes > this.maxBytes) {
      const removed = stream.items.shift()
      stream.bytes -= removed.bytes
      stream.dropped += 1
    }
    if (stream.onItem) {
      try {
        stream.onItem(value)
      } catch (err) {
        this.logger.warn('stream onItem handler failed', { code: err?.code })
      }
    }
    for (const listener of stream.listeners) safeCall(listener, { type: 'item', seq, value })
    this._wake(stream)
  }
}

function safeCall(fn, arg) {
  try {
    fn(arg)
  } catch {
    /* listener 异常不影响 hub */
  }
}

export function createStreamHub(options) {
  return new StreamHub(options)
}
