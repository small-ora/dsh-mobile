/**
 * tests/proxy-trust.test.js — 反代可信边界（P1 安全修复）。
 *
 * 修复目标：直连网关的攻击者伪造 `X-Forwarded-For` / `cf-connecting-ip` 不能换掉
 * 登录限速的 IP 桶；只有“直连对端可信”（回环，或 DSH_MOBILE_TRUST_PROXY_CIDRS 命中）
 * 时才使用转发头，且 XFF 从右向左跳过可信代理（忽略客户端自造左链）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import os from 'node:os'
import {
  createProxyTrust,
  isLoopbackIp,
  matchCidr,
  normalizeIp,
  parseCidr,
  parseCidrList,
} from '../gateway/proxy-trust.mjs'
import { login, readJson, startTestGateway } from './helpers.mjs'

function fakeReq({ peer = '127.0.0.1', headers = {} } = {}) {
  return { socket: { remoteAddress: peer }, headers }
}

function lanIpv4() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const info of list || []) {
      if ((info.family === 'IPv4' || info.family === 4) && !info.internal) return info.address
    }
  }
  return null
}

test('IP 归一化与回环判断', () => {
  assert.equal(normalizeIp('127.0.0.1'), '127.0.0.1')
  assert.equal(normalizeIp('::1'), '::1')
  assert.equal(normalizeIp('::ffff:127.0.0.1'), '127.0.0.1')
  assert.equal(normalizeIp('::ffff:192.168.1.9'), '192.168.1.9')
  assert.equal(normalizeIp('2001:DB8::5'), '2001:db8::5')
  assert.equal(normalizeIp('bad-ip'), null)
  assert.equal(normalizeIp(undefined), null)
  assert.equal(isLoopbackIp('127.0.0.1'), true)
  assert.equal(isLoopbackIp('127.9.8.7'), true)
  assert.equal(isLoopbackIp('::1'), true)
  assert.equal(isLoopbackIp('::ffff:127.0.0.1'), true)
  assert.equal(isLoopbackIp('192.168.1.9'), false)
  assert.equal(isLoopbackIp('10.0.0.1'), false)
})

test('CIDR 解析与匹配（IPv4 / IPv6 / 非法）', () => {
  const v4 = parseCidr('10.0.0.0/8')
  assert.equal(v4.family, 4)
  assert.equal(v4.prefix, 8)
  assert.equal(matchCidr('10.1.2.3', v4), true)
  assert.equal(matchCidr('11.1.2.3', v4), false)
  assert.equal(matchCidr('10.1.2.3', parseCidr('192.168.0.0/16')), false)
  assert.equal(matchCidr('192.168.31.170', parseCidr('192.168.0.0/16')), true)
  assert.equal(matchCidr('192.168.31.170', parseCidr('192.168.31.0/24')), true)
  assert.equal(matchCidr('192.168.32.1', parseCidr('192.168.31.0/24')), false)
  assert.equal(matchCidr('::1', parseCidr('::1/128')), true)
  assert.equal(matchCidr('2001:db8::5', parseCidr('2001:db8::/32')), true)
  assert.equal(matchCidr('2001:db9::5', parseCidr('2001:db8::/32')), false)
  assert.equal(parseCidr('10.0.0.0'), null)
  assert.equal(parseCidr('10.0.0.0/33'), null)
  assert.equal(parseCidr('not-a-cidr/8'), null)
  assert.equal(parseCidrList('10.0.0.0/8, ::1/128, bad').length, 2)
  assert.equal(parseCidrList('').length, 0)
})

test('不可信直连：完全忽略 cf-connecting-ip / X-Forwarded-For', () => {
  const trust = createProxyTrust({ env: {} })
  for (const forged of ['203.0.113.1', '203.0.113.2', '203.0.113.3']) {
    const resolved = trust.resolve(
      fakeReq({ peer: '192.168.1.9', headers: { 'x-forwarded-for': forged, 'cf-connecting-ip': forged } }),
    )
    assert.equal(resolved.ip, '192.168.1.9')
    assert.equal(resolved.source, 'socket')
    assert.equal(resolved.trusted, false)
  }
  // IPv4-mapped 直连地址同样归一化
  assert.equal(trust.resolve(fakeReq({ peer: '::ffff:192.168.1.9', headers: { 'x-forwarded-for': '203.0.113.1' } })).ip, '192.168.1.9')
  assert.deepEqual(trust.describe(), { mode: 'loopback', trustedCidrCount: 0 })
})

test('回环可信：cf 优先、XFF 取最右侧不可信地址（忽略伪造左链）', () => {
  const trust = createProxyTrust({ env: {} })
  // 客户端自造左链 + 代理追加的真实地址在右端
  const rightmost = trust.resolve(fakeReq({ headers: { 'x-forwarded-for': '6.6.6.6, 203.0.113.7' } }))
  assert.equal(rightmost.ip, '203.0.113.7')
  assert.equal(rightmost.source, 'x-forwarded-for')
  // cf-connecting-ip 优先于 XFF
  const cf = trust.resolve(fakeReq({ headers: { 'cf-connecting-ip': '198.51.100.5', 'x-forwarded-for': '6.6.6.6' } }))
  assert.equal(cf.ip, '198.51.100.5')
  assert.equal(cf.source, 'cf-connecting-ip')
  // 非法 cf → 退回 XFF
  assert.equal(trust.resolve(fakeReq({ headers: { 'cf-connecting-ip': 'not-an-ip', 'x-forwarded-for': '203.0.113.8' } })).ip, '203.0.113.8')
  // 无任何转发头 → 回环自身
  assert.equal(trust.resolve(fakeReq()).ip, '127.0.0.1')
  // IPv4-mapped 回环直连也按回环信任
  assert.equal(trust.resolve(fakeReq({ peer: '::ffff:127.0.0.1', headers: { 'x-forwarded-for': '203.0.113.9' } })).ip, '203.0.113.9')
})

test('CIDR：命中才信任转发头，不命中忽略；XFF 跳过可信代理', () => {
  const trust = createProxyTrust({ env: { DSH_MOBILE_TRUST_PROXY_CIDRS: '10.0.0.0/8, 192.168.50.0/24' } })
  assert.deepEqual(trust.describe(), { mode: 'custom', trustedCidrCount: 2 })
  const hit = trust.resolve(fakeReq({ peer: '10.9.8.7', headers: { 'x-forwarded-for': '203.0.113.10' } }))
  assert.equal(hit.trusted, true)
  assert.equal(hit.ip, '203.0.113.10')
  const miss = trust.resolve(fakeReq({ peer: '192.168.1.9', headers: { 'x-forwarded-for': '203.0.113.10' } }))
  assert.equal(miss.trusted, false)
  assert.equal(miss.ip, '192.168.1.9')
  // 链右端是可信代理（10.0.0.9）→ 跳过，取更左侧不可信地址
  const skip = trust.resolve(fakeReq({ peer: '10.0.0.1', headers: { 'x-forwarded-for': '1.2.3.4, 10.0.0.9' } }))
  assert.equal(skip.ip, '1.2.3.4')
  // 整条链都可信 → 退化为最左端
  const allTrusted = trust.resolve(fakeReq({ peer: '10.0.0.1', headers: { 'x-forwarded-for': '10.0.0.2, 10.0.0.9' } }))
  assert.equal(allTrusted.ip, '10.0.0.2')
})

test('HTTP：回环直连时 XFF 按真实客户端分桶（同桶第 6 次 429）', async (t) => {
  const { gateway } = await startTestGateway(t)
  const health = await readJson(await fetch(`${gateway.url}/health`))
  assert.deepEqual(health.json.proxyTrust, { mode: 'loopback', trustedCidrCount: 0 })

  for (let i = 0; i < 5; i++) {
    const bad = await login(gateway, { password: 'bad-password', headers: { 'x-forwarded-for': '203.0.113.10' } })
    assert.equal(bad.status, 401)
  }
  const limited = await login(gateway, { password: 'bad-password', headers: { 'x-forwarded-for': '203.0.113.10' } })
  assert.equal(limited.status, 429)
  // 换一个 XFF 来源是独立桶（回环直连 = 可信反代场景）
  const other = await login(gateway, { password: 'bad-password', headers: { 'x-forwarded-for': '203.0.113.11' } })
  assert.equal(other.status, 401)
})

test('HTTP：不可信直连（局域网地址）轮换 XFF 仍 429，不能绕过限速', async (t) => {
  const ip = lanIpv4()
  if (!ip) return t.skip('no non-internal IPv4 address')
  const { gateway } = await startTestGateway(t, { gatewayOptions: { host: '0.0.0.0' } })
  const base = `http://${ip}:${gateway.port}`
  const health = await readJson(await fetch(`${base}/health`))
  assert.equal(health.status, 200)
  assert.equal(health.json.proxyTrust.mode, 'loopback')

  // 每次换一个伪造 XFF → 由于直连对端不可信，全部落到 socket IP 同一个桶
  for (let i = 0; i < 5; i++) {
    const response = await fetch(`${base}/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-forwarded-for': `203.0.113.${100 + i}`, 'cf-connecting-ip': `198.51.100.${100 + i}` },
      body: JSON.stringify({ password: 'bad-password', deviceName: 'spoofed' }),
    })
    assert.equal(response.status, 401, `attempt ${i + 1} should be 401 (untrusted direct peer)`)
  }
  const blocked = await fetch(`${base}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.200', 'cf-connecting-ip': '198.51.100.200' },
    body: JSON.stringify({ password: 'bad-password', deviceName: 'spoofed' }),
  })
  const payload = await readJson(blocked)
  assert.equal(payload.status, 429, '伪造 XFF 轮换后第 6 次仍应 429')
  assert.equal(payload.json.error, 'rate-limited')
})

test('HTTP：DSH_MOBILE_TRUST_PROXY_CIDRS 命中时信任转发头并出现在 /health', async (t) => {
  const ip = lanIpv4()
  if (!ip) return t.skip('no non-internal IPv4 address')
  const cidr = `${ip.split('.').slice(0, 3).join('.')}.0/24`
  const { gateway } = await startTestGateway(t, {
    env: { DSH_MOBILE_TRUST_PROXY_CIDRS: `${cidr}, bad-entry` },
    gatewayOptions: { host: '0.0.0.0' },
  })
  const base = `http://${ip}:${gateway.port}`
  const health = await readJson(await fetch(`${base}/health`))
  assert.deepEqual(health.json.proxyTrust, { mode: 'custom', trustedCidrCount: 1 })

  // 可信代理下：每个 XFF 来源独立分桶 → 轮换时均为 401，不共享计数
  for (let i = 0; i < 5; i++) {
    const response = await fetch(`${base}/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-forwarded-for': `203.0.113.${50 + i}` },
      body: JSON.stringify({ password: 'bad-password', deviceName: 'proxied' }),
    })
    assert.equal(response.status, 401, `rotating XFF ${i} should be 401`)
  }
  // 固定一个 XFF 来源，第 6 次 429（证明按 XFF 分桶）
  let limitedStatus = null
  for (let i = 0; i < 6; i++) {
    const response = await fetch(`${base}/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.77' },
      body: JSON.stringify({ password: 'bad-password', deviceName: 'proxied' }),
    })
    limitedStatus = response.status
  }
  assert.equal(limitedStatus, 429)
})
