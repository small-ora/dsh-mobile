/**
 * gateway/ws.mjs — 零依赖 RFC6455 WebSocket 实现（服务端 accept + 客户端 connect）。
 *
 * 网关需要两个方向：
 *  - 面向 App：作为 WS 服务端（`GET /api/ws?ticket=`），接收掩码帧、发送不掩码帧；
 *  - 面向 DSH 上游：作为 WS 客户端连 `ws://<dsh>/api/remote.mux`，注入 Cookie 头，
 *    发送掩码帧、接收不掩码帧。
 *
 * 只实现业务需要的子集：文本帧（含分片重组）、Ping/Pong/Close 控制帧、背压上限。
 * 控制帧按 RFC6455 §5.5 处理（载荷 ≤125、不参与分片）。
 */
import crypto from 'node:crypto'
import http from 'node:http'
import https from 'node:https'
import { EventEmitter } from 'node:events'

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

export const DEFAULT_MAX_FRAME = 8 * 1024 * 1024
export const DEFAULT_MAX_BUFFER = 2 * 1024 * 1024
/** 分片消息最多允许的分片数（防认证后碎片 DoS）。 */
export const DEFAULT_MAX_FRAGMENTS = 1024
export const OPCODES = {
  continuation: 0x0,
  text: 0x1,
  binary: 0x2,
  close: 0x8,
  ping: 0x9,
  pong: 0xa,
}

export function wsAccept(key) {
  return crypto.createHash('sha1').update(String(key || '') + WS_GUID).digest('base64')
}

/** 编码一个帧；payload 为 Buffer 或字符串。 */
export function encodeFrame(opcode, payload = Buffer.alloc(0), { mask = false } = {}) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), 'utf8')
  const len = body.length
  let header
  if (len < 126) {
    header = Buffer.alloc(2)
    header[1] = len
  } else if (len < 65536) {
    header = Buffer.alloc(4)
    header[1] = 126
    header.writeUInt16BE(len, 2)
  } else {
    header = Buffer.alloc(10)
    header[1] = 127
    header.writeBigUInt64BE(BigInt(len), 2)
  }
  header[0] = 0x80 | opcode
  if (!mask) return Buffer.concat([header, body])
  header[1] |= 0x80
  const maskKey = crypto.randomBytes(4)
  const masked = Buffer.alloc(len)
  for (let i = 0; i < len; i++) masked[i] = body[i] ^ maskKey[i & 3]
  return Buffer.concat([header, maskKey, masked])
}

export function encodeText(text, { mask = false } = {}) {
  return encodeFrame(OPCODES.text, Buffer.from(String(text), 'utf8'), { mask })
}

export function encodePing(payload = Buffer.alloc(0)) {
  return encodeFrame(OPCODES.ping, payload, { mask: false })
}

export function encodePong(payload = Buffer.alloc(0)) {
  return encodeFrame(OPCODES.pong, payload, { mask: false })
}

export function encodeClose(code = 1000, reason = '', { mask = false } = {}) {
  let text = String(reason || '')
  if (text.length > 120) text = text.slice(0, 120)
  const reasonBuf = Buffer.from(text, 'utf8')
  const payload = Buffer.alloc(2 + reasonBuf.length)
  payload.writeUInt16BE(code, 0)
  reasonBuf.copy(payload, 2)
  return encodeFrame(OPCODES.close, payload, { mask })
}

/**
 * 从缓冲区解析一个帧。返回 { frame } / { frame:null, needMore:true } / { protocolError }。
 * requireMask 为 true 时（服务端视角）客户端帧必须带掩码。
 */
