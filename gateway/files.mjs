/**
 * gateway/files.mjs — 文件浏览 / 下载（Range）/ 上传 / 预览。
 *
 * 安全边界（SPEC v2 §5.1，docs/interfaces.md §2.5）：
 *  - 允许根：~ + config.roots + DSH 当前工作区（插件经 env 传入）；
 *  - 输入路径先做词汇层面的根校验，再用 realpath 校验真实目标（防符号链接穿越）；
 *  - 越界 403 forbidden-path；上传 2GiB 上限；预览 1MiB 上限。
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { silentLogger } from './log.mjs'

export const MAX_UPLOAD_BYTES = 2 * 1024 ** 3 // 2 GiB
export const MAX_PREVIEW_BYTES = 1024 * 1024 // 1 MiB
const CHUNK_LIMIT_CHECK = 1024 * 1024

const CONTENT_TYPES = {
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.js': 'text/plain; charset=utf-8',
  '.mjs': 'text/plain; charset=utf-8',
  '.cjs': 'text/plain; charset=utf-8',
  '.ts': 'text/plain; charset=utf-8',
  '.css': 'text/plain; charset=utf-8',
  '.html': 'text/plain; charset=utf-8',
  '.xml': 'text/plain; charset=utf-8',
  '.yml': 'text/plain; charset=utf-8',
  '.yaml': 'text/plain; charset=utf-8',
  '.csv': 'text/plain; charset=utf-8',
  '.log': 'text/plain; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.pdf': 'application/pdf',
  '.zip': 'application/zip',
  '.tar': 'application/x-tar',
  '.gz': 'application/gzip',
}

const TEXT_EXTENSIONS = new Set(['.txt', '.md', '.json', '.js', '.mjs', '.cjs', '.ts', '.css', '.html', '.xml', '.yml', '.yaml', '.csv', '.log', '.toml', '.ini', '.conf', '.sh', '.py', '.go', '.rs', '.java', '.kt', '.c', '.h', '.cpp'])
const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp'])
export const DEFAULT_DOWNLOAD_TYPES = new Set(['.pdf', '.zip', '.tar', '.gz', '.apk', '.tgz'])

export class FileError extends Error {
  constructor(status, code, message) {
    super(message || code)
    this.status = status
    this.code = code
  }
}

function isInside(child, root) {
  const rel = path.relative(root, child)
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel))
}

function isBadName(name) {
  return (
    !name ||
    name === '.' ||
    name === '..' ||
    name.length > 255 ||
    /[/\\\u0000]/.test(name) ||
    // 拒绝控制字符
    /[\u0001-\u001f\u007f]/.test(name)
  )
}

function contentDisposition(filename) {
  const fallback = filename.replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_')
  return `attachment; filename="${fallback}"; filename*=UTF-8''${encodeURIComponent(filename)}`
}

function sendJson(res, status, payload, headers = {}) {
  if (res.headersSent || res.writableEnded) return
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    ...headers,
  })
  res.end(body)
}

function parseRange(header, size) {
  if (!header) return null
  const match = /^bytes=(\d*)-(\d*)$/.exec(String(header).trim())
  if (!match) return { invalid: true }
  const [, rawStart, rawEnd] = match
  let start
  let end
  if (rawStart === '') {
    const suffix = Number(rawEnd)
    if (!Number.isFinite(suffix) || suffix <= 0) return { invalid: true }
    start = Math.max(0, size - suffix)
    end = size - 1
  } else {
    start = Number(rawStart)
    end = rawEnd === '' ? size - 1 : Number(rawEnd)
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || start > end || start >= size) return { invalid: true }
  end = Math.min(end, size - 1)
  return { start, end }
}

export class FileService {
  constructor({ roots = [], home = os.homedir(), logger = silentLogger, maxUploadBytes = MAX_UPLOAD_BYTES, maxPreviewBytes = MAX_PREVIEW_BYTES } = {}) {
    this.home = path.resolve(home)
    this.logger = logger
    this.maxUploadBytes = maxUploadBytes
    this.maxPreviewBytes = maxPreviewBytes
    const declared = roots.length ? roots : [this.home]
    const realRoots = []
    for (const raw of declared) {
      const resolved = path.resolve(raw)
      try {
        const real = fs.realpathSync(resolved)
        if (!realRoots.includes(real)) realRoots.push(real)
      } catch {
        this.logger.warn('file root unavailable', { root: resolved })
      }
    }
    // 家目录兜底：即使配置写错也允许访问家目录（接口契约要求 ~ 为根）。
    try {
      const realHome = fs.realpathSync(this.home)
      if (!realRoots.includes(realHome)) realRoots.unshift(realHome)
    } catch {
      /* ignore */
    }
    this.roots = realRoots
  }

  /** 允许根（返回给客户端）。 */
  rootPaths() {
    return [...this.roots]
  }

  _expand(input) {
    if (input === undefined || input === null || input === '') return this.home
    let text = String(input)
    if (text === '~') text = this.home
    else if (text.startsWith('~/')) text = path.join(this.home, text.slice(2))
    return path.isAbsolute(text) ? path.normalize(text) : path.resolve(this.home, text)
  }

  _assertInside(target) {
    const resolved = path.resolve(target)
    const lexicalOk = this.roots.some((root) => isInside(resolved, root))
    if (!lexicalOk) throw new FileError(403, 'forbidden-path', 'path outside allowed roots')
  }

  /** 解析一个必须存在的路径（realpath 校验防符号链接穿越）。 */
  resolveExisting(input, { kind = 'any' } = {}) {
    const expanded = this._expand(input)
    this._assertInside(expanded)
    let real
    try {
      real = fs.realpathSync(expanded)
    } catch (err) {
      if (err.code === 'ENOENT' || err.code === 'ENOTDIR') throw new FileError(404, 'not-found', 'path not found')
      throw new FileError(403, 'forbidden-path', 'path not accessible')
    }
    // realpath 后再次校验：`..` 与符号链接都不能跳出允许根。
    this._assertInside(real)
    let stat
    try {
      stat = fs.statSync(real)
    } catch {
      throw new FileError(403, 'forbidden-path', 'path not accessible')
    }
    if (kind === 'dir' && !stat.isDirectory()) throw new FileError(400, 'not-a-directory', 'not a directory')
    if (kind === 'file' && !stat.isFile()) throw new FileError(400, 'not-a-file', 'not a regular file')
    return { path: real, stat }
  }

  /** 上传目标解析：目录必须是允许根内的真实目录，文件名做净化。 */
  resolveUploadTarget(dirInput, name) {
    const filename = String(name ?? '')
    if (isBadName(filename)) throw new FileError(400, 'invalid-name', 'invalid file name')
    const dir = this.resolveExisting(dirInput, { kind: 'dir' })
    const target = path.join(dir.path, filename)
    this._assertInside(target)
    return { dir: dir.path, target, name: filename }
  }

  list(input) {
    const { path: real, stat } = this.resolveExisting(input, { kind: 'dir' })
    let dirents
    try {
      dirents = fs.readdirSync(real, { withFileTypes: true })
    } catch (err) {
      throw new FileError(err.code === 'EACCES' ? 403 : 500, 'list-failed', 'failed to list directory')
    }
    const entries = []
    for (const dirent of dirents) {
      const full = path.join(real, dirent.name)
      let type = dirent.isDirectory() ? 'dir' : 'file'
      let size = 0
      let mtimeMs = 0
      try {
        const st = fs.statSync(full)
        type = st.isDirectory() ? 'dir' : 'file'
        size = st.isFile() ? st.size : 0
        mtimeMs = st.mtimeMs
      } catch {
        type = 'file'
      }
      entries.push({ name: dirent.name, path: full, type, size, mtimeMs })
    }
    entries.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1))
    return { path: real, roots: this.rootPaths(), entries }
  }

  /** GET/HEAD /api/files/download?path= （支持 Range）。 */
  handleDownload(req, res, url) {
    let resolved
    try {
      resolved = this.resolveExisting(url.searchParams.get('path'), { kind: 'file' })
    } catch (err) {
      return sendError(res, err)
    }
    const { path: file, stat } = resolved
    const size = stat.size
    const filename = path.basename(file)
    const type = CONTENT_TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream'
    const range = parseRange(req.headers.range, size)
    if (range?.invalid) {
      return sendJson(res, 416, { error: 'range-not-satisfiable' }, { 'content-range': `bytes */${size}` })
    }
    const baseHeaders = {
      'content-type': type,
      'accept-ranges': 'bytes',
      'content-disposition': contentDisposition(filename),
      'last-modified': new Date(stat.mtimeMs).toUTCString(),
    }
    if (req.method === 'HEAD') {
      res.writeHead(200, { ...baseHeaders, 'content-length': size })
      return res.end()
    }
    const start = range ? range.start : 0
    const end = range ? range.end : size - 1
    const length = size === 0 ? 0 : end - start + 1
    res.writeHead(range ? 206 : 200, {
      ...baseHeaders,
      'content-length': length,
      ...(range ? { 'content-range': `bytes ${start}-${end}/${size}` } : {}),
    })
    if (size === 0) return res.end()
    const stream = fs.createReadStream(file, { start, end })
    stream.on('error', () => {
      try {
        res.destroy()
      } catch {
        /* ignore */
      }
    })
    stream.pipe(res)
    return undefined
  }

  async handleUpload(req, res, url) {
    let target
    try {
      target = this.resolveUploadTarget(url.searchParams.get('path') ?? this.home, url.searchParams.get('name'))
    } catch (err) {
      return sendError(res, err)
    }
    const overwrite = url.searchParams.get('overwrite') === '1' || url.searchParams.get('overwrite') === 'true'
    const declaredLength = Number(req.headers['content-length'] || NaN)
    if (Number.isFinite(declaredLength) && declaredLength > this.maxUploadBytes) {
      req.resume()
      return sendJson(res, 413, { error: 'body-too-large' })
    }
    if (!overwrite && fs.existsSync(target.target)) {
      req.resume()
      return sendJson(res, 409, { error: 'conflict' })
    }
    const expectedSha = String(req.headers['x-content-sha256'] || '').trim().toLowerCase()
    if (expectedSha && !/^[a-f0-9]{64}$/.test(expectedSha)) {
      req.resume()
      return sendJson(res, 400, { error: 'invalid-sha256' })
    }
    const tmp = path.join(target.dir, `.upload-${process.pid}-${crypto.randomBytes(6).toString('hex')}.tmp`)
    const hasher = crypto.createHash('sha256')
    let written = 0
    let tooLarge = false
    const meter = new Transform({
      transform(chunk, _enc, callback) {
        written += chunk.length
        if (written > this.maxUploadBytes) {
          tooLarge = true
          callback(new FileError(413, 'body-too-large', 'upload too large'))
          return
        }
        hasher.update(chunk)
        callback(null, chunk)
      },
    })
    // 绑定 this 供 Transform 内部使用
    meter.maxUploadBytes = this.maxUploadBytes
    try {
      await pipeline(req, meter, fs.createWriteStream(tmp, { mode: 0o600, flags: 'wx' }))
    } catch (err) {
      try {
        fs.unlinkSync(tmp)
      } catch {
        /* ignore */
      }
      if (tooLarge || err instanceof FileError || err?.message === 'upload too large') {
        return sendJson(res, 413, { error: 'body-too-large' })
      }
      return sendJson(res, err?.code === 'ABORT_ERR' ? 400 : 500, { error: err?.code === 'ABORT_ERR' ? 'upload-aborted' : 'upload-failed' })
    }
    const sha256 = hasher.digest('hex')
    if (expectedSha && expectedSha !== sha256) {
      try {
        fs.unlinkSync(tmp)
      } catch {
        /* ignore */
      }
      return sendJson(res, 400, { error: 'sha256-mismatch', sha256 })
    }
    try {
      fs.renameSync(tmp, target.target)
      fs.chmodSync(target.target, 0o600)
    } catch {
      try {
        fs.unlinkSync(tmp)
      } catch {
        /* ignore */
      }
      return sendJson(res, 500, { error: 'upload-failed' })
    }
    return sendJson(res, 201, { ok: true, name: target.name, path: target.target, size: written, sha256 })
  }

  handlePreview(req, res, url) {
    let resolved
    try {
      resolved = this.resolveExisting(url.searchParams.get('path'), { kind: 'file' })
    } catch (err) {
      return sendError(res, err)
    }
    const { path: file, stat } = resolved
    if (stat.size > this.maxPreviewBytes) return sendJson(res, 415, { error: 'preview-too-large' })
    const ext = path.extname(file).toLowerCase()
    let type = CONTENT_TYPES[ext]
    let allowed = IMAGE_EXTENSIONS.has(ext) || TEXT_EXTENSIONS.has(ext)
    if (!type) {
      // 未知扩展名：探测是否为文本（无 NUL 字节），只按 text/plain 返回。
      let probe = Buffer.alloc(0)
      try {
        const fd = fs.openSync(file, 'r')
        probe = Buffer.alloc(Math.min(4096, stat.size))
        fs.readSync(fd, probe, 0, probe.length, 0)
        fs.closeSync(fd)
      } catch {
        /* ignore */
      }
      if (!probe.includes(0)) {
        type = 'text/plain; charset=utf-8'
        allowed = true
      }
    }
    if (!allowed || !type) return sendJson(res, 415, { error: 'preview-unsupported' })
    res.writeHead(200, {
      'content-type': type,
      'content-length': stat.size,
      'content-disposition': `inline; filename*=UTF-8''${encodeURIComponent(path.basename(file))}`,
    })
    if (req.method === 'HEAD') return res.end()
    const stream = fs.createReadStream(file)
    stream.on('error', () => res.destroy())
    stream.pipe(res)
    return undefined
  }
}

export function sendError(res, err) {
  if (err instanceof FileError) return sendJson(res, err.status, { error: err.code })
  return sendJson(res, 500, { error: 'internal-error' })
}

export function createFileService(options) {
  return new FileService(options)
}
