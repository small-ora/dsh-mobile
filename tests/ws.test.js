/**
 * tests/ws.test.js — 零依赖 WS 实现的安全边界：
 *  - 分片消息累计超过 maxFrame → 1009（message too big），且限制分片数（防认证后 OOM DoS）；
 *  - client→server 帧必须掩码（P2：client 方向 Close 帧曾未掩码）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import http from 'node:http'
import net from 'node:net'
import { acceptUpgrade, connectWebSocket, wsAccept } from '../gateway/ws.mjs'

const OPCODE = { text: 0x1, continuation: 0x0, close: 0x8 }

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject)
      resolve(server.address().port)
    })
  })
}

/** 构造一个带掩码的客户端帧。 */
function maskedFrame(opcode, payload, fin = true) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(payload)
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
  header[0] = (fin ? 0x80 : 0) | opcode
  header[1] |= 0x80
  const mask = crypto.randomBytes(4)
  const masked = Buffer.alloc(len)
  for (let i = 0; i < len; i++) masked[i] = body[i] ^ mask[i & 3]
  return Buffer.concat([header, mask, masked])
}

/** 解析服务端→客户端帧（不掩码）。 */
function parseServerFrame(buf) {
  if (buf.length < 2) return null
  const opcode = buf[0] & 0x0f
  const masked = (buf[1] & 0x80) !== 0
  let len = buf[1] & 0x7f
  let off = 2
  if (len === 126) {
    if (buf.length < 4) return null
    len = buf.readUInt16BE(2)
    off = 4
  } else if (len === 127) {
    if (buf.length < 10) return null
    len = Number(buf.readBigUInt64BE(2))
    off = 10
  }
  if (buf.length < off + len) return null
  return { opcode, masked, payload: buf.subarray(off, off + len), total: off + len }
}