function parseFrame(buf, { requireMask, maxFrame }) {
  if (buf.length < 2) return { needMore: true }
  const fin = (buf[0] & 0x80) !== 0
  const opcode = buf[0] & 0x0f
  const masked = (buf[1] & 0x80) !== 0
  let len = buf[1] & 0x7f
  let off = 2
  if (len === 126) {
    if (buf.length < 4) return { needMore: true }
    len = buf.readUInt16BE(2)
    off = 4
  } else if (len === 127) {
    if (buf.length < 10) return { needMore: true }
    const big = buf.readBigUInt64BE(2)
    if (big > BigInt(maxFrame)) return { protocolError: 1009 }
    len = Number(big)
    off = 10
  }
  if (len > maxFrame) return { protocolError: 1009 }
  if (requireMask && !masked) return { protocolError: 1002 }
  if (!requireMask && masked) return { protocolError: 1002 }
  const maskBytes = masked ? 4 : 0
  const total = off + maskBytes + len
  if (buf.length < total) return { needMore: true }
  let payload = buf.subarray(off + maskBytes, off + maskBytes + len)
  if (masked) {
    const maskKey = buf.subarray(off, off + 4)
    const unmasked = Buffer.allocUnsafe(len)
    for (let i = 0; i < len; i++) unmasked[i] = payload[i] ^ maskKey[i & 3]
    payload = unmasked
  }
  return { frame: { fin, opcode, payload, total } }
}

/** 双向文本连接。事件：'text'(string)、'close'({code,reason})、'error'(err)、'pong'()。 */
export class WsConnection extends EventEmitter {
  constructor(socket, { side = 'server', maxFrame = DEFAULT_MAX_FRAME, maxBuffered = DEFAULT_MAX_BUFFER, maxFragments = DEFAULT_MAX_FRAGMENTS } = {}) {
    super()
    this.socket = socket
    this.side = side // 'server' → 读掩码帧/写不掩码帧；'client' → 反之
    this.maxFrame = maxFrame
    this.maxBuffered = maxBuffered
    this.maxFragments = maxFragments
    this.maskOutgoing = side === 'client'
    this.closed = false
    this.closeSent = false
    this.terminated = false
    this._buffer = Buffer.alloc(0)
    this._fragments = []
    this._fragmentOpcode = null
    this._fragmentBytes = 0
    this._fragmentCount = 0
    this._closeTimer = null

    socket.setNoDelay?.(true)
    socket.on('data', (chunk) => this._onData(chunk))
    socket.on('error', (err) => this._emitError(err))
    socket.on('close', () => this._onSocketClose())
    socket.on('end', () => this._onSocketClose())
  }

  get bufferedAmount() {
    return Number(this.socket.writableLength || 0)
  }

  /** 发送文本帧；超过缓冲上限时返回 false 并断开（调用方负责终止对端）。 */
  sendText(text) {
    if (this.closed || this.terminated || !this.socket.writable) return false
    const frame = encodeText(text, { mask: this.maskOutgoing })
    if (this.socket.writableLength + frame.length > this.maxBuffered) {
      this.emit('backpressure', this.socket.writableLength + frame.length)
      this.terminate()
      return false
    }
    try {
      return this.socket.write(frame)
    } catch (err) {
      this._emitError(err)
      this.terminate()
      return false
    }
  }

  ping(payload = Buffer.alloc(0)) {
    if (this.closed || this.terminated || !this.socket.writable) return false
    const body = this.maskOutgoing ? encodeFrame(OPCODES.ping, payload, { mask: true }) : encodePing(payload)
    try {
      this.socket.write(body)
      return true
    } catch {
      return false
    }
  }

  close(code = 1000, reason = '') {
    if (this.closed || this.terminated) return
    if (this.closeSent) {
      this.terminate()
      return
    }
    this.closeSent = true
    try {
      // RFC6455 §5.1：client→server 帧必须掩码，server→client 帧必须不掩码。
      this.socket.write(encodeClose(code, reason, { mask: this.maskOutgoing }))
      this.socket.end()
    } catch {
      this.terminate()
      return
    }
    this._closeTimer = setTimeout(() => this.terminate(), 1000)
    this._closeTimer.unref?.()
  }

  terminate() {
    if (this.terminated) return
    this.terminated = true
    this.closed = true
    if (this._closeTimer) clearTimeout(this._closeTimer)
    try {
      this.socket.destroy()
    } catch {
      /* ignore */
    }
    this.emit('close', { code: 1006, reason: 'terminated' })
  }

  _emitError(err) {
    if (this.closed) return
    this.emit('error', err)
  }

  _onSocketClose() {
    if (this.closed) return
    this.closed = true
    if (this._closeTimer) clearTimeout(this._closeTimer)
    this.emit('close', {
      code: this._closeCode ?? 1006,
      reason: this._closeReason ?? 'socket-closed',
    })
  }

