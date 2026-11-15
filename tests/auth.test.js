/**
 * tests/auth.test.js — scrypt 口令、设备 token 只存哈希、限速/锁定、撤销、WS ticket 滑动过期。
 * 对应 SPEC v2 AC2/AC10 与 docs/interfaces.md §2.1/§7。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import {
  AuthService,
  SCRYPT_PARAMS,
  hashPassword,
  hashPasswordAsync,
  hashToken,
  newDeviceToken,
  timingSafeEqualString,
  validatePasswordStrength,
  verifyPassword,
  verifyPasswordAsync,
} from '../gateway/auth.mjs'
import { api, login, readJson, startFakeUpstream, startTestGateway } from './helpers.mjs'

const PASSWORD = 'correct-horse-123'

function makeConfig(password = PASSWORD) {
  return {
    version: 1,
    mustChangePassword: true,
    password: hashPassword(password),
    devices: [],
  }
}

test('scrypt 参数固定且 verifyPassword 常量时间校验', () => {
  const record = hashPassword(PASSWORD)
  assert.equal(record.algo, 'scrypt')
  assert.equal(record.N, SCRYPT_PARAMS.N)
  assert.equal(record.r, SCRYPT_PARAMS.r)
  assert.equal(record.p, SCRYPT_PARAMS.p)
  assert.equal(SCRYPT_PARAMS.N, 16384)
  assert.equal(SCRYPT_PARAMS.r, 8)
  assert.equal(SCRYPT_PARAMS.p, 1)
  assert.equal(verifyPassword(PASSWORD, record), true)
  assert.equal(verifyPassword('wrong-password', record), false)
  assert.equal(verifyPassword(PASSWORD, { algo: 'plain' }), false)
  assert.equal(verifyPassword(PASSWORD, null), false)
  // 同口令两次 hash 的 salt 不同
  const second = hashPassword(PASSWORD)
  assert.notEqual(record.salt, second.salt)
  assert.notEqual(record.hash, second.hash)
  assert.equal(timingSafeEqualString('abc', 'abc'), true)
  assert.equal(timingSafeEqualString('abc', 'abd'), false)
  assert.equal(timingSafeEqualString('abc', 'abcd'), false)
})

test('设备 token 32B base64url 且配置只存 SHA-256', async () => {
  const config = makeConfig()
  const persistCalls = []
  const auth = new AuthService({ config, persist: () => persistCalls.push(1), secret: crypto.randomBytes(32) })
  const result = await auth.login({ password: PASSWORD, deviceName: 'Pixel', platform: 'android', ip: '10.0.0.1' })
  assert.equal(result.ok, true)
  assert.equal(typeof result.token, 'string')
  assert.equal(result.token.length, 43)
  assert.match(result.token, /^[A-Za-z0-9_-]{43}$/)
  assert.equal(Buffer.from(result.token, 'base64url').length, 32)
  assert.equal(config.devices.length, 1)
  const device = config.devices[0]
  assert.equal(device.tokenHash, hashToken(result.token))
  assert.equal(device.tokenHash.length, 64)
  assert.equal(JSON.stringify(config).includes(result.token), false)
  assert.equal(device.name, 'Pixel')
  assert.equal(persistCalls.length, 1)
  // 用 token 鉴权返回脱敏设备信息（不含 tokenHash）
  const authenticated = auth.authenticate(result.token)
  assert.equal(authenticated.id, device.id)
  assert.equal('tokenHash' in authenticated, false)
  assert.equal(auth.authenticate('x'.repeat(43)), null)
})

test('登录限速：同 IP 每分钟 5 次，第 6 次 429；窗口后恢复', async () => {
  let clock = 1_700_000_000_000
  const config = makeConfig()
  const auth = new AuthService({ config, now: () => clock, persist: () => {}, secret: crypto.randomBytes(32) })
  for (let i = 0; i < 5; i++) {
    const result = await auth.login({ password: 'bad-password', ip: '1.2.3.4' })
    assert.equal(result.ok, false)
    assert.equal(result.status, 401)
    assert.equal(result.error, 'invalid-password')
  }
  const limited = await auth.login({ password: 'bad-password', ip: '1.2.3.4' })
  assert.equal(limited.ok, false)
  assert.equal(limited.status, 429)
  assert.equal(limited.error, 'rate-limited')
  assert.ok(limited.retryAfterMs > 0 && limited.retryAfterMs <= 60_000)
  // 其它 IP 不受影响（在窗口内第 6 次也应 429；这里验证不同 IP 独占计数）
  const otherIp = await auth.login({ password: 'bad-password', ip: '5.6.7.8' })
  assert.equal(otherIp.status, 401)
  // 60s 窗口过后恢复一次尝试
  clock += 61_000
  const afterWindow = await auth.login({ password: PASSWORD, ip: '1.2.3.4' })
  assert.equal(afterWindow.ok, true)
})

test('连续 10 次失败锁 15 分钟，锁定期正确口令也返回 423', async () => {
  let clock = 1_700_000_000_000
  const config = makeConfig()
  const auth = new AuthService({ config, now: () => clock, persist: () => {}, secret: crypto.randomBytes(32) })
  let locked = null
  for (let i = 0; i < 10; i++) {
    if (i > 0 && i % 5 === 0) clock += 61_000
    const result = await auth.login({ password: 'bad-password', ip: '9.9.9.9' })
    if (result.status === 423) {
      locked = result
      assert.equal(i, 9)
    }
  }
  assert.ok(locked, 'expected 423 on 10th failure')
  assert.equal(locked.error, 'locked')
  assert.ok(locked.until >= clock)
  const duringLock = await auth.login({ password: PASSWORD, ip: '9.9.9.9' })
  assert.equal(duringLock.status, 423)
  clock = locked.until + 1
  const afterLock = await auth.login({ password: PASSWORD, ip: '9.9.9.9' })
  assert.equal(afterLock.ok, true)
})

test('撤销设备 / 登出后原 token 立即失效', async () => {
  const config = makeConfig()
  const auth = new AuthService({ config, persist: () => {}, secret: crypto.randomBytes(32) })
  const first = await auth.login({ password: PASSWORD, deviceName: 'A', ip: '1.1.1.1' })
  const second = await auth.login({ password: PASSWORD, deviceName: 'B', ip: '1.1.1.1' })
  assert.equal(auth.listDevices().length, 2)
  assert.ok(auth.authenticate(first.token))
  assert.equal(auth.revokeDevice(first.device.id), true)
  assert.equal(auth.authenticate(first.token), null)
  assert.ok(auth.authenticate(second.token))
  assert.equal(auth.logout(second.token), true)
  assert.equal(auth.authenticate(second.token), null)
  assert.equal(auth.listDevices().length, 0)
})

test('设备 token 30 天滑动过期', async () => {
  let clock = 1_700_000_000_000
  const config = makeConfig()
  const auth = new AuthService({ config, now: () => clock, persist: () => {}, secret: crypto.randomBytes(32) })
  const result = await auth.login({ password: PASSWORD, ip: '2.2.2.2' })
  clock += 29 * 24 * 60 * 60 * 1000
  assert.ok(auth.authenticate(result.token), '29 天内应有效（并滑动续期）')
  clock += 29 * 24 * 60 * 60 * 1000
  assert.ok(auth.authenticate(result.token), '滑动续期后 29 天应继续有效')
  clock += 31 * 24 * 60 * 60 * 1000
  assert.equal(auth.authenticate(result.token), null, '31 天未使用应过期')
  assert.equal(auth.authenticate(result.token), null)
})

test('WS ticket：HMAC 30 秒、不落盘、篡改/过期拒绝', async () => {
  let clock = 1_700_000_000_000
  const config = makeConfig()
  const auth = new AuthService({ config, now: () => clock, persist: () => {}, secret: crypto.randomBytes(32) })
  const result = await auth.login({ password: PASSWORD, ip: '3.3.3.3' })
  const { ticket, expiresAt } = auth.issueTicket(result.device.id)
  assert.equal(typeof ticket, 'string')
  assert.ok(ticket.includes('.'))
  assert.equal(expiresAt - clock, 30_000)
  assert.equal(auth.verifyTicket(ticket), result.device.id)
  // ticket 不写入 config.json
  assert.equal(JSON.stringify(config).includes(ticket), false)
  // 篡改
  const tampered = ticket.replace(/.$/, (ch) => (ch === 'A' ? 'B' : 'A'))
  assert.equal(auth.verifyTicket(tampered), null)
  // 过期
  clock += 30_001
  assert.equal(auth.verifyTicket(ticket), null)
  // 撤销设备后 ticket 也失效
  const other = await auth.login({ password: PASSWORD, ip: '3.3.3.3' })
  const fresh = auth.issueTicket(other.device.id)
  auth.revokeDevice(other.device.id)
  assert.equal(auth.verifyTicket(fresh.ticket), null)
})

test('修改口令：错误旧口令 401、弱口令 400、成功清除 mustChangePassword', async () => {
  const config = makeConfig()
  const auth = new AuthService({ config, persist: () => {}, secret: crypto.randomBytes(32) })
  assert.equal(auth.mustChangePassword, true)
  const wrong = await auth.changePassword('not-the-password', 'new-strong-password')
  assert.equal(wrong.ok, false)
  assert.equal(wrong.status, 401)
  const weak = await auth.changePassword(PASSWORD, 'short')
  assert.equal(weak.ok, false)
  assert.equal(weak.status, 400)
  assert.equal(weak.error, 'password-too-short')
  assert.deepEqual(validatePasswordStrength('long-enough-1'), { ok: true })
  const ok = await auth.changePassword(PASSWORD, 'new-strong-password')
  assert.equal(ok.ok, true)
  assert.equal(config.mustChangePassword, false)
  assert.equal(verifyPassword('new-strong-password', config.password), true)
  assert.equal(verifyPassword(PASSWORD, config.password), false)
  const relogin = await auth.login({ password: 'new-strong-password', ip: '4.4.4.4' })
  assert.equal(relogin.ok, true)
})

test('网关 HTTP：/health 免鉴权、/api/rpc 无 token 401、登录/me/撤销设备', async (t) => {
  let clock = 1_700_000_000_000
  const { gateway } = await startTestGateway(t, {
    gatewayOptions: { disableStreams: true, now: () => clock },
  })
  assert.ok(gateway.initialPassword, '首次启动应生成初始口令')
  assert.equal(gateway.initialPassword.length, 10)
  // 首次启动文件权限
  const fs = await import('node:fs')
  const path = await import('node:path')
  const dirMode = fs.statSync(gateway.home).mode & 0o777
  const configMode = fs.statSync(path.join(gateway.home, 'config.json')).mode & 0o777
  const passwordMode = fs.statSync(path.join(gateway.home, 'initial-password.txt')).mode & 0o777
  assert.equal(dirMode.toString(8), '700')
  assert.equal(configMode.toString(8), '600')
  assert.equal(passwordMode.toString(8), '600')

  const health = await readJson(await fetch(`${gateway.url}/health`))
  assert.equal(health.status, 200)
  assert.equal(health.json.ok, true)
  assert.equal(health.json.service, 'dsh-mobile')
  assert.equal('tunnel' in health.json, false, 'v3 健康检查不再返回隧道字段')

  const noToken = await readJson(await api(gateway, null, '/api/rpc', { method: 'POST', body: { method: 'session/list', args: {} } }))
  assert.equal(noToken.status, 401)
  assert.equal(noToken.json.error, 'unauthorized')

  // 5 次错误口令 401，第 6 次 429
  for (let i = 0; i < 5; i++) {
    const bad = await login(gateway, { password: 'bad-password' })
    assert.equal(bad.status, 401)
    assert.equal(bad.json.error, 'invalid-password')
  }
  const limited = await login(gateway, { password: 'bad-password' })
  assert.equal(limited.status, 429)
  assert.equal(limited.json.error, 'rate-limited')
  assert.ok(Number(limited.headers.get('retry-after')) >= 1)

  // 窗口过后正确登录
  clock += 61_000
  const ok = await login(gateway, { password: gateway.initialPassword, deviceName: 'HTTP Pixel' })
  assert.equal(ok.status, 200)
  assert.equal(typeof ok.json.token, 'string')
  assert.equal(ok.json.deviceName, 'HTTP Pixel')
  assert.equal(ok.json.mustChangePassword, true)
  assert.equal(typeof ok.json.deviceId, 'string')
  assert.equal(ok.json.host.gatewayVersion, '0.1.10')
  assert.equal('tunnel' in ok.json.host, false)

  const me = await readJson(await api(gateway, ok.json.token, '/api/me'))
  assert.equal(me.status, 200)
  assert.equal(me.json.device.name, 'HTTP Pixel')

  // mustChangePassword 服务端强制：改密前业务接口 403
  const blockedDevices = await readJson(await api(gateway, ok.json.token, '/api/devices'))
  assert.equal(blockedDevices.status, 403)
  assert.equal(blockedDevices.json.error, 'password-change-required')

  // 改密后初始口令文件被删除、mustChangePassword 置 false，业务接口立即恢复
  const changed = await readJson(
    await api(gateway, ok.json.token, '/api/password', {
      method: 'POST',
      body: { current: gateway.initialPassword, next: 'brand-new-password-1' },
    }),
  )
  assert.equal(changed.status, 200)
  assert.equal(changed.json.ok, true)
  assert.equal(fs.existsSync(path.join(gateway.home, 'initial-password.txt')), false)

  const devices = await readJson(await api(gateway, ok.json.token, '/api/devices'))
  assert.equal(devices.status, 200)
  assert.ok(devices.json.items.some((device) => device.id === ok.json.deviceId))

  const revoked = await readJson(await api(gateway, ok.json.token, `/api/devices/${ok.json.deviceId}`, { method: 'DELETE' }))
  assert.equal(revoked.status, 200)
  const afterRevoke = await readJson(await api(gateway, ok.json.token, '/api/me'))
  assert.equal(afterRevoke.status, 401)

  const oldLogin = await login(gateway, { password: gateway.initialPassword })
  assert.equal(oldLogin.status, 401)
  const newLogin = await login(gateway, { password: 'brand-new-password-1' })
  assert.equal(newLogin.status, 200)
  assert.equal(newLogin.json.mustChangePassword, false)
})


test('异步 scrypt：hashPasswordAsync / verifyPasswordAsync 与同步版本一致', async () => {
  const record = await hashPasswordAsync('async-password-1')
  assert.equal(record.algo, 'scrypt')
  assert.equal(record.N, SCRYPT_PARAMS.N)
  assert.equal(verifyPassword('async-password-1', record), true)
  assert.equal(await verifyPasswordAsync('async-password-1', record), true)
  assert.equal(await verifyPasswordAsync('wrong-password', record), false)
  assert.equal(await verifyPasswordAsync('async-password-1', null), false)
})

test('改密接口复用登录限速：失败 6 次/分钟 → 429；成功改密后旧 token 仍可用', async (t) => {
  const { gateway } = await startTestGateway(t, { gatewayOptions: { disableStreams: true } })
  const session = await login(gateway)
  assert.equal(session.status, 200)

  for (let i = 0; i < 5; i++) {
    const failed = await readJson(
      await api(gateway, session.json.token, '/api/password', {
        method: 'POST',
        body: { current: 'wrong-current-password', next: 'new-strong-password-1' },
      }),
    )
    assert.equal(failed.status, 401)
    assert.equal(failed.json.error, 'invalid-password')
  }
  const limited = await readJson(
    await api(gateway, session.json.token, '/api/password', {
      method: 'POST',
      body: { current: 'wrong-current-password', next: 'new-strong-password-1' },
    }),
  )
  assert.equal(limited.status, 429)
  assert.equal(limited.json.error, 'rate-limited')
  assert.ok(Number(limited.headers.get('retry-after')) >= 1)

  // 换一个可信 XFF 来源（回环直连视为可信反代）正确改密
  const changed = await readJson(
    await api(gateway, session.json.token, '/api/password', {
      method: 'POST',
      headers: { 'x-forwarded-for': '203.0.113.99' },
      body: { current: gateway.initialPassword, next: 'new-strong-password-1' },
    }),
  )
  assert.equal(changed.status, 200)
  assert.equal(changed.json.ok, true)

  // 既有安全语义不变：改密不吊销已登录设备 token
  const me = await readJson(await api(gateway, session.json.token, '/api/me'))
  assert.equal(me.status, 200)
  // 新口令可登录；旧口令失败（各自独立 IP 桶，避免与上面限速窗口互相影响）
  const reloginNew = await login(gateway, { password: 'new-strong-password-1', headers: { 'x-forwarded-for': '203.0.113.100' } })
  assert.equal(reloginNew.status, 200)
  const reloginOld = await login(gateway, { password: gateway.initialPassword, headers: { 'x-forwarded-for': '203.0.113.101' } })
  assert.equal(reloginOld.status, 401)
})

test('改密重入保护：同一设备并发改密，第二个请求 409 conflict', async () => {
  const config = makeConfig()
  const auth = new AuthService({ config, persist: () => {}, secret: crypto.randomBytes(32) })
  const session = await auth.login({ password: PASSWORD, deviceName: 'reentry', ip: '6.6.6.6' })
  const first = auth.changePassword(PASSWORD, 'new-strong-password-1', { deviceId: session.device.id, ip: '6.6.6.6' })
  const second = auth.changePassword(PASSWORD, 'new-strong-password-2', { deviceId: session.device.id, ip: '6.6.6.6' })
  const [r1, r2] = await Promise.all([first, second])
  assert.equal([r1, r2].filter((result) => result.ok).length, 1)
  assert.equal([r1, r2].find((result) => !result.ok).status, 409)
})


test('mustChangePassword 服务端强制：改密前所有业务 /api/* 403，改密后立即解除', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { disableStreams: true } })
  const a = await login(gateway, { deviceName: 'MustChange-A' })
  const b = await login(gateway, { deviceName: 'MustChange-B' })
  assert.equal(a.status, 200)
  assert.equal(a.json.mustChangePassword, true)
  assert.equal(b.status, 200)

  const blocked = [
    ['/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } }],
    ['/api/pending', {}],
    ['/api/devices', {}],
    ['/api/ws-ticket', {}],
    [`/api/files?path=${encodeURIComponent(gateway.home)}`, {}],
    ['/api/stream/open', { method: 'POST', body: { endpoint: '$events', args: {} } }],
    ['/api/respond', { method: 'POST', body: { eventId: 'x', outcome: 'allowed-once' } }],
  ]
  for (const [path, init] of blocked) {
    const response = await readJson(await api(gateway, a.json.token, path, init))
    assert.equal(response.status, 403, `${path} 在 mustChangePassword 时应 403`)
    assert.equal(response.json.error, 'password-change-required')
  }
  // 白名单端点仍可用
  assert.equal((await readJson(await api(gateway, a.json.token, '/api/me'))).status, 200)
  const health = await readJson(await fetch(`${gateway.url}/health`))
  assert.equal(health.status, 200)
  assert.equal(health.json.mustChangePassword, true)

  // 改密成功后同进程立即解除，无需重新登录；其他设备 token 不受影响
  const changed = await readJson(
    await api(gateway, a.json.token, '/api/password', {
      method: 'POST',
      body: { current: gateway.initialPassword, next: 'new-strong-password-1' },
    }),
  )
  assert.equal(changed.status, 200)
  assert.equal(changed.json.ok, true)

  const rpcA = await readJson(await api(gateway, a.json.token, '/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } }))
  assert.equal(rpcA.status, 200)
  assert.equal(rpcA.json.ok, true)
  const rpcB = await readJson(await api(gateway, b.json.token, '/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } }))
  assert.equal(rpcB.status, 200)
  const files = await readJson(await api(gateway, a.json.token, `/api/files?path=${encodeURIComponent(gateway.home)}`))
  assert.equal(files.status, 200)
})
