/**
 * plugin/client.js — 浏览器端半体：DSH 侧边栏入口 + 浮层主机面板（无构建步骤，产物入库）。
 *
 * 按 docs/recon/dsh-plugin-api.md §3：
 *  - `window.__ModuleLoader__.load({ id, factory })`，id 必须等于包名；
 *  - factory 通过 `require()` 拿 react / dsh-client-store（这里只用 react）；
 *  - 注册 `sidebar.footer.action`（侧边栏入口）与 `shell.overlay`（面板浮层）。
 */
window.__ModuleLoader__.load({
  id: 'dsh-mobile-plugin',
  factory: (require) => {
    const React = require('react')
    const module = { exports: {} }
    const exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    // 模块级 UI 状态：入口与浮层是两个独立槽位注册，需要共享 open 状态。
    const uiState = {
      open: false,
      listeners: new Set(),
      setOpen(next) {
        if (uiState.open === next) return
        uiState.open = next
        for (const listener of [...uiState.listeners]) listener()
      },
    }
    function subscribe(listener) {
      uiState.listeners.add(listener)
      return () => uiState.listeners.delete(listener)
    }
    function getSnapshot() {
      return uiState.open
    }
    function useOpen() {
      if (typeof React.useSyncExternalStore === 'function') {
        return React.useSyncExternalStore(subscribe, getSnapshot)
      }
      // 极老 React 的兜底：不订阅，只读一次。
      return uiState.open
    }

    const BLUE = '#4d6bfe'
    const TEXT = '#1a1a1a'
    const MUTED = '#8b8b8b'
    const LINE = '#ececec'
    const SURFACE = '#f5f6f7'

    function SidebarEntry() {
      const open = useOpen()
      return React.createElement(
        'button',
        {
          type: 'button',
          title: 'DSH Mobile',
          'aria-label': 'DSH Mobile',
          'aria-expanded': open,
          onClick: () => uiState.setOpen(!open),
          style: {
            display: 'flex',
            alignItems: 'center',
            gap: 7,
            width: '100%',
            padding: '6px 10px',
            border: 'none',
            borderRadius: 8,
            background: open ? '#eef1ff' : 'transparent',
            color: open ? BLUE : 'inherit',
            cursor: 'pointer',
            font: '500 13px/1.3 system-ui, -apple-system, "Segoe UI", sans-serif',
            textAlign: 'left',
          },
        },
        React.createElement(
          'span',
          {
            'aria-hidden': 'true',
            style: {
              width: 16,
              height: 16,
              borderRadius: 5,
              background: open ? BLUE : SURFACE,
              border: `1px solid ${open ? BLUE : LINE}`,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxSizing: 'border-box',
            },
          },
          React.createElement('span', {
            style: { width: 6, height: 6, borderRadius: 2, background: open ? '#ffffff' : BLUE, display: 'block' },
          }),
        ),
        React.createElement('span', { style: { flex: 1 } }, 'DSH Mobile'),
      )
    }

    function Drawer() {
      const open = useOpen()
      if (!open) return null
      return React.createElement(
        'div',
        {
          style: {
            position: 'fixed',
            top: 12,
            right: 12,
            bottom: 12,
            zIndex: 2147483000,
            width: 'min(560px, calc(100vw - 24px))',
            pointerEvents: 'none',
          },
        },
        React.createElement(
          'div',
          {
            role: 'dialog',
            'aria-label': 'DSH Mobile 主机面板',
            style: {
              pointerEvents: 'auto',
              height: '100%',
              display: 'flex',
              flexDirection: 'column',
              background: '#ffffff',
              border: `1px solid ${LINE}`,
              borderRadius: 16,
              overflow: 'hidden',
            },
          },
          React.createElement(
            'div',
            {
              style: {
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '0 16px',
                height: 44,
                borderBottom: `1px solid ${LINE}`,
                flex: '0 0 auto',
              },
            },
            React.createElement('span', { style: { font: '600 15px/1.3 system-ui, sans-serif', color: TEXT } }, 'DSH Mobile'),
            React.createElement(
              'button',
              {
                type: 'button',
                'aria-label': '关闭',
                onClick: () => uiState.setOpen(false),
                style: {
                  border: 'none',
                  background: 'transparent',
                  color: MUTED,
                  cursor: 'pointer',
                  font: '400 20px/1 system-ui, sans-serif',
                  padding: '4px 6px',
                  borderRadius: 8,
                },
              },
              '×',
            ),
          ),
          React.createElement('iframe', {
            src: '/mobile',
            title: 'DSH Mobile 主机面板',
            style: { flex: '1 1 auto', width: '100%', border: 'none', background: '#ffffff' },
          }),
        ),
      )
    }

    const inject = ['slots']

    function apply(ctx) {
      ctx.effect(
        () =>
          ctx.slots.inject('sidebar.footer.action', () =>
            ctx.slots.register({ name: 'sidebar.footer.action', id: 'dsh-mobile', order: 100 }, SidebarEntry),
          ),
        'dsh-mobile: sidebar entry',
      )
      ctx.effect(
        () =>
          ctx.slots.inject('shell.overlay', () =>
            ctx.slots.register({ name: 'shell.overlay', id: 'dsh-mobile-panel', order: 100 }, Drawer),
          ),
        'dsh-mobile: panel overlay',
      )
    }

    exports.apply = apply
    exports.inject = inject
    return module.exports
  },
})
