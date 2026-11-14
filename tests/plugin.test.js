/**
 * tests/plugin.test.js — DSH bundle 插件（服务端半体）在 mock Cordis ctx 下的行为。
 *
 * 覆盖：
 *  - `ctx.connection.authenticatedUrl()` → 303 + Set-Cookie → 写 upstream.cookie(0600)；
 *  - spawn/守护网关（真实子进程，隔离配置目录 + 测试端口）并通过 /health 探活；
 *  - `/mobile` 面板 HTML/JS 200；`/mobile/api/status` 返回网关/LAN/远程访问说明；
 *  - `requestRejection` 拒绝时返回 401；
 *  - 卸载插件（dispose）后网关进程被停止。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import fs from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { sleep, waitFor } from './helpers.mjs'

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-mobile-plugin-test-'))
}

async function getFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port
      server.close(() => resolve(port))
    })
  })
}

function createRes() {
  const res = new EventEmitter()
  res.statusCode = null
  res.headers = {}
  res.body = ''
  res.headersSent = false
  res.writableEnded = false
  res.destroyed = false
  res.setHeader = function (name, value) {
    this.headers[String(name).toLowerCase()] = value
  }
  res.getHeader = function (name) {
    return this.headers[String(name).toLowerCase()]
  }
  res.writeHead = function (status, headers = {}) {
    this.statusCode = status
    this.headersSent = true
    for (const [key, value] of Object.entries(headers)) this.headers[key.toLowerCase()] = value
  }
  res.write = function (chunk) {
    if (chunk) this.body += Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk)
    return true
  }
  res.end = function (chunk) {
    if (chunk) this.body += Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk)
    this.writableEnded = true
    this.emit('finish')
  }
  res.destroy = function () {
    this.destroyed = true
    this.emit('close')
  }
  return res
}

function createReq({ method = 'GET', url = '/mobile', headers = {}, body = undefined } = {}) {
  const req = new EventEmitter()
  req.method = method
  req.url = url
  req.headers = headers
  req.socket = { remoteAddress: '127.0.0.1' }
  req.resume = () => {}
  req._body = body
  return req
}

async function invoke(handler, req) {
  const res = createRes()
  handler(req, res)
  if (req._body !== undefined) {
    const text = typeof req._body === 'string' ? req._body : JSON.stringify(req._body)
    setImmediate(() => {
      req.emit('data', Buffer.from(text))
      req.emit('end')
    })
  }
  await waitFor(() => res.writableEnded, { timeout: 4000, message: 'response end' })
  return res
}

test('插件：Cookie 交换、spawn 网关、/mobile 面板与 /mobile/api/status、卸载清理', async (t) => {
  const home = makeTempDir()
  const gatewayPort = await getFreePort()
  const savedEnv = {
    DSH_MOBILE_CONFIG_DIR: process.env.DSH_MOBILE_CONFIG_DIR,
    DSH_MOBILE_GATEWAY_HOST: process.env.DSH_MOBILE_GATEWAY_HOST,
    DSH_MOBILE_GATEWAY_PORT: process.env.DSH_MOBILE_GATEWAY_PORT,
  }
  process.env.DSH_MOBILE_CONFIG_DIR = home
  process.env.DSH_MOBILE_GATEWAY_HOST = '127.0.0.1'

  // 假 DSH 登录入口：authenticatedUrl → 303 + Set-Cookie
  const authServer = http.createServer((req, res) => {
    res.writeHead(303, { location: '/', 'set-cookie': 'dsh_session=plugin-cookie-value; Path=/; HttpOnly' })
    res.end()
  })
  await new Promise((resolve) => authServer.listen(0, '127.0.0.1', resolve))
  const authPort = authServer.address().port

  const routes = []
  const effects = []
  const rejectStatus = { value: undefined }
  // session-file 依赖的 in-process 服务假实现（可逐用例替换）。
  const services = {
    sessionController: { inspect: async () => ({ events: [] }) },
    attachments: {
      readImage: async (ref) => ({ ref, data: Buffer.from('PNGDATA') }),
      readFileStream: (ref) => (async function* () {
        yield Buffer.from('FILEDATA')
      })(),
    },
  }
  const ctx = {
    logger: { info() {}, warn() {}, error() {} },
    get(name) {
      return services[name]
    },
    effect(fn) {
      const dispose = fn()
      effects.push(dispose)
      return dispose
    },
    webServer: {
      host: '127.0.0.1',
      port: 3080,
      register(route) {
        routes.push(route)
        return () => routes.splice(routes.indexOf(route), 1)
      },
    },
    connection: {
      authenticatedUrl: () => `http://127.0.0.1:${authPort}/login-token`,
      requestRejection: () => rejectStatus.value,
    },
  }

  const { apply } = await import('../plugin/index.mjs')
  let disposed = false
  const disposeAll = () => {
    if (disposed) return
    disposed = true
    for (let i = effects.length - 1; i >= 0; i--) {
      try {
        effects[i]()
      } catch {
        /* ignore */
      }
    }
    for (let i = routes.length - 1; i >= 0; i--) {
      try {
        routes[i]()
      } catch {
        /* ignore */
      }
    }
  }
  t.after(async () => {
    disposeAll()
    await new Promise((resolve) => authServer.close(() => resolve()))
    authServer.closeAllConnections?.()
    for (const [key, value] of Object.entries(savedEnv)) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
    fs.rmSync(home, { recursive: true, force: true })
  })

  apply(ctx, { port: gatewayPort })

  // 1) 上游 Cookie 交换：文件存在且 0600，内容为 Set-Cookie 的 name=value
  const cookieFile = path.join(home, 'upstream.cookie')
  await waitFor(() => fs.existsSync(cookieFile), { timeout: 5000, message: 'upstream.cookie written' })
  assert.equal(fs.readFileSync(cookieFile, 'utf8').trim(), 'dsh_session=plugin-cookie-value')
  assert.equal(fs.statSync(cookieFile).mode & 0o777, 0o600)
  const upstreamFile = JSON.parse(fs.readFileSync(path.join(home, 'upstream.json'), 'utf8'))
  assert.equal(upstreamFile.base, 'http://127.0.0.1:3080')

  // 2) 插件 spawn 起网关子进程，/health 可访问
  await waitFor(async () => {
    try {
      const response = await fetch(`http://127.0.0.1:${gatewayPort}/health`, { signal: AbortSignal.timeout(800) })
      return response.ok
    } catch {
      return false
    }
  }, { timeout: 15_000, message: 'spawned gateway health' })
  const health = await (await fetch(`http://127.0.0.1:${gatewayPort}/health`)).json()
  assert.equal(health.ok, true)
  assert.equal(health.service, 'dsh-mobile')

  // 3) 路由与面板
  assert.equal(routes.length, 1)
  assert.equal(routes[0].kind, 'prefix')
  assert.equal(routes[0].path, '/mobile')
  const html = await invoke(routes[0].handler, createReq({ url: '/mobile' }))
  assert.equal(html.statusCode, 200)
  assert.match(html.headers['content-type'], /text\/html/)
  assert.match(html.body, /DSH Mobile/)
  assert.match(html.body, /设备管理/)
  assert.match(html.body, /devices-summary/)
  assert.match(html.headers['content-security-policy'], /default-src 'self'/)
  const js = await invoke(routes[0].handler, createReq({ url: '/mobile/admin.js' }))
  assert.equal(js.statusCode, 200)
  assert.match(js.body, /mobile\/api\/status/)
  assert.match(js.body, /mobile\/api\/devices/)
  assert.match(js.body, /renderDevices/)

  // 4) /mobile/api/status（同源鉴权通过）
  const statusRes = await invoke(routes[0].handler, createReq({ url: '/mobile/api/status' }))
  assert.equal(statusRes.statusCode, 200)
  const status = JSON.parse(statusRes.body)
  assert.equal(status.ok, true)
  assert.equal(status.plugin.name, 'dsh-mobile-plugin')
  assert.equal(status.gateway.port, gatewayPort)
  assert.equal(status.gateway.running, true)
  assert.equal(status.gateway.health.ok, true)
  assert.ok(Array.isArray(status.lan) && status.lan.some((entry) => entry.url === `http://127.0.0.1:${gatewayPort}`))
  assert.equal(status.upstream.cookiePresent, true)
  assert.equal(status.remoteAccess.gatewayPort, gatewayPort)
  assert.match(status.remoteAccess.note, /Cloudflare/)
  // v3：面板/状态里不再有隧道凭据或 cloudflared 管理字段
  assert.equal('tunnel' in status.gateway, false)
  assert.equal(JSON.stringify(status).includes('cloudflaredPath'), false)
  assert.equal(JSON.stringify(status).includes('/api/tunnel'), false)
  assert.equal(typeof status.initialPassword === 'string' || status.initialPassword === null, true)

  // 5) 面板设备管理：通过真实网关登录一台设备 → 列表可见 → 撤销 → token 失效
  const initialPassword = fs.readFileSync(path.join(home, 'initial-password.txt'), 'utf8').trim()
  const gwBase = `http://127.0.0.1:${gatewayPort}`
  const loginResponse = await fetch(`${gwBase}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password: initialPassword, deviceName: 'Panel Device', platform: 'android' }),
  })
  assert.equal(loginResponse.status, 200)
  const loginPayload = await loginResponse.json()
  assert.equal(typeof loginPayload.deviceId, 'string')

  const devicesRes = await invoke(routes[0].handler, createReq({ url: '/mobile/api/devices' }))
  assert.equal(devicesRes.statusCode, 200)
  const devicesPayload = JSON.parse(devicesRes.body)
  assert.equal(devicesPayload.ok, true)
  const listedDevice = devicesPayload.items.find((item) => item.id === loginPayload.deviceId)
  assert.ok(listedDevice, '面板设备列表应包含刚登录设备')
  assert.equal(listedDevice.name, 'Panel Device')
  assert.equal(listedDevice.online, true)
  assert.equal('tokenHash' in listedDevice, false)

  const statusWithDevices = JSON.parse((await invoke(routes[0].handler, createReq({ url: '/mobile/api/status' }))).body)
  assert.equal(typeof statusWithDevices.streamHub.connected, 'boolean')
  assert.equal(statusWithDevices.streamHub.connected, false, '测试环境无上游 WS，应为未连接')
  assert.ok(statusWithDevices.devices.total >= 1)
  assert.ok(statusWithDevices.devices.online >= 1)

  const revokeRes = await invoke(
    routes[0].handler,
    createReq({ method: 'POST', url: '/mobile/api/devices/revoke', body: { id: loginPayload.deviceId } }),
  )
  assert.equal(revokeRes.statusCode, 200)
  assert.deepEqual(JSON.parse(revokeRes.body), { ok: true, revoked: true })
  const afterRevoke = JSON.parse((await invoke(routes[0].handler, createReq({ url: '/mobile/api/devices' }))).body)
  assert.equal(afterRevoke.items.some((item) => item.id === loginPayload.deviceId), false)
  const meAfterRevoke = await fetch(`${gwBase}/api/me`, { headers: { authorization: `Bearer ${loginPayload.token}` } })
  assert.equal(meAfterRevoke.status, 401, '撤销后原 device token 应 401')
  const revokeUnknown = await invoke(
    routes[0].handler,
    createReq({ method: 'POST', url: '/mobile/api/devices/revoke', body: { id: 'no-such-device' } }),
  )
  assert.equal(revokeUnknown.statusCode, 404)

  // 6) 图片/文件附件读取：/mobile/api/session-file（200 流 / HEAD / 404 / 503 / 参数非法）
  const imageId = 'a'.repeat(64)
  const fileId = 'c'.repeat(64)
  const imageBytes = Buffer.from('PNGDATA')
  services.sessionController.inspect = async () => ({
    events: [
      { type: 'turn/start', data: {} },
      {
        type: 'user/message',
        data: {
          content: [
            { type: 'text', text: '看图' },
            {
              type: 'image',
              attachment: { attachmentId: `sha256:${imageId}`, mediaType: 'image/png', bytes: imageBytes.length, width: 1, height: 1, name: 'x.png' },
            },
            { type: 'file', attachment: { attachmentId: `sha256:${fileId}`, name: 'a.txt', bytes: 8 } },
          ],
        },
      },
    ],
  })
  services.attachments = {
    readImage: async (ref) => ({ ref, data: imageBytes }),
    readFileStream: (ref) => (async function* () {
      yield Buffer.from('FILEDATA')
    })(),
  }
  const imagePath = `/mobile/api/session-file?sessionId=s1&attachmentId=${imageId}`
  const imageRes = await invoke(routes[0].handler, createReq({ url: imagePath }))
  assert.equal(imageRes.statusCode, 200)
  assert.equal(imageRes.headers['content-type'], 'image/png')
  assert.equal(imageRes.headers['content-length'], String(imageBytes.length))
  assert.equal(imageRes.headers['cache-control'], 'private, no-store')
  assert.equal(imageRes.headers['x-content-type-options'], 'nosniff')
  assert.equal(imageRes.body, 'PNGDATA')
  const imageHead = await invoke(routes[0].handler, createReq({ method: 'HEAD', url: imagePath }))
  assert.equal(imageHead.statusCode, 200)
  assert.equal(imageHead.body, '')
  assert.equal(imageHead.headers['content-length'], String(imageBytes.length))
  // file 引用走 readFileStream 流式
  const fileRes = await invoke(routes[0].handler, createReq({ url: `/mobile/api/session-file?sessionId=s1&attachmentId=${fileId}` }))
  assert.equal(fileRes.statusCode, 200)
  assert.equal(fileRes.headers['content-type'], 'application/octet-stream')
  assert.equal(fileRes.headers['content-length'], '8')
  assert.equal(fileRes.body, 'FILEDATA')
  // 404：会话里没有该引用
  const missing = await invoke(routes[0].handler, createReq({ url: `/mobile/api/session-file?sessionId=s1&attachmentId=${'b'.repeat(64)}` }))
  assert.equal(missing.statusCode, 404)
  assert.equal(JSON.parse(missing.body).code, 'file-not-in-session')
  // 400：非法/多余参数
  for (const badPath of [
    '/mobile/api/session-file?sessionId=s1&attachmentId=xyz',
    '/mobile/api/session-file?attachmentId=' + imageId,
    `/mobile/api/session-file?sessionId=s1&attachmentId=${imageId}&foo=1`,
    `/mobile/api/session-file?sessionId=${'x'.repeat(201)}&attachmentId=${imageId}`,
  ]) {
    const bad = await invoke(routes[0].handler, createReq({ url: badPath }))
    assert.equal(bad.statusCode, 400, badPath)
    assert.equal(JSON.parse(bad.body).code, 'invalid-reference')
  }
  // 503：附件服务缺失
  services.attachments = null
  const unsupported = await invoke(routes[0].handler, createReq({ url: imagePath }))
  assert.equal(unsupported.statusCode, 503)
  assert.equal(JSON.parse(unsupported.body).code, 'files-unsupported')
  services.attachments = {
    readImage: async (ref) => ({ ref, data: imageBytes }),
    readFileStream: (ref) => (async function* () {
      yield Buffer.from('FILEDATA')
    })(),
  }

  // 7) DSH 鉴权失败 → 401
  rejectStatus.value = 401
  const denied = await invoke(routes[0].handler, createReq({ url: '/mobile/api/status' }))
  assert.equal(denied.statusCode, 401)
  rejectStatus.value = undefined

  // 8) 卸载：网关子进程停止（端口不再可访问）
  disposeAll()
  await waitFor(async () => {
    try {
      await fetch(`http://127.0.0.1:${gatewayPort}/health`, { signal: AbortSignal.timeout(300) })
      return false
    } catch {
      return true
    }
  }, { timeout: 8000, message: 'gateway stopped after dispose' })
  await sleep(50)
})
