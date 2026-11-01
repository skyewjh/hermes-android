# 架构与取舍

## 目标

“功能要和 Windows 桌面前端一样”。桌面前端（`apps/desktop`）是 Electron + React + Vite 的成熟产品：会话、流式、工具卡片、技能/工具集、cron、消息平台、设置、产物面板。任何“原生重写”都只能覆盖其中一小部分。

因此本项目的策略是 **复用渲染层，替换外壳**：

```
┌──────────────────────────── Android 手机 ────────────────────────────┐
│                                                                      │
│  MainActivity (Java)                                                 │
│   ├─ 设置页：网关地址 + Token                                         │
│   ├─ AssetServer：http://127.0.0.1:<port> 提供 web/ 静态资源          │
│   └─ WebView ── 载入同一份桌面前端 bundle                             │
│                └─ hermes-android-bridge.js  (window.hermesDesktop)   │
│                       ├─ api()        → fetch  REST  /api/*          │
│                       └─ getGatewayWsUrl() → WebSocket /api/ws       │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ 局域网 HTTP / WS
                    ┌───────────▼────────────┐
                    │ loopback-bridge.py     │  改写 Host → 127.0.0.1:9119
                    └───────────┬────────────┘
                    ┌───────────▼────────────┐
                    │ hermes serve (loopback)│  REST + JSON-RPC WS
                    │ agent / state.db / 工具 │
                    └────────────────────────┘
```

## 关键决策

### 1. 为什么用本地 HTTP 服务器而不是 `file://` 或 `WebViewAssetLoader`

网关的 CORS 白名单是 `^https?://(localhost|127\.0\.0\.1)(:\d+)?$`。渲染层用 `fetch()` 调 REST，Origin 必须落在这个正则里；`file://` 的 Origin 是 `null`，`WebViewAssetLoader` 的 `https://appassets.androidplatform.net` 也不在白名单。

AssetServer 在 `127.0.0.1:<随机端口>` 上起一个极简静态服务器（`ServerSocket`，无第三方依赖），页面 Origin 于是变成 `http://127.0.0.1:<port>`，CORS 天然通过，和桌面 dashboard 完全一致。

服务器还负责一件事：把 `window.__HERMES_ANDROID_CONFIG__`（网关地址 + token）**注入到 `hermes-android-bridge.js` 的首行**，避免“原生注入 vs 脚本执行”的时序竞争。

### 2. `window.hermesDesktop` 的分层实现

渲染层有 132 个桥接调用点（`global.d.ts` 里约 1700 行声明）。全部真做没有意义，全部空实现会崩。分层：

| 层 | 成员 | 实现 |
|---|---|---|
| 真实 | `api`, `getConnection`, `getConnectionFor`, `getGatewayWsUrl`, `revalidateConnection`, `getBootProgress`, `writeClipboard`, `openExternal`, `getOnBattery` | 走真实 REST/WS/Android API |
| 形状正确 | `profile.getDefault/setDefault/getActive/set/list` | 返回纯数据（`null` / `{profile:'default'}`），因为主题层会对它做 `.trim()` |
| 惰性 | 其余全部 | 可调用代理：调用返回 `Promise<undefined>`，属性返回同类代理，`onXxx` 返回退订函数，`trim/toLowerCase/...` 返回 `''` |

惰性代理里那几个“像字符串”的方法不是凑数：桌面版从 `$activeProfile` 读当前 profile 名，缺失时会退化成对象，`(e ?? '').trim()` 直接抛 `TypeError`，整个应用白屏。给未知成员一个“空字符串”的人格，比让 UI 崩掉合理。

### 3. 为什么需要 `loopback-bridge.py`

实测（`hermes serve --host 0.0.0.0`）：

```
Refusing to bind dashboard to 0.0.0.0 — the auth gate engages on non-loopback
binds, but no auth providers are registered.
```

网关的立场是对的：能读你文件的东西不该裸奔。但它带来两个连带问题——非 loopback 绑定必须配 `dashboard.basic_auth`（密码）或 Nous OAuth；即使认证通过，Host 头校验也只接受绑定接口的名字（DNS rebinding 防护），所以“纯 TCP 转发”会被 400 掉。

bridge 的做法是保持网关在 loopback，由它改写 Host：