  _fail(code, reason) {
    if (this.closed) return
    try {
      this.socket.write(encodeClose(code, reason, { mask: this.maskOutgoing }))
    } catch {
      /* ignore */
    }
    this.terminate()
  }

  _onData(chunk) {
    if (this.closed) return
    this._buffer = this._buffer.length ? Buffer.concat([this._buffer, chunk]) : chunk
    const requireMask = this.side === 'server'
    while (!this.closed) {
      const result = parseFrame(this._buffer, { requireMask, maxFrame: this.maxFrame })
      if (result.needMore) break
      if (result.protocolError) {
        this._fail(result.protocolError, 'protocol-error')
        return
      }
      const { frame } = result
      this._buffer = this._buffer.subarray(frame.total)
      this._handleFrame(frame)
    }
  }

  _handleFrame(frame) {
    switch (frame.opcode) {
      case OPCODES.text:
      case OPCODES.binary: {
        if (frame.opcode === OPCODES.binary) {
          this._fail(1003, 'binary-not-supported')
          return
        }
        if (this._fragmentOpcode !== null) {
          this._fail(1002, 'unexpected-data-frame')
          return
        }
        if (frame.fin) {
          this._deliverText(frame.payload)
        } else {
          if (frame.payload.length > this.maxFrame) {
            this._fail(1009, 'message-too-big')
            return
          }
          this._fragmentOpcode = frame.opcode
          this._fragments = [frame.payload]
          this._fragmentBytes = frame.payload.length
          this._fragmentCount = 1
        }
        return
      }
      case OPCODES.continuation: {
        if (this._fragmentOpcode === null) {
          this._fail(1002, 'unexpected-continuation')
          return
        }
        this._fragmentBytes += frame.payload.length
        this._fragmentCount += 1
        // 分片总量/分片数上限：超限直接 1009（message too big），不拼接、不 OOM。
        if (this._fragmentBytes > this.maxFrame || this._fragmentCount > this.maxFragments) {
          this._fragments = []
          this._fragmentOpcode = null
          this._fragmentBytes = 0
          this._fragmentCount = 0
          this._fail(1009, 'message-too-big')
          return
        }
        this._fragments.push(frame.payload)
        if (frame.fin) {
          const payload = Buffer.concat(this._fragments)
          this._fragments = []
          this._fragmentOpcode = null
          this._fragmentBytes = 0
          this._fragmentCount = 0
          this._deliverText(payload)
        }
        return
      }
      case OPCODES.ping: {
        if (frame.payload.length > 125) return
        if (this.closed || this.terminated) return
        const pong = this.maskOutgoing
          ? encodeFrame(OPCODES.pong, frame.payload, { mask: true })
          : encodePong(frame.payload)
        try {
          this.socket.write(pong)
        } catch {
          /* ignore */
        }
        this.emit('ping')
        return
      }
      case OPCODES.pong: {
        this.emit('pong')
        return
      }
      case OPCODES.close: {
        let code = 1000
        let reason = ''
        if (frame.payload.length === 1) {
          this._fail(1002, 'bad-close')
          return
        }
        if (frame.payload.length >= 2) {
          code = frame.payload.readUInt16BE(0)
          reason = frame.payload.subarray(2).toString('utf8')
        }
        this._closeCode = code
        this._closeReason = reason
        this.closed = true
        if (this._closeTimer) clearTimeout(this._closeTimer)
        try {
          this.socket.write(encodeClose(code === 1005 || code === 1006 ? 1000 : code, '', { mask: this.maskOutgoing }))
          this.socket.end()
        } catch {
          this.socket.destroy()
        }
        this.emit('close', { code, reason })
        return
      }
      default:
        this._fail(1002, 'unknown-opcode')
    }
  }

  _deliverText(payload) {
    let text
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(payload)
    } catch {
      this._fail(1007, 'invalid-utf8')
      return
    }
    this.emit('text', text)
  }
}

