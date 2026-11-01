# Hermes for Android

一个把 **Hermes 桌面前端**完整搬到 Android 上的客户端：不是重写的精简版，而是把 Windows/macOS 桌面应用里那份 React 渲染层原样装进 WebView，再用一层 JS 桥接出 `window.hermesDesktop`，让桌面那份 UI 以为自己在 Electron 里。

所以桌面上有的东西——会话列表、流式对话、工具调用可视化、技能/工具集、定时任务、消息平台、产物面板、设置——在手机上是同一套代码、同一套行为。

```
D:\hermes_android
├─ web/                    桌面渲染层（从 apps/desktop/dist 复制而来，含桥接脚本）
│  ├─ hermes-android-bridge.js   ← window.hermesDesktop 的 Android 实现
│  └─ index.html                 ← 注入了桥接脚本
├─ android/                Android 工程（Java，零第三方依赖）
│  ├─ app/src/main/java/com/nous/hermes/android/
│  │  ├─ MainActivity.java        设置页 + WebView 外壳 + 文件选择 + 返回键
│  │  └─ AssetServer.java         127.0.0.1 静态服务器（给渲染层一个合法 Origin）
│  ├─ app/src/main/assets/www/    打包进 APK 的渲染层
│  └─ hermes-android-debug.apk    产物（已签名，可直接安装）
├─ tools/
│  ├─ build_apk.sh          aapt2 → javac → d8 → zipalign → apksigner 一键构建
│  ├─ sync_web.sh           把 web/ 同步进 assets 并裁掉 Electron 专用文件
│  └─ loopback-bridge.py    把只监听 127.0.0.1 的网关安全地暴露到局域网
└─ docs/ARCHITECTURE.md    设计取舍与已知边界
```

## 为什么是 WebView 而不是原生重写

桌面前端有 **132 个** `window.hermesDesktop.*` 调用点、上千个组件。原生重写不可能做到“功能一样”；而这套 UI 的产品能力（会话、流式、工具面板、cron、设置）全部走网关的 REST + JSON-RPC WebSocket，跟 Electron 外壳关系不大。于是：

- **真实实现**：`api()`（REST）、`getConnection()` / `getGatewayWsUrl()`（网关连接）
- **惰性降级**：更新器、窗口控制、宠物、HUD、系统对话框——桌面上是本地能力，Android 上退化成 no-op
- 结果是同一份 UI 在手机上照常启动、连网关、跑对话。

## 构建 APK

```bash
cd /d/hermes_android
bash tools/sync_web.sh      # 改过 web/ 里的桥接脚本后先同步
bash tools/build_apk.sh     # 产物: android/hermes-android-debug.apk
```

不需要 Gradle：应用零第三方依赖，`aapt2 → javac → d8 → zipalign → apksigner` 就是全链路（用 Android Studio 自带的 build-tools 36 与 JBR）。

## 在手机上跑起来

### 一键启动（推荐）

**Windows：双击项目根目录的 `start-server.bat`**（停止：双击 `stop-server.bat`）。窗口会一直开着跑网关 + 桥接，关掉窗口或 Ctrl+C 即停止。

如果更喜欢命令行：

```bash
bash tools/start-server.sh
# 输出示例：
#   网关地址   http://192.168.101.102:9122
#   Token      jcWqCwmPuLtLcVKTrcqegbuK9-eHG-sH_PXkjbkzs44
```

脚本做的事：生成并复用 `tools/.session-token` → 用它启动只监听 `127.0.0.1:9119` 的网关 → 起 `loopback-bridge.py` 到 `0.0.0.0:9122` → 用真实请求验证 token 与桥接都通 → 打印手机该填的两行。

- 停止：`bash tools/start-server.sh --stop`（或双击 `stop-server.bat`）
- 换 token：删掉 `tools/.session-token` 再启动
- 换端口：`--gateway-port 9119 --bridge-port 9122`
- 日志：`tools/.run/gateway.log`、`tools/.run/bridge.log`
- 依赖：Git for Windows（提供 bash）和 Hermes Agent 本体；脚本会自己按绝对路径找 `hermes.exe` / python，不依赖 PATH

### 手动启动（等价，分两条命令）

```bash
cd C:/Users/skyewjh/AppData/Local/hermes/hermes-agent
HERMES_DASHBOARD_SESSION_TOKEN=<自己编一个长随机串> hermes serve --skip-build --host 127.0.0.1 --port 9119
python tools/loopback-bridge.py --gateway-port 9119 --listen-port 9122
```

