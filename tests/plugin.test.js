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
  const res = {
    statusCode: null,
    headers: {},
    body: '',
    headersSent: false,
    writableEnded: false,
    setHeader(name, value) {
      this.headers[String(name).toLowerCase()] = value
    },
    getHeader(name) {
      return this.headers[String(name).toLowerCase()]
    },
    writeHead(status, headers = {}) {
      this.statusCode = status
      this.headersSent = true
      for (const [key, value] of Object.entries(headers)) this.headers[key.toLowerCase()] = value
    },
    end(chunk) {
      if (chunk) this.body += Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk)
      this.writableEnded = true
    },
  }
  return res
}

function createReq({ method = 'GET', url = '/mobile', headers = {} } = {}) {
  return { method, url, headers, socket: { remoteAddress: '127.0.0.1' } }
}

async function invoke(handler, req) {
  const res = createRes()
  handler(req, res)
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
  const ctx = {
    logger: { info() {}, warn() {}, error() {} },
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
  assert.match(html.headers['content-security-policy'], /default-src 'self'/)
  const js = await invoke(routes[0].handler, createReq({ url: '/mobile/admin.js' }))
  assert.equal(js.statusCode, 200)
  assert.match(js.body, /mobile\/api\/status/)

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

  // 5) DSH 鉴权失败 → 401
  rejectStatus.value = 401
  const denied = await invoke(routes[0].handler, createReq({ url: '/mobile/api/status' }))
  assert.equal(denied.statusCode, 401)
  rejectStatus.value = undefined

  // 6) 卸载：网关子进程停止（端口不再可访问）
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
