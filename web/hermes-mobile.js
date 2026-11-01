/* Portrait layout for Hermes on a phone.
 *
 * The desktop renderer docks its session sidebar through a split-pane layout
 * tree whose minimum pane widths are desktop-sized, so at phone widths the
 * sessions pane is dropped entirely and there is no way to reach it. This layer
 * adds a native-feeling drawer that talks to the same gateway REST API, plus a
 * few desktop-only chrome elements hidden where they would only cost space. */
(function () {
  'use strict'

  var MOBILE_MAX = 760
  var drawerEl = null
  var backdropEl = null
  var listEl = null
  var searchEl = null
  var menuBtn = null
  var sessions = []
  var loading = false

  function api(path) {
    if (window.__hermesAndroid && window.__hermesAndroid.api) {
      return window.__hermesAndroid.api({ path: path })
    }
    if (window.hermesDesktop && window.hermesDesktop.api) {
      return window.hermesDesktop.api({ path: path })
    }
    return Promise.reject(new Error('no bridge'))
  }

  function timeAgo(seconds) {
    if (!seconds) return ''
    var diff = Date.now() / 1000 - seconds
    if (diff < 60) return '刚刚'
    if (diff < 3600) return Math.floor(diff / 60) + ' 分钟'
    if (diff < 86400) return Math.floor(diff / 3600) + ' 小时'
    if (diff < 86400 * 7) return Math.floor(diff / 86400) + ' 天'
    var d = new Date(seconds * 1000)
    return (d.getMonth() + 1) + '/' + d.getDate()
  }

  function currentSessionId() {
    var hash = location.hash.replace(/^#\/?/, '')
    return hash.split(/[/?]/)[0] || ''
  }

  function el(tag, cls, text) {
    var node = document.createElement(tag)
    if (cls) node.className = cls
    if (text != null) node.textContent = text
    return node
  }

  function render() {
    if (!listEl) return
    var query = (searchEl && searchEl.value || '').trim().toLowerCase()
    var active = currentSessionId()
    var rows = sessions.filter(function (s) {
      if (!query) return true
      return String(s.title || '').toLowerCase().indexOf(query) !== -1
    })

    listEl.innerHTML = ''
    if (!rows.length) {
      var empty = el('div', 'hm-empty', loading ? '加载中…' : (query ? '没有匹配的会话' : '还没有会话'))
      listEl.appendChild(empty)
      return
    }

    rows.forEach(function (s) {
      var row = el('button', 'hm-row' + (s.id === active ? ' hm-active' : ''))
      row.type = 'button'
      var title = el('div', 'hm-title', s.title || '未命名会话')
      var meta = el('div', 'hm-meta',
        timeAgo(s.last_activity_at || s.started_at) +
        (s.message_count ? ' · ' + s.message_count + ' 条' : '') +
        (s.source && s.source !== 'desktop' ? ' · ' + s.source : ''))
      row.appendChild(title)
      row.appendChild(meta)
      row.addEventListener('click', function () {
        location.hash = '#/' + s.id
        close()
      })
      listEl.appendChild(row)
    })
  }

  function load() {
    loading = true
    render()
    api('/api/sessions?limit=60').then(function (data) {
      var rows = (data && data.sessions) || []
      sessions = rows.slice().sort(function (a, b) {
        return (b.last_activity_at || b.started_at || 0) - (a.last_activity_at || a.started_at || 0)
      })
    }).catch(function () {
      sessions = []
    }).then(function () {
      loading = false
      render()
    })
  }

  // The renderer's theme vars are translucent in places, so sample the app's own
  // page background and paint the drawer with it — otherwise the page underneath
  // bleeds through the header.
  function applyOpaqueBackground() {
    var bg = ''
    try {
      bg = getComputedStyle(document.body).backgroundColor || ''
    } catch (e) { /* ignore */ }
    if (!bg || bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent') bg = '#ffffff'
    if (drawerEl) drawerEl.style.backgroundColor = bg
    if (menuBtn) menuBtn.style.backgroundColor = bg
  }

  function open() {
    if (!drawerEl) return
    applyOpaqueBackground()
    if (menuBtn) menuBtn.style.visibility = 'hidden'
    drawerEl.classList.add('hm-open')
    backdropEl.classList.add('hm-open')
    document.documentElement.classList.add('hm-locked')
    load()
  }

  function close() {
    if (!drawerEl) return
    if (menuBtn) menuBtn.style.visibility = ''
    drawerEl.classList.remove('hm-open')
    backdropEl.classList.remove('hm-open')
    document.documentElement.classList.remove('hm-locked')
  }

  function build() {
    menuBtn = el('button', 'hm-menu')
    menuBtn.type = 'button'
    menuBtn.setAttribute('aria-label', '会话')
    menuBtn.innerHTML = '<span></span><span></span><span></span>'
    menuBtn.addEventListener('click', open)

    backdropEl = el('div', 'hm-backdrop')
    backdropEl.addEventListener('click', close)

    drawerEl = el('aside', 'hm-drawer')

    var header = el('div', 'hm-head')
    header.appendChild(el('div', 'hm-heading', '会话'))
    var newBtn = el('button', 'hm-new', '＋ 新会话')
    newBtn.type = 'button'
    newBtn.addEventListener('click', function () {
      location.hash = '#/'
      close()
    })
    header.appendChild(newBtn)

    searchEl = document.createElement('input')
    searchEl.className = 'hm-search'
    searchEl.type = 'search'
    searchEl.placeholder = '搜索会话'
    searchEl.addEventListener('input', render)

    listEl = el('div', 'hm-list')

    drawerEl.appendChild(header)
    drawerEl.appendChild(searchEl)
    drawerEl.appendChild(listEl)

    document.body.appendChild(menuBtn)
    document.body.appendChild(backdropEl)
    document.body.appendChild(drawerEl)
  }

  function applyViewport() {
    var mobile = window.innerWidth <= MOBILE_MAX
    document.documentElement.classList.toggle('hm-mobile', mobile)
    if (!mobile) close()
    if (mobile && !drawerEl) build()
    if (mobile) { hideDesktopChrome(); applyOpaqueBackground() }
  }

  // The chrome labels are localized, so hide by label content rather than by
  // the English aria-labels the desktop build ships.
  var HIDE_LABELS = [
    /侧边栏|sidebar/i,               // docked sidebar toggle (unusable at this width)
    /布局编辑器|layout editor/i,
    /HUD/i,
    /交换侧边栏|swap sidebar/i,
    /右侧栏|right sidebar/i,
    /^(最小化|Minimize window)/i,
    /^(最大化|Maximize window)/i,
    /^(关闭窗口|Close window)/i
  ]

  function hideDesktopChrome() {
    var buttons = document.querySelectorAll('button[aria-label],[role="button"][aria-label]')
    for (var i = 0; i < buttons.length; i++) {
      var label = buttons[i].getAttribute('aria-label') || ''
      for (var j = 0; j < HIDE_LABELS.length; j++) {
        if (HIDE_LABELS[j].test(label)) {
          buttons[i].style.display = 'none'
          break
        }
      }
    }
  }

  var chromeTimer = null
  function scheduleHideChrome() {
    if (chromeTimer) return
    chromeTimer = setTimeout(function () {
      chromeTimer = null
      if (document.documentElement.classList.contains('hm-mobile')) hideDesktopChrome()
    }, 300)
  }

  function init() {
    applyViewport()
    window.addEventListener('resize', applyViewport)
    window.addEventListener('orientationchange', function () {
      setTimeout(applyViewport, 150)
    })
    window.addEventListener('hashchange', function () {
      if (drawerEl && drawerEl.classList.contains('hm-open')) render()
      scheduleHideChrome()
    })
    // The renderer mounts after us and re-renders on navigation; keep the
    // desktop-only chrome hidden as it comes back.
    new MutationObserver(scheduleHideChrome).observe(document.body, { childList: true, subtree: true })
    scheduleHideChrome()
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init)
  } else {
    init()
  }
})()