/** 在 http.Server 的 'upgrade' 事件里接受一个 WS 连接。 */
export function acceptUpgrade(req, socket, head, options = {}) {
  const key = req.headers['sec-websocket-key']
  if (!key || req.headers.upgrade?.toLowerCase() !== 'websocket') {
    socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
    throw new Error('bad-websocket-handshake')
  }
  const version = req.headers['sec-websocket-version']
  if (version && version !== '13') {
    socket.end('HTTP/1.1 426 Upgrade Required\r\nSec-WebSocket-Version: 13\r\nConnection: close\r\n\r\n')
    throw new Error('bad-websocket-version')
  }
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${wsAccept(key)}\r\n` +
      '\r\n',
  )
  if (head?.length) socket.unshift(head)
  return new WsConnection(socket, { side: 'server', ...options })
}

/** 以客户端身份连接一个 WS URL（可带自定义头，用于注入 Cookie）。 */
export function connectWebSocket(targetUrl, { headers = {}, timeoutMs = 10_000, maxFrame, maxBuffered } = {}) {
  return new Promise((resolve, reject) => {
    let url
    try {
      url = new URL(targetUrl)
    } catch (err) {
      reject(new Error(`invalid-ws-url: ${err.message}`))
      return
    }
    if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
      reject(new Error(`invalid-ws-protocol: ${url.protocol}`))
      return
    }
    const isTls = url.protocol === 'wss:'
    const hostname = url.hostname.replace(/^\[|\]$/g, '')
    const key = crypto.randomBytes(16).toString('base64')
    const req = (isTls ? https : http).request({
      hostname,
      port: url.port || (isTls ? 443 : 80),
      path: `${url.pathname}${url.search}`,
      method: 'GET',
      headers: {
        connection: 'Upgrade',
        upgrade: 'websocket',
        'sec-websocket-version': '13',
        'sec-websocket-key': key,
        ...headers,
      },
      timeout: timeoutMs,
    })
    let settled = false
    const fail = (err) => {
      if (settled) return
      settled = true
      try {
        req.destroy()
      } catch {
        /* ignore */
      }
      reject(err)
    }
    req.on('upgrade', (res, socket, head) => {
      if (settled) {
        socket.destroy()
        return
      }
      if (res.statusCode !== 101) {
        socket.destroy()
        fail(new Error(`ws-upgrade-failed: ${res.statusCode}`))
        return
      }
      const expected = wsAccept(key)
      if (res.headers['sec-websocket-accept'] !== expected) {
        socket.destroy()
        fail(new Error('ws-accept-mismatch'))
        return
      }
      settled = true
      socket.setNoDelay?.(true)
      if (head?.length) socket.unshift(head)
      resolve(new WsConnection(socket, { side: 'client', maxFrame, maxBuffered }))
    })
    req.on('response', (res) => {
      res.resume()
      fail(new Error(`ws-upgrade-rejected: ${res.statusCode}`))
    })
    req.on('timeout', () => fail(new Error('ws-connect-timeout')))
    req.on('error', (err) => fail(err))
    req.end()
  })
}

/**
 * 心跳：每 intervalMs 发一次 Ping；连续 silentFactor 个周期没有任何入站帧则判定对端死亡。
 * 返回停止函数。定时器 unref，不阻止进程退出。
 */
export function startHeartbeat(conn, { intervalMs = 30_000, silentFactor = 3, onDead } = {}) {
  if (!(intervalMs > 0)) return () => {}
  let lastInbound = Date.now()
  const touch = () => {
    lastInbound = Date.now()
  }
  conn.on('text', touch)
  conn.on('ping', touch)
  conn.on('pong', touch)
  const timer = setInterval(() => {
    if (conn.closed || conn.terminated) return
    if (Date.now() - lastInbound > intervalMs * silentFactor) {
      try {
        onDead?.()
      } finally {
        conn.terminate()
      }
      return
    }
    conn.ping()
  }, intervalMs)
  timer.unref?.()
  return () => {
    clearInterval(timer)
    conn.off('text', touch)
    conn.off('ping', touch)
    conn.off('pong', touch)
  }
}

/** 拒绝一个 Upgrade 请求（未通过鉴权/路径不存在时）。 */
export function rejectUpgrade(socket, status = 401, message = 'Unauthorized') {
  const body = `${message}\n`
  try {
    socket.end(
      `HTTP/1.1 ${status} ${message}\r\n` +
        'Connection: close\r\n' +
        'Content-Type: text/plain; charset=utf-8\r\n' +
        `Content-Length: ${Buffer.byteLength(body)}\r\n` +
        '\r\n' +
        body,
    )
  } catch {
    socket.destroy()
  }
}
