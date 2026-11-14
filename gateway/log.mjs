/**
 * gateway/log.mjs — 日志脱敏。
 *
 * SPEC v2 §5.1：token / 口令 / tunnel token / DSH 上游 Cookie 一律不落日志；
 * 错误只记录类型与状态码。这里做“最后一道”文本过滤，调用方仍不应主动传入敏感值。
 */

const COMMON_PATTERNS = [
  /\bBearer\s+[A-Za-z0-9._~+/=-]{8,}/gi,
  /\b(?:cookie|set-cookie)\s*[:=]\s*[^\s"'\\]+/gi,
  /--token[= ]+[^\s"']+/gi,
  /\b(?:password|passwd|secret|token|ticket)[=:]\s*["']?[^\s"'&,}]+/gi,
  /([?&](?:token|ticket|password)=)[^&\s]+/gi,
]

/** 文本脱敏：先替换显式 secrets，再套用通用模式。 */
export function redactText(input, secrets = []) {
  let text = String(input ?? '')
  for (const secret of secrets) {
    if (typeof secret === 'string' && secret.length >= 4) text = text.split(secret).join('***')
  }
  for (const pattern of COMMON_PATTERNS) {
    text = text.replace(pattern, (match) => {
      const sep = match.includes('=') ? '=' : match.includes(':') ? ':' : ' '
      const head = match.split(sep)[0]
      return `${head}${sep}***`
    })
  }
  return text
}

export function createLogger({ stream = process.stderr, level = 'info', secrets = [], now = () => new Date() } = {}) {
  const order = { debug: 10, info: 20, warn: 30, error: 40 }
  const threshold = order[level] ?? order.info
  const write = (lvl, message, detail) => {
    if ((order[lvl] ?? 20) < threshold) return
    const time = now().toISOString()
    let line = `[dsh-mobile] ${time} ${lvl.toUpperCase()} ${redactText(message, secrets)}`
    if (detail !== undefined) {
      let text
      try {
        text = typeof detail === 'string' ? detail : JSON.stringify(detail)
      } catch {
        text = String(detail)
      }
      line += ` ${redactText(text, secrets)}`
    }
    try {
      stream.write(`${line}\n`)
    } catch {
      /* 日志写失败不影响主流程 */
    }
  }
  return {
    debug: (message, detail) => write('debug', message, detail),
    info: (message, detail) => write('info', message, detail),
    warn: (message, detail) => write('warn', message, detail),
    error: (message, detail) => write('error', message, detail),
    child(prefix) {
      return {
        debug: (m, d) => write('debug', `${prefix} ${m}`, d),
        info: (m, d) => write('info', `${prefix} ${m}`, d),
        warn: (m, d) => write('warn', `${prefix} ${m}`, d),
        error: (m, d) => write('error', `${prefix} ${m}`, d),
      }
    },
  }
}

export const silentLogger = {
  debug() {},
  info() {},
  warn() {},
  error() {},
  child() {
    return silentLogger
  },
}