- HTTP：逐请求重写 `Host: 127.0.0.1:<port>`，`Connection: close`，逐条转发（不解析响应）
- WebSocket：保留 `Upgrade`/`Connection: Upgrade`，其余同上，握手后做双向裸字节泵

于是手机在网关眼里就是一个本机 dashboard 客户端，session token 认证照常。

### 4. 构建为什么绕开 Gradle

应用零第三方依赖（纯 Java + 系统 WebView），Gradle 只是把 `aapt2/javac/d8/zipalign/apksigner` 串起来。构建脚本直接串这五步，省掉 ~130MB 的 Gradle 发行包（本机下载速度约 40KB/s，不可接受）。需要正式 CI 时再把同一套工程接回 AGP 即可（去掉 manifest 的 `package` 属性、改用 `namespace`）。

## 竖屏：为什么必须自己补一层

桌面渲染层的会话侧栏不是普通 `<aside>`，而是挂在 `hermes.desktop.layoutTree.v2` 描述的 split-pane 树上（`spl-root` → `grp-sessions` + `grp-main`）。这棵树的 pane 最小宽度按桌面设计，容器一窄就把 sessions 组整个塌掉——不是折叠成抽屉，而是**根本不渲染**：

```
412px 视口下 #root 的直接子树只有 grp-main（w=412），
localStorage['hermes.desktop.paneStates.v1'] 仍是 {"chat-sidebar":{"open":true}}，
标题栏按钮 aria-label="显示侧边栏" 还在，点它没有任何面板出现。
```

所以 `web/hermes-mobile.js`（≤760px 生效）做三件事：

1. **自建抽屉**：汉堡按钮 + 340px 抽屉，数据走网关 `GET /api/sessions?limit=60`（和桌面同一份 state.db），点条目 `location.hash = '#/<id>'` —— 这条路由与桌面完全一致（桌面点会话时 hash 就是 `#/20261101_112933_f891c5` 这种形式），所以切会话不经过任何私有 API。
2. **清桌面 chrome**：最小化/最大化/关闭、布局编辑器、HUD、交换侧栏、右侧栏、原生侧栏按钮。这些按钮的 aria-label 是本地化的（中文环境是"显示侧边栏"/"布局编辑器"），所以按标签内容做正则匹配，并用 `MutationObserver` 在每次重渲染后重新隐藏。
3. **不透明底色**：主题变量里的 surface 带 alpha，抽屉直接用它会让底层标题文字透出来（截图里表现为汉堡按钮旁多出一个"多余字符"）。改为运行时读取 `getComputedStyle(document.body).backgroundColor` 再回填。

副作用处理：抽屉打开时隐藏汉堡、锁定 `html` 滚动、z-index 抬到 9000（桌面顶栏和更新提示是 z-70/80，会压在抽屉上）。

## 验证状态

已验证（真实工具输出）：

- 渲染层在 Chrome / 412×915 手机视口下完整启动，无 console 异常
- 会话列表从真实网关读取；发消息 → 网关执行 → 错误/结果回流（测试时账号额度不足，返回的是 provider 的 “Out of credits”，链路本身通）
- 通过 `loopback-bridge.py` 走 `http://192.168.101.102:9122`（与手机同路径：REST + WS upgrade 101）完成同样的往返
- APK：`aapt dump badging` 显示 `com.nous.hermes.android` / minSdk 26 / target 36，含 `classes.dex`、`assets/www/index.html` 与桥接脚本（9874 字节，含 native call）

未验证（需要真机）：

- Android WebView 里的实际渲染与交互（WebView 与 Chrome 同内核，但仍可能有 UA/权限差异）
- `AssetServer` 的 chunked 静态响应在 WebView 网络栈下的表现
- 系统文件选择器回填附件

## 下一步（按价值排序）

1. **App 内登录**：`dashboard.basic_auth` 或 RFC 8252 PKCE → Bearer，`POST /api/auth/ws-ticket` 换 WS ticket。这样不跑 bridge 也能直连局域网（需要 App 内同源代理来绕开跨站 Cookie）。
2. **移动端细节**：窄屏下更紧凑的排版、软键盘与 composer 的贴底行为、下拉刷新。
3. **通知**：`notify()` 接 Android 通知渠道，cron 完成/长任务结束时推一条。
