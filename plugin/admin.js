/**
 * plugin/admin.js — 主机面板逻辑（无外部依赖）。
 *
 * 只读 /mobile/api/status；操作为刷新上游 Cookie / 重启网关。
 * 面板接口由 DSH 同源会话鉴权（插件 index.mjs 里 requestRejection），本文件不接触任何凭据。
 */
;(function () {
  'use strict'

  var els = {
    banner: document.getElementById('banner'),
    gwRunning: document.getElementById('gw-running'),
    gwListen: document.getElementById('gw-listen'),
    gwUpstream: document.getElementById('gw-upstream'),
    gwVersion: document.getElementById('gw-version'),
    gwRestarts: document.getElementById('gw-restarts'),
    lanRows: document.getElementById('lan-rows'),
    initialPassword: document.getElementById('initial-password'),
    copyPassword: document.getElementById('copy-password'),
    passwordNote: document.getElementById('password-note'),
    remoteTarget: document.getElementById('remote-target'),
    remoteNote: document.getElementById('remote-note'),
    logs: document.getElementById('logs'),
    toast: document.getElementById('toast'),
    btnRefreshCookie: document.getElementById('btn-refresh-cookie'),
    btnRestart: document.getElementById('btn-restart'),
    btnRefresh: document.getElementById('btn-refresh'),
  }

  var state = {
    data: null,
    timer: null,
    lastPassword: null,
  }
  var toastTimer = null

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]
    })
  }

  function showToast(text) {
    els.toast.textContent = text
    els.toast.classList.add('show')
    if (toastTimer) clearTimeout(toastTimer)
    toastTimer = setTimeout(function () {
      els.toast.classList.remove('show')
    }, 1800)
  }

  function showBanner(text) {
    if (!text) {
      els.banner.classList.remove('show')
      els.banner.textContent = ''
      return
    }
    els.banner.textContent = text
    els.banner.classList.add('show')
  }

  async function copyText(text, label) {
    if (!text) {
      showToast('没有可复制的内容')
      return
    }
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text)
      } else {
        var input = document.createElement('textarea')
        input.value = text
        input.style.position = 'fixed'
        input.style.opacity = '0'
        document.body.appendChild(input)
        input.select()
        document.execCommand('copy')
        document.body.removeChild(input)
      }
      showToast(label || '已复制')
    } catch (err) {
      showToast('复制失败，请手动选择')
    }
  }

  function render(data) {
    state.data = data
    showBanner('')
    var gw = data.gateway || {}
    var health = gw.health || null
    var upstreamOk = Boolean(health && health.upstream && health.upstream.ok)
    var running = Boolean(gw.running)
    els.gwRunning.innerHTML = '<span class="dot ' + (running ? 'ok' : 'bad') + '"></span>' + (running ? '运行中' : '未运行')
    els.gwListen.textContent = (gw.host || '0.0.0.0') + ':' + (gw.port || 8790)
    els.gwUpstream.innerHTML =
      '<span class="dot ' + (upstreamOk ? 'ok' : '') + '"></span>' + (upstreamOk ? '已连接' : '未知/不可达')
    els.gwVersion.textContent = 'gateway ' + ((data.plugin && data.plugin.version) || '—') + ' · plugin ' + ((data.plugin && data.plugin.version) || '—')
    els.gwRestarts.textContent = String(gw.restarts != null ? gw.restarts : 0) + (gw.lastError ? '（最近：' + esc(gw.lastError) + '）' : '')

    var lan = Array.isArray(data.lan) ? data.lan : []
    if (!lan.length) {
      els.lanRows.innerHTML = '<div class="row"><span class="k">地址</span><span class="v muted">暂无可用地址</span></div>'
    } else {
      els.lanRows.innerHTML = lan
        .map(function (item, index) {
          return (
            '<div class="row"><span class="k">' +
            (index === 0 ? '局域网' : item.iface || '地址') +
            '</span><span class="v mono">' +
            esc(item.url) +
            '</span><button class="copy" type="button" data-copy="' +
            esc(item.url) +
            '">复制</button></div>'
          )
        })
        .join('')
    }

    var password = data.initialPassword || null
    state.lastPassword = password
    if (password) {
      els.initialPassword.textContent = password
      els.copyPassword.disabled = false
      els.passwordNote.textContent = '首次登录后请尽快修改口令；修改后本处不再显示。'
    } else {
      els.initialPassword.textContent = '已修改（不再显示）'
      els.copyPassword.disabled = true
      els.passwordNote.textContent = data.config && data.config.mustChangePassword === false ? '管理口令已修改。' : '未找到 initial-password.txt（可能已修改或已删除）。'
    }

    var remote = data.remoteAccess || {}
    els.remoteTarget.textContent = 'http://127.0.0.1:' + (remote.gatewayPort || gw.port || 8790)
    els.remoteNote.textContent = remote.note || '在 Cloudflare 控制台把域名转发到本机网关端口即可。'

    var logs = Array.isArray(data.logs) ? data.logs : []
    els.logs.textContent = logs.length ? logs.join('\n') : '暂无日志'
    els.logs.scrollTop = els.logs.scrollHeight

    if (data.upstream && data.upstream.cookiePresent === false) {
      showBanner('尚未拿到上游 DSH Cookie：请确认 DSH 正在运行，并点击「刷新上游 Cookie」。')
    } else if (gw.lastError) {
      showBanner('网关最近一次异常：' + gw.lastError)
    }
  }

  async function load() {
    try {
      var response = await fetch('/mobile/api/status', { credentials: 'same-origin', cache: 'no-store' })
      if (!response.ok) {
        if (response.status === 401 || response.status === 403) {
          showBanner('面板会话未通过 DSH 鉴权，请从 DSH 页面内打开本面板。')
          return
        }
        throw new Error('HTTP ' + response.status)
      }
      render(await response.json())
    } catch (err) {
      showBanner('无法读取网关状态：' + (err && err.message ? err.message : '网络错误'))
    }
  }

  async function post(path, label) {
    try {
      var response = await fetch(path, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'content-type': 'application/json' },
        body: '{}',
      })
      var payload = await response.json().catch(function () {
        return null
      })
      if (!response.ok || (payload && payload.ok === false)) {
        showToast(label + '失败')
      } else {
        showToast(label + '成功')
      }
    } catch (err) {
      showToast(label + '失败')
    }
    load()
  }

  els.lanRows.addEventListener('click', function (event) {
    var target = event.target
    if (!target || target.tagName !== 'BUTTON') return
    var text = target.getAttribute('data-copy')
    if (text) copyText(text, '地址已复制')
  })

  els.copyPassword.addEventListener('click', function () {
    copyText(state.lastPassword, '口令已复制')
  })

  els.btnRefreshCookie.addEventListener('click', function () {
    post('/mobile/api/refresh-cookie', '刷新 Cookie')
  })

  els.btnRestart.addEventListener('click', function () {
    if (window.confirm('重启网关会短暂断开已连接的 App，继续？')) post('/mobile/api/restart-gateway', '重启网关')
  })

  els.btnRefresh.addEventListener('click', function () {
    load().then(function () {
      showToast('已刷新')
    })
  })

  load()
  state.timer = setInterval(function () {
    if (document.visibilityState === 'visible') load()
  }, 3000)
})()
