/**
 * tests/files.test.js — 文件列表 / Range 下载 / 上传（sha256、冲突、覆盖）/ 预览 / realpath 防穿越。
 * 对应 SPEC v2 AC5 与 docs/interfaces.md §2.5。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { api, loginReady, makeTempDir, readJson, startTestGateway } from './helpers.mjs'

const sha256 = (buffer) => crypto.createHash('sha256').update(buffer).digest('hex')

async function authedFetch(gateway, token, apiPath, options = {}) {
  return fetch(`${gateway.url}${apiPath}`, {
    ...options,
    headers: { authorization: `Bearer ${token}`, ...(options.headers || {}) },
  })
}

test('文件：列表 / 下载 sha256 一致 / Range / 越权 403 / 预览 / 上传', async (t) => {
  const outsideDir = makeTempDir('dsh-mobile-outside-')
  const outsideSecret = path.join(outsideDir, 'secret.txt')
  fs.writeFileSync(outsideSecret, 'outside-secret-content')

  const { gateway } = await startTestGateway(t, {
    gatewayOptions: { disableStreams: true },
  })
  const home = gateway.home
  const dataDir = path.join(home, 'data')
  fs.mkdirSync(path.join(dataDir, 'sub'), { recursive: true })
  const hello = Buffer.from('hello dsh-mobile')
  fs.writeFileSync(path.join(dataDir, 'hello.txt'), hello)
  const binary = crypto.randomBytes(256 * 1024)
  fs.writeFileSync(path.join(dataDir, 'big.bin'), binary)
  fs.writeFileSync(path.join(dataDir, 'sub', 'nested.txt'), 'nested')
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x00])
  fs.writeFileSync(path.join(dataDir, 'image.png'), png)
  fs.writeFileSync(path.join(dataDir, 'huge.txt'), Buffer.alloc(1024 * 1024 + 1, 0x61))
  fs.symlinkSync(outsideSecret, path.join(dataDir, 'escape.txt'))

  const session = await loginReady(gateway)
  assert.equal(session.status, 200)
  const token = session.json.token

  // ---- 列表 ----
  const listing = await readJson(await authedFetch(gateway, token, `/api/files?path=${encodeURIComponent(dataDir)}`))
  assert.equal(listing.status, 200)
  assert.equal(listing.json.path, fs.realpathSync(dataDir))
  assert.ok(Array.isArray(listing.json.roots))
  assert.ok(listing.json.roots.includes(fs.realpathSync(home)))
  const names = listing.json.entries.map((entry) => entry.name)
  for (const name of ['hello.txt', 'big.bin', 'image.png', 'sub']) assert.ok(names.includes(name), `列表应包含 ${name}`)
  const dirEntry = listing.json.entries.find((entry) => entry.name === 'sub')
  assert.equal(dirEntry.type, 'dir')
  const fileEntry = listing.json.entries.find((entry) => entry.name === 'hello.txt')
  assert.equal(fileEntry.type, 'file')
  assert.equal(fileEntry.size, hello.length)
  // 目录在前
  assert.equal(listing.json.entries[0].type, 'dir')

  // 未经鉴权
  const unauth = await readJson(await fetch(`${gateway.url}/api/files?path=${encodeURIComponent(dataDir)}`))
  assert.equal(unauth.status, 401)

  // ---- 下载：sha256 与源文件一致 ----
  const download = await authedFetch(gateway, token, `/api/files/download?path=${encodeURIComponent(path.join(dataDir, 'big.bin'))}`)
  assert.equal(download.status, 200)
  assert.equal(download.headers.get('accept-ranges'), 'bytes')
  assert.match(download.headers.get('content-disposition') || '', /attachment/)
  const downloaded = Buffer.from(await download.arrayBuffer())
  assert.equal(downloaded.length, binary.length)
  assert.equal(sha256(downloaded), sha256(binary))

  // ---- Range ----
  const partial = await authedFetch(gateway, token, `/api/files/download?path=${encodeURIComponent(path.join(dataDir, 'hello.txt'))}`, {
    headers: { range: 'bytes=0-4' },
  })
  assert.equal(partial.status, 206)
  assert.equal(partial.headers.get('content-range'), `bytes 0-4/${hello.length}`)
  assert.equal(await partial.text(), 'hello')
  const invalidRange = await authedFetch(gateway, token, `/api/files/download?path=${encodeURIComponent(path.join(dataDir, 'hello.txt'))}`, {
    headers: { range: 'bytes=99999-100000' },
  })
  assert.equal(invalidRange.status, 416)

  // ---- 越权：词法穿越 / 符号链接穿越 ----
  const traversal = await readJson(await authedFetch(gateway, token, `/api/files?path=${encodeURIComponent('../../etc')}`))
  assert.equal(traversal.status, 403)
  assert.equal(traversal.json.error, 'forbidden-path')
  const traversalDownload = await readJson(
    await authedFetch(gateway, token, `/api/files/download?path=${encodeURIComponent(path.join(home, '..', '..', 'etc', 'passwd'))}`),
  )
  assert.equal(traversalDownload.status, 403)
  const symlinkEscape = await readJson(
    await authedFetch(gateway, token, `/api/files/download?path=${encodeURIComponent(path.join(dataDir, 'escape.txt'))}`),
  )
  assert.equal(symlinkEscape.status, 403)
  assert.equal(symlinkEscape.json.error, 'forbidden-path')
  const outsideList = await readJson(await authedFetch(gateway, token, `/api/files?path=${encodeURIComponent(outsideDir)}`))
  assert.equal(outsideList.status, 403)

  // ---- 预览 ----
  const textPreview = await authedFetch(gateway, token, `/api/files/preview?path=${encodeURIComponent(path.join(dataDir, 'hello.txt'))}`)
  assert.equal(textPreview.status, 200)
  assert.match(textPreview.headers.get('content-type') || '', /text\/plain/)
  assert.equal(await textPreview.text(), 'hello dsh-mobile')
  const pngPreview = await authedFetch(gateway, token, `/api/files/preview?path=${encodeURIComponent(path.join(dataDir, 'image.png'))}`)
  assert.equal(pngPreview.status, 200)
  assert.equal(pngPreview.headers.get('content-type'), 'image/png')
  const bigPreview = await readJson(await authedFetch(gateway, token, `/api/files/preview?path=${encodeURIComponent(path.join(dataDir, 'huge.txt'))}`))
  assert.equal(bigPreview.status, 415)
  // 未识别扩展名二进制（含 NUL）→ 415
  const binaryPreview = await readJson(await authedFetch(gateway, token, `/api/files/preview?path=${encodeURIComponent(path.join(dataDir, 'big.bin'))}`))
  assert.equal(binaryPreview.status, 415)

  // ---- 上传 ----
  const payload = Buffer.from('uploaded-by-test-中文内容')
  const expectedSha = sha256(payload)
  const uploadPath = `/api/files/upload?path=${encodeURIComponent(dataDir)}&name=${encodeURIComponent('上传文件.txt')}`
  const uploaded = await readJson(
    await fetch(`${gateway.url}${uploadPath}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'x-content-sha256': expectedSha },
      body: payload,
    }),
  )
  assert.equal(uploaded.status, 201)
  assert.equal(uploaded.json.ok, true)
  assert.equal(uploaded.json.sha256, expectedSha)
  assert.equal(uploaded.json.size, payload.length)
  assert.equal(fs.statSync(uploaded.json.path).mode & 0o777, 0o600)
  const uploadedDownload = await authedFetch(gateway, token, `/api/files/download?path=${encodeURIComponent(uploaded.json.path)}`)
  assert.equal(sha256(Buffer.from(await uploadedDownload.arrayBuffer())), expectedSha)

  // 冲突默认 409；overwrite=1 覆盖
  const conflict = await readJson(
    await fetch(`${gateway.url}${uploadPath}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'x-content-sha256': expectedSha },
      body: payload,
    }),
  )
  assert.equal(conflict.status, 409)
  const overwritten = await readJson(
    await fetch(`${gateway.url}${uploadPath}&overwrite=1`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'x-content-sha256': expectedSha },
      body: payload,
    }),
  )
  assert.equal(overwritten.status, 201)

  // sha256 不匹配 → 400 且不落盘
  const mismatchName = 'mismatch.txt'
  const mismatch = await readJson(
    await fetch(`${gateway.url}/api/files/upload?path=${encodeURIComponent(dataDir)}&name=${mismatchName}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'x-content-sha256': 'f'.repeat(64) },
      body: Buffer.from('some content'),
    }),
  )
  assert.equal(mismatch.status, 400)
  assert.equal(mismatch.json.error, 'sha256-mismatch')
  assert.equal(fs.existsSync(path.join(dataDir, mismatchName)), false)

  // 非法文件名 // 越界目录
  const badName = await readJson(
    await fetch(`${gateway.url}/api/files/upload?path=${encodeURIComponent(dataDir)}&name=${encodeURIComponent('../evil.txt')}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}` },
      body: Buffer.from('x'),
    }),
  )
  assert.equal(badName.status, 400)
  const badDir = await readJson(
    await fetch(`${gateway.url}/api/files/upload?path=${encodeURIComponent(outsideDir)}&name=${encodeURIComponent('evil.txt')}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}` },
      body: Buffer.from('x'),
    }),
  )
  assert.equal(badDir.status, 403)

  fs.rmSync(outsideDir, { recursive: true, force: true })
})