/** 原始 socket 完成 WS 握手（用于手工构造帧/抓取原始字节）。 */
function rawUpgrade(port, { path = '/x', headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1')
    const key = crypto.randomBytes(16).toString('base64')
    let buffer = Buffer.alloc(0)
    let upgraded = false
    const timer = setTimeout(() => {
      if (!upgraded) {
        socket.destroy()
        reject(new Error('upgrade timeout'))
      }
    }, 3000)
    timer.unref?.()
    socket.once('connect', () => {
      const extra = Object.entries(headers)
        .map(([name, value]) => `${name}: ${value}\r\n`)
        .join('')
      socket.write(
        `GET ${path} HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
          `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n${extra}\r\n`,
      )
    })
    socket.on('error', (err) => {
      if (!upgraded) reject(err)
    })
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk])
      const index = buffer.indexOf('\r\n\r\n')
      if (index < 0) return
      const head = buffer.subarray(0, index).toString('latin1')
      if (!/^HTTP\/1\.1 101/.test(head)) {
        socket.destroy()
        reject(new Error(`upgrade failed: ${head.split('\r\n')[0]}`))
        return
      }
      upgraded = true
      clearTimeout(timer)
      socket.off('data', onData)
      resolve({ socket, rest: buffer.subarray(index + 4) })
    }
    socket.on('data', onData)
    socket.on('close', () => {
      if (!upgraded) reject(new Error('socket closed before upgrade'))
    })
  })
}

/** 等待服务端发来的第一个完整帧。 */
function waitServerFrame(socket, { timeout = 5000, initial = Buffer.alloc(0) } = {}) {
  return new Promise((resolve, reject) => {
    let buffer = initial
    const timer = setTimeout(() => {
      cleanup()
      reject(new Error('server frame timeout'))
    }, timeout)
    timer.unref?.()
    const finish = (frame) => {
      cleanup()
      resolve(frame)
    }
    const tryParse = () => {
      const frame = parseServerFrame(buffer)
      if (frame) finish(frame)
    }
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk])
      tryParse()
    }
    const cleanup = () => {
      clearTimeout(timer)
      socket.off('data', onData)
    }
    socket.on('data', onData)
    tryParse()
  })
}

test('分片消息累计超过 maxFrame → 协议错误 1009（不拼接、不 OOM）', async (t) => {
  const server = http.createServer()
  server.on('upgrade', (req, socket, head) => {
    try {
      const conn = acceptUpgrade(req, socket, head)
      conn.on('error', () => {})
    } catch {
      /* ignore */
    }
  })
  const port = await listen(server)
  t.after(async () => {
    await new Promise((resolve) => server.close(() => resolve()))
    server.closeAllConnections?.()
  })

  const { socket, rest } = await rawUpgrade(port)
  t.after(() => socket.destroy())
  const framePromise = waitServerFrame(socket, { initial: rest })
  // 两个 6MiB 分片：单帧均 < 8MiB maxFrame，但累计 12MiB > maxFrame → 1009
  const fragment = Buffer.alloc(6 * 1024 * 1024, 0x61)
  socket.write(maskedFrame(OPCODE.text, fragment, false))
  socket.write(maskedFrame(OPCODE.continuation, fragment, true))
  const frame = await framePromise
  assert.equal(frame.opcode, OPCODE.close)
  assert.equal(frame.masked, false, '服务端帧必须不掩码')
  assert.equal(frame.payload.readUInt16BE(0), 1009)
})

test('分片数量超过上限 → 1009', async (t) => {
  const server = http.createServer()
  server.on('upgrade', (req, socket, head) => {
    try {
      const conn = acceptUpgrade(req, socket, head)
      conn.on('error', () => {})
    } catch {
      /* ignore */
    }
  })
  const port = await listen(server)
  t.after(async () => {
    await new Promise((resolve) => server.close(() => resolve()))
    server.closeAllConnections?.()
  })

  const { socket, rest } = await rawUpgrade(port)
  t.after(() => socket.destroy())
  const framePromise = waitServerFrame(socket, { initial: rest, timeout: 8000 })
  socket.write(maskedFrame(OPCODE.text, Buffer.alloc(16), false))
  const piece = Buffer.alloc(16)
  for (let i = 0; i < 1100; i++) socket.write(maskedFrame(OPCODE.continuation, piece, false))
  socket.write(maskedFrame(OPCODE.continuation, piece, true))
  const frame = await framePromise
  assert.equal(frame.opcode, OPCODE.close)
  assert.equal(frame.payload.readUInt16BE(0), 1009)
})

test('client→server Close 帧必须掩码（P2 修复）', async (t) => {
  const sockets = new Set()
  let captured = Buffer.alloc(0)
  let upgraded = false
  const server = net.createServer((socket) => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
    let buffer = Buffer.alloc(0)
    socket.on('data', (chunk) => {
      if (!upgraded) {
        buffer = Buffer.concat([buffer, chunk])
        const index = buffer.indexOf('\r\n\r\n')
        if (index < 0) return
        const head = buffer.subarray(0, index).toString('latin1')
        const key = /sec-websocket-key:\s*(\S+)/i.exec(head)
        if (!key) {
          socket.destroy()
          return
        }
        socket.write(
          'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
            `Sec-WebSocket-Accept: ${wsAccept(key[1])}\r\n\r\n`,
        )
        upgraded = true
        captured = Buffer.concat([captured, buffer.subarray(index + 4)])
        return
      }
      captured = Buffer.concat([captured, chunk])
    })
  })
  const port = await listen(server)
  t.after(async () => {
    for (const socket of sockets) socket.destroy()
    await new Promise((resolve) => server.close(() => resolve()))
  })

  const conn = await connectWebSocket(`ws://127.0.0.1:${port}/x`, { timeoutMs: 3000 })
  t.after(() => conn.terminate())
  conn.close(1000, 'bye')

  // Close 帧：2 字节头 + 4 字节掩码 + 2 字节 code + 3 字节 reason = 11 字节
  const deadline = Date.now() + 3000
  while (captured.length < 11 && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  assert.ok(captured.length >= 11, `captured close frame bytes: ${captured.length}`)
  assert.equal(captured[0] & 0x0f, OPCODE.close, '应是 Close 帧')
  assert.equal(captured[0] & 0x80, 0x80, 'FIN 必须置位')
  assert.equal(captured[1] & 0x80, 0x80, 'client→server 帧必须带 MASK 位')
  const maskKey = captured.subarray(2, 6)
  const payload = Buffer.alloc(captured[1] & 0x7f)
  for (let i = 0; i < payload.length; i++) payload[i] = captured[6 + i] ^ maskKey[i & 3]
  assert.equal(payload.readUInt16BE(0), 1000)
  assert.equal(payload.subarray(2).toString('utf8'), 'bye')
})
