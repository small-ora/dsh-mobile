/**
 * tests/admin-devices.test.js — 插件主机面板的设备管理（gateway 侧 admin 接口）。
 *
 * 覆盖：
 *  - GET /api/admin/devices：adminToken 鉴权（缺失/错误 → 401）、字段（online、无 tokenHash）、按 lastSeenAt 倒序；
 *  - POST /api/admin/devices/revoke：撤销设备 → 原 device token 401、流/WS 关闭、404/400；
 *  - GET /api/admin/overview：streamHub.connected / devices 计数；
 *  - /health 的 upstream.ok = upstream.healthy || streamHub.connected（空闲 60s 不再误报 false）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { connectWebSocket } from '../gateway/ws.mjs'
import { api, loginReady, readJson, startFakeUpstream, startTestGateway, waitFor } from './helpers.mjs'

const ADMIN_TOKEN = 'test-admin-token-0123456789'

function adminHeaders(token = ADMIN_TOKEN) {
  return token ? { 'x-admin-token': token } : {}
}

async function adminGet(gateway, pathname, token = ADMIN_TOKEN) {
  return readJson(await fetch(`${gateway.url}${pathname}`, { headers: adminHeaders(token) }))
}

async function adminPost(gateway, pathname, body, token = ADMIN_TOKEN) {
  return readJson(
    await fetch(`${gateway.url}${pathname}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...adminHeaders(token) },
      body: JSON.stringify(body),
    }),
  )
}

test('admin 设备列表：鉴权 401、字段/排序/无 tokenHash', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, {
    upstream,
    gatewayOptions: { adminToken: ADMIN_TOKEN, hubOptions: { retryDelays: [20] } },
  })
  const a = await loginReady(gateway, { deviceName: 'Admin-A' })
  // 让 A 的 lastSeenAt 更新，B 后登录 → 列表应按 lastSeenAt 倒序（B 在前）
  const b = await loginReady(gateway, { deviceName: 'Admin-B' })
  assert.equal(a.status, 200)
  assert.equal(b.status, 200)

  const missing = await readJson(await fetch(`${gateway.url}/api/admin/devices`))
  assert.equal(missing.status, 401)
  assert.equal(missing.json.error, 'unauthorized')
  const wrong = await adminGet(gateway, '/api/admin/devices', 'wrong-token')
  assert.equal(wrong.status, 401)

  const listed = await adminGet(gateway, '/api/admin/devices')
  assert.equal(listed.status, 200)
  assert.equal(listed.json.ok, true)
  assert.equal(listed.json.items.length, 2)
  for (const item of listed.json.items) {
    assert.equal(typeof item.id, 'string')
    assert.equal(typeof item.name, 'string')
    assert.equal(typeof item.online, 'boolean')
    assert.equal('tokenHash' in item, false, '管理接口不得返回 tokenHash')
  }
  assert.deepEqual(
    listed.json.items.map((item) => item.name),
    ['Admin-B', 'Admin-A'],
    '按 lastSeenAt 倒序',
  )
  assert.ok(listed.json.items.every((item) => item.online === true), '刚登录的设备应在线')

  // overview：流中心状态 + 设备计数
  const overview = await adminGet(gateway, '/api/admin/overview')
  assert.equal(overview.status, 200)
  assert.equal(overview.json.streamHub.connected, true)
  assert.equal(overview.json.devices.total, 2)
  assert.equal(overview.json.devices.online, 2)
  assert.equal(typeof overview.json.upstream.ok, 'boolean')

  // /health：hub 已连接但 upstream.healthy 空闲回落时，upstream.ok 仍应为 true
  gateway.upstream.lastOkAt = 0
  const health = await readJson(await fetch(`${gateway.url}/health`))
  assert.equal(health.status, 200)
  assert.equal(gateway.streams.connected, true)
  assert.equal(health.json.upstream.ok, true, 'streamHub 已连接即视为上游可用')

  // 无效 id
  const badBody = await adminPost(gateway, '/api/admin/devices/revoke', {})
  assert.equal(badBody.status, 400)
  const notFound = await adminPost(gateway, '/api/admin/devices/revoke', { id: 'no-such-device' })
  assert.equal(notFound.status, 404)
  assert.equal(notFound.json.error, 'device-not-found')
})

test('admin 撤销设备：device token 401、流与 WS 关闭', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, {
    upstream,
    gatewayOptions: { adminToken: ADMIN_TOKEN, hubOptions: { retryDelays: [20] } },
  })
  const a = await loginReady(gateway, { deviceName: 'Revoke-Target' })
  const b = await loginReady(gateway, { deviceName: 'Revoke-Keeper' })
  assert.equal(a.status, 200)

  // A 打开一个长轮询流 + 一条 WS
  const open = await readJson(
    await api(gateway, a.json.token, '/api/stream/open', { method: 'POST', body: { endpoint: '$events', args: {} } }),
  )
  assert.equal(open.status, 200)
  const ticket = await readJson(await api(gateway, a.json.token, '/api/ws-ticket'))
  const ws = await connectWebSocket(`ws://127.0.0.1:${gateway.port}/api/ws?ticket=${encodeURIComponent(ticket.json.ticket)}`, {
    timeoutMs: 3000,
  })
  t.after(() => ws.terminate())
  const wsClosed = new Promise((resolve) => ws.once('close', resolve))
  assert.equal(gateway.streams.list({ ownerDeviceId: a.json.deviceId }).length, 1)

  const revoked = await adminPost(gateway, '/api/admin/devices/revoke', { id: a.json.deviceId })
  assert.equal(revoked.status, 200)
  assert.deepEqual(revoked.json, { ok: true, revoked: true })

  // A 的流被取消、WS 收到 4401
  assert.equal(gateway.streams.list({ ownerDeviceId: a.json.deviceId }).length, 0)
  const closeInfo = await Promise.race([
    wsClosed,
    new Promise((_, reject) => setTimeout(() => reject(new Error('ws close timeout')), 3000)),
  ])
  assert.equal(closeInfo.code, 4401)
  // A 的 device token 立即失效；B 不受影响
  assert.equal((await readJson(await api(gateway, a.json.token, '/api/me'))).status, 401)
  assert.equal((await readJson(await api(gateway, b.json.token, '/api/me'))).status, 200)
  const after = await adminGet(gateway, '/api/admin/devices')
  assert.deepEqual(
    after.json.items.map((item) => item.name),
    ['Revoke-Keeper'],
  )
})

test('admin 撤销：无效/缺失 admin token 一律 401 且不生效', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, {
    upstream,
    gatewayOptions: { adminToken: ADMIN_TOKEN, hubOptions: { retryDelays: [20] } },
  })
  const a = await loginReady(gateway, { deviceName: 'Protected' })
  const missing = await readJson(
    await fetch(`${gateway.url}/api/admin/devices/revoke`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: a.json.deviceId }),
    }),
  )
  assert.equal(missing.status, 401)
  const wrong = await adminPost(gateway, '/api/admin/devices/revoke', { id: a.json.deviceId }, 'bad-token')
  assert.equal(wrong.status, 401)
  // 设备仍然有效
  assert.equal((await readJson(await api(gateway, a.json.token, '/api/me'))).status, 200)
})