### 安装并连接

```bash
adb install -r android/hermes-android-debug.apk
```

打开 App 是**服务器列表**：连接过的网关都存在这里，点条目直接连；每条右侧有「编辑」「✕」；底部「＋ 添加服务器」。

添加/编辑页可以填 名称（可选）、网关地址、Token：

- 「测试并保存」—— 用 `GET /api/status` 真实探活，通过才保存并进入
- 「仅保存（暂不连接）」—— 服务器暂时离线时先存下来，回头点条目再连
- 编辑页额外有「删除此服务器」（带确认）

App 里按返回键（WebView 首页时）会弹「服务器列表 / 退出」，随时可以切服务器。首次从旧版本升级时，原来那一条配置会自动迁移成名为主机地址的条目。

Token 来源见 `tools/start-server.sh` 打印，或 `cat tools/.session-token`。

## 竖屏适配

桌面版的会话侧栏挂在 split-pane 布局树上，最小宽度是按桌面设的 —— 窗口一窄，整块 sessions pane 直接被丢掉，界面上也没有任何入口能把它叫回来（`hermes-android-bridge` 报 `Hide sidebar` 状态，但 DOM 里根本没有侧栏）。所以手机竖屏需要自己的一层：

`web/hermes-mobile.js` + `hermes-mobile.css`（同样打进 APK）：

- **左上角汉堡按钮** → 拉出抽屉：会话列表（最多 60 条，按最近活动排序）、搜索框、"＋ 新会话"
- 点条目 `location.hash = '#/<sessionId>'`，与桌面路由同构，直接用 App 自己的路由器切会话
- 抽屉用 App 解析出的页面背景色作不透明底色（主题色带透明度，否则底下文字会透出来）
- **顺手清掉桌面专属 chrome**：最小化/最大化/关闭、布局编辑器、HUD、交换侧栏、右侧栏、原生侧栏按钮 —— 这些在手机上要么没意义要么占掉唯一的标题栏
- 断点 760px，横屏/平板自动退回桌面布局；`viewport-fit=cover` + safe-area 处理刘海与手势条
- **系统栏避让由原生做**：`targetSdk 35+` 强制 edge-to-edge，WebView 会画到状态栏和手势条下面。`MainActivity.installSystemBarInsets()` 给 `android.R.id.content` 加 system-bars + display-cutout + IME 的 padding，页面就落在可用区域内，软键盘弹出时输入框也不会被压住。

效果（412×915）：

MEDIA:D:/hermes_android/docs/screenshot-phone.png

## 来源与许可

- `web/` 是 [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) 桌面渲染层的构建产物（MIT），本仓库只是把它装进 Android 外壳，并对 `window.hermesDesktop` 做了移动端实现。
- 本仓库其余代码（Android 外壳、桥接脚本、竖屏层、工具链）同样以 MIT 使用。
- `web/` 里的 bundle 会随上游版本变化；换版本时用 `tools/sync_web.sh` 从新的 `apps/desktop/dist` 同步。

## 已知边界

| 项目 | 状态 |
|---|---|
| 会话列表 / 切换 / 搜索 | ✅ 同桌面 |
| 流式对话、工具调用 | ✅ 同桌面 |
| 图片 / 文件附件 | ✅ 走桌面同一条链路（`selectPaths` → `readFileDataUrl` → `file.attach`），最多一次多选；**文件夹**附件不支持（手机没有可读的宿主路径） |
| 技能、工具集、MCP、定时任务、消息平台、设置 | ✅ 同桌面（全部走网关 API） |
| 工作区文件浏览器 / 预览面板 | ⚠️ 依赖本机文件系统的部分降级（Android 沙箱里没有电脑的磁盘） |
| 更新器 / 窗口控制 / 宠物 / HUD | ⚪️ no-op（桌面专有） |
| 局域网直连（不跑 bridge） | ❌ 需要网关配 `dashboard.basic_auth` 或 Nous OAuth；App 端的密码/OAuth 登录是下一步 |
| 手机端跑 agent 本体 | ❌ 这是客户端，agent 始终在你电脑/服务器上跑（Termux 版官方目前是坏的） |

安全提示：`loopback-bridge.py` 把网关暴露到局域网，**token 是唯一防线**，只在信任的网络里开，用完关掉。
