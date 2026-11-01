/* Hermes Android bridge — implements the `window.hermesDesktop` surface that the
 * Hermes desktop renderer expects, on top of plain fetch()/WebSocket() against a
 * remote Hermes gateway. Injected before the renderer bundle in index.html.
 *
 * Design: the renderer touches ~130 bridge members. The ones that carry the
 * product (REST + the gateway connection) are real; everything the desktop shell
 * does locally (updaters, window chrome, pets, HUD, OS dialogs) degrades to inert
 * no-ops so the UI keeps working instead of crashing on a missing shell service.
 */
(function () {
  'use strict'

  var STORE_KEY = 'hermes.android.config'
  var DEFAULT_CONFIG = { baseUrl: 'http://127.0.0.1:9120', token: 'devtoken123' }

  function noop() { return undefined }
  function unsubscribe() { return noop }

  // A callable object whose every property is another callable object, and whose
  // calls resolve to undefined (so `await` works). Property names shaped like an
  // event subscription (`onFoo`) hand back a no-op unsubscribe function, and the
  // string members a value-shaped consumer might touch (`trim`, `toLowerCase`, …)
  // behave like an empty string instead of throwing.
  var STRING_METHODS = {
    trim: 1, toLowerCase: 1, toUpperCase: 1, slice: 1, substring: 1, replace: 1,
    includes: 1, startsWith: 1, endsWith: 1, split: 1, charAt: 1, indexOf: 1,
    padStart: 1, padEnd: 1, toString: 1, valueOf: 1, concat: 1, normalize: 1
  }

  function inert() {
    var target = function () { return undefined }
    return new Proxy(target, {
      get: function (t, key) {
        if (key === 'then' || key === 'toJSON') return undefined
        if (typeof key === 'symbol') {
          if (key === Symbol.toPrimitive) return function () { return '' }
          return undefined
        }
        if (key === 'length') return 0
        if (STRING_METHODS[key] === 1) return function () { return '' }
        if (/^on[A-Z]/.test(key)) return unsubscribe
        return inert()
      },
      // Unknown bridge calls resolve to a promise so call sites that chain
      // `.then(...)` on the result keep working.
      apply: function () { return Promise.resolve(undefined) }
    })
  }

  function readConfig() {
    if (window.__HERMES_ANDROID_CONFIG__ && window.__HERMES_ANDROID_CONFIG__.baseUrl) {
      return window.__HERMES_ANDROID_CONFIG__
    }
    try {
      var raw = localStorage.getItem(STORE_KEY)
      if (raw) {
        var parsed = JSON.parse(raw)
        if (parsed && parsed.baseUrl) return parsed
      }
    } catch (e) { /* ignore */ }
    return DEFAULT_CONFIG
  }

  var config = readConfig()
  var baseUrl = String(config.baseUrl || '').replace(/\/+$/, '')
  var token = String(config.token || '')
  var wsUrl = config.wsUrl || baseUrl.replace(/^http/i, 'ws') + '/api/ws?token=' + encodeURIComponent(token)

  // ---- REST -----------------------------------------------------------------
  function authHeaders() {
    return { 'X-Hermes-Session-Token': token, Authorization: 'Bearer ' + token }
  }

  async function api(request) {
    var path = request && request.path ? String(request.path) : '/api/status'
    var url = /^https?:/i.test(path) ? path : baseUrl + (path.charAt(0) === '/' ? path : '/' + path)
    var headers = Object.assign({ Accept: 'application/json' }, authHeaders())
    var init = { method: (request && request.method) || 'GET', headers: headers, mode: 'cors', credentials: 'omit' }

    if (request && request.upload) {
      var form = new FormData()
      var blob = new Blob([request.upload.bytes], { type: request.upload.contentType || 'application/octet-stream' })
      form.append('file', blob, request.upload.filename || 'upload.bin')
      init.method = 'POST'
      init.body = form
    } else if (request && request.body !== undefined) {
      headers['Content-Type'] = 'application/json'
      init.body = JSON.stringify(request.body)
      if (!request.method) init.method = 'POST'
    }

    var controller = typeof AbortController !== 'undefined' ? new AbortController() : null
    var timeoutMs = (request && request.timeoutMs) || 60000
    var timer = controller ? setTimeout(function () { controller.abort() }, timeoutMs) : null
    if (controller) init.signal = controller.signal

    var response
    try {
      response = await fetch(url, init)
    } finally {
      if (timer) clearTimeout(timer)
    }

    if (!response.ok) {
      var detail = ''
      try { detail = await response.text() } catch (e) { /* ignore */ }
      var err = new Error('Hermes API ' + response.status + ' ' + (path || url) + (detail ? ': ' + detail.slice(0, 300) : ''))
      err.status = response.status
      throw err
    }

    if (response.status === 204) return undefined
    var contentType = response.headers.get('content-type') || ''
    if (contentType.indexOf('application/json') !== -1) return response.json()
    var text = await response.text()
    try { return JSON.parse(text) } catch (e) { return text }
  }

  // ---- connection -----------------------------------------------------------
  function connectionDescriptor(profile) {
    var name = typeof profile === 'string' && profile.trim() ? profile.trim() : 'default'
    return {
      baseUrl: baseUrl,
      token: token,
      wsUrl: wsUrl,
      authMode: 'token',
      mode: 'remote',
      remoteHost: baseUrl,
      remoteKind: 'url',
      isFullscreen: false,
      isMaximized: false,
      nativeOverlayWidth: 0,
      logs: [],
      profile: name
    }
  }

  // ---- file picking ---------------------------------------------------------
  // The renderer's attach flow is built around host paths: selectPaths() returns
  // absolute paths, then readFileDataUrl()/readFileDataUrlForAttach() read the
  // bytes and file.attach ships them to the gateway as a data URL. Android has
  // no host paths, so picked Files are registered under a synthetic path that
  // round-trips through those same calls.
  var pickedFiles = new Map()
  var pickSeq = 0

  function registerFile(file) {
    if (!file) return ''
    var id = 'android-file://pick/' + (++pickSeq) + '/' + (file.name || 'file')
    pickedFiles.set(id, file)
    return id
  }

  function pickViaInput(directories) {
    return new Promise(function (resolve) {
      var input = document.createElement('input')
      input.type = 'file'
      input.multiple = !directories
      if (directories) input.setAttribute('webkitdirectory', '')
      input.style.position = 'fixed'
      input.style.left = '-9999px'
      var settled = false
      function finish(paths) {
        if (settled) return
        settled = true
        try { input.remove() } catch (e) { /* ignore */ }
        resolve(paths)
      }
      input.addEventListener('change', function () {
        var files = Array.prototype.slice.call(input.files || [])
        finish(files.map(registerFile))
      })
      // Chrome/WebView >= 113 fires `cancel` when the chooser is dismissed.
      // Older shells simply never settle, which the caller tolerates; a focus
      // heuristic would race a real selection instead.
      input.addEventListener('cancel', function () { finish([]) })
      document.body.appendChild(input)
      input.click()
    })
  }

  function readPickedFile(path) {
    var file = pickedFiles.get(String(path))
    if (!file) return Promise.reject(new Error('file not available: ' + path))
    return new Promise(function (resolve, reject) {
      var reader = new FileReader()
      reader.onload = function () { resolve(String(reader.result || '')) }
      reader.onerror = function () { reject(reader.error || new Error('read failed')) }
      reader.readAsDataURL(file)
    })
  }

  var real = {
    api: api,
    getConnection: async function (profile) { return connectionDescriptor(profile) },
    getConnectionFor: async function (payload) { return connectionDescriptor(payload && payload.profile) },
    getGatewayWsUrl: async function () { return { ok: true, wsUrl: wsUrl } },
    getGatewayWsUrlFor: async function () { return { ok: true, wsUrl: wsUrl } },
    revalidateConnection: async function () { return { ok: true, rebuilt: false } },
    getBootProgress: async function () { return { phase: 'ready', retryable: false, progress: 1 } },
    getEmbedHostOrigin: async function () { return baseUrl },
    getAgentRoster: async function () { return { agents: [] } },
    getProfileRoutes: async function () { return [] },
    setActiveConnectionRoute: async function () { return { ok: true } },
    applyConnectionConfig: async function () { return { ok: true } },
    getConnectionConfig: async function () { return { mode: 'remote', remoteUrl: baseUrl } },
    touchBackend: async function () { return { ok: true } },
    getPlatform: function () { return 'android' },
    platform: 'android',
    isAndroid: true,

    // Attach flow: picker -> synthetic path -> bytes for file.attach.
    selectPaths: function (options) {
      // Folder pickers rely on host path semantics the agent could read; on a
      // phone the bytes live behind content URIs, so only files are supported.
      if (options && options.directories) return Promise.resolve([])
      return pickViaInput(false)
    },
    readFileDataUrl: readPickedFile,
    readFileDataUrlForAttach: readPickedFile,
    getPathForFile: function (file) { return registerFile(file) },
    // Clipboard images: the renderer asks the shell to stage the clipboard into
    // a file path; WebView can read the clipboard directly when permitted.
    saveClipboardImage: async function () {
      try {
        if (!navigator.clipboard || !navigator.clipboard.read) return null
        var items = await navigator.clipboard.read()
        for (var i = 0; i < items.length; i++) {
          var types = items[i].types || []
          for (var j = 0; j < types.length; j++) {
            if (String(types[j]).indexOf('image/') === 0) {
              var blob = await items[i].getType(types[j])
              var ext = String(types[j]).split('/')[1] || 'png'
              return registerFile(new File([blob], 'clipboard.' + ext, { type: types[j] }))
            }
          }
        }
      } catch (e) { /* permission denied or unsupported */ }
      return null
    },

    writeClipboard: async function (text) {
      try { await navigator.clipboard.writeText(String(text == null ? '' : text)); return true } catch (e) { return false }
    },
    readClipboard: async function () {
      try { return await navigator.clipboard.readText() } catch (e) { return '' }
    },
    openExternal: function (url) {
      var target = String(url || '')
      // The Android shell exposes a native bridge so links open in the browser
      // instead of replacing the app inside the WebView.
      try {
        if (window.HermesNative && window.HermesNative.openExternal) {
          window.HermesNative.openExternal(target)
          return true
        }
      } catch (e) { /* fall through */ }
      try { window.open(target, '_blank'); return true } catch (e) { return false }
    },
    requestMicrophoneAccess: async function () { return true },
    requestCameraAccess: async function () { return true },
    getOnBattery: async function () { return false },
    onBatteryChanged: unsubscribe,
    notify: async function () { return false },

    // Window chrome: there is no OS window to drive on Android.
    onWindowState: unsubscribe,
    onWindowStateChange: unsubscribe,
    getWindowState: async function () { return { isFullscreen: false, isMaximized: false } },
    setWindowState: async function () { return { ok: true } },

    // Update channels are a desktop concern; report "nothing to install".
    updates: {
      check: async function () { return null },
      download: async function () { return null },
      install: async function () { return null },
      onProgress: unsubscribe
    },
    updateHold: inert(),
    uninstall: inert(),
    windowControls: inert(),
    connections: inert(),
    cloud: inert(),
    // Profile services: the renderer normalizes the ACTIVE profile name from
    // these, so they must resolve to plain data (never an inert proxy).
    profile: {
      getDefault: async function () { return null },
      setDefault: async function () { return { connectionId: null, profile: 'default' } },
      getActive: async function () { return null },
      set: async function (name) { return { connectionId: null, profile: typeof name === 'string' ? name : 'default' } },
      list: async function () { return [] },
      onDefaultChanged: unsubscribe
    },
    settings: inert(),
    terminal: inert(),
    themes: inert(),
    petOverlay: inert(),
    quickEntry: inert(),
    hud: inert(),
    chatOnboarding: inert(),
    screenshot: inert()
  }

  function makeBridge() {
    var target = real
    return new Proxy(target, {
      get: function (t, key) {
        if (key in t) return t[key]
        if (key === 'then' || key === 'toJSON' || typeof key === 'symbol') return undefined
        if (typeof key === 'string' && /^on[A-Z]/.test(key)) return unsubscribe
        return inert()
      }
    })
  }

  window.hermesDesktop = makeBridge()
  window.__hermesAndroid = {
    getConfig: readConfig,
    api: api,
    baseUrl: baseUrl,
    wsUrl: wsUrl,
    reload: function (next) {
      try { localStorage.setItem(STORE_KEY, JSON.stringify(next || DEFAULT_CONFIG)) } catch (e) { /* ignore */ }
      location.reload()
    }
  }
})()
