#!/usr/bin/env bash
# One-command mobile server: Hermes gateway (loopback) + LAN bridge.
#
#   bash tools/start-server.sh              # start, print phone URL + token, return
#   bash tools/start-server.sh --foreground # start and stay attached (Ctrl+C stops)
#   bash tools/start-server.sh --stop       # stop both processes
#   bash tools/start-server.sh --token XXX  # use a specific token
#   bash tools/start-server.sh --gateway-port 9119 --bridge-port 9122
#
# Windows: double-click start-server.bat, which runs this script with --foreground.
#
# The token is generated once and reused from tools/.session-token, so the phone
# keeps working across restarts. Delete that file to rotate it.
set -euo pipefail

SCRIPT="${BASH_SOURCE[0]}"
case "$SCRIPT" in *\\*) SCRIPT="$(printf '%s' "$SCRIPT" | tr '\\' '/')" ;; esac
HERE="$(cd "$(dirname "$SCRIPT")" && pwd)"
# Native tools (python.exe, hermes.exe) need a Windows path; bash builtins are
# fine with the MSYS one. cygpath is authoritative regardless of how the shell
# was entered (double-clicked .bat included).
HERE_WIN="$(cygpath -w "$HERE" 2>/dev/null || echo "$HERE")"
ROOT="$(cd "$HERE/.." && pwd)"
STATE="$HERE/.run"
TOKEN_FILE="$HERE/.session-token"
GATEWAY_PORT=9119
BRIDGE_PORT=9122
TOKEN=""
DO_STOP=0
FOREGROUND=0

while [ $# -gt 0 ]; do
  case "$1" in
    --stop) DO_STOP=1; shift ;;
    --foreground) FOREGROUND=1; shift ;;
    --token) TOKEN="$2"; shift 2 ;;
    --gateway-port) GATEWAY_PORT="$2"; shift 2 ;;
    --bridge-port) BRIDGE_PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

# --------------------------------------------------------------- tool lookup
# A double-clicked .bat starts with Explorer's PATH, not the Hermes shell's, so
# resolve the interpreter and the CLI by absolute path instead of trusting PATH.
LOCAL_HERMES="${LOCALAPPDATA:-$HOME/AppData/Local}/hermes"

PY="${PYTHON_BIN:-}"
if [ -z "$PY" ]; then
  for candidate in \
    "$LOCAL_HERMES/hermes-agent/venv/Scripts/python.exe" \
    "$(command -v python 2>/dev/null || true)" \
    "$(command -v python3 2>/dev/null || true)"
  do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then PY="$candidate"; break; fi
  done
fi
if [ -z "$PY" ]; then
  echo "找不到 python，无法启动。" >&2
  exit 1
fi

if [ -z "${HERMES_BIN:-}" ]; then
  for candidate in \
    "$(command -v hermes 2>/dev/null || true)" \
    "$LOCAL_HERMES/hermes-agent/venv/Scripts/hermes.exe" \
    "$LOCAL_HERMES/.hermes/bin/hermes.exe"
  do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then HERMES_BIN="$candidate"; break; fi
  done
fi
if [ -z "${HERMES_BIN:-}" ]; then
  echo "找不到 hermes 命令，请先安装 Hermes Agent。" >&2
  exit 1
fi

stop_all() {
  if [ -d "$STATE" ]; then
    for f in "$STATE"/*.pid; do
      [ -e "$f" ] || continue
      pid="$(cat "$f" 2>/dev/null || true)"
      if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        echo "stopped $(basename "$f" .pid) (pid $pid)"
      fi
      rm -f "$f"
    done
  fi
  # Sweep listeners left behind by an interrupted run (pid file already gone):
  # the ports are recorded by this script, so anyone still holding them is ours.
  if [ -f "$STATE/ports" ]; then
    old_gw="$(sed -n 's/^GATEWAY_PORT=//p' "$STATE/ports" | head -1)"
    old_br="$(sed -n 's/^BRIDGE_PORT=//p' "$STATE/ports" | head -1)"
    for port in "$old_gw" "$old_br"; do
      [ -n "$port" ] || continue
      leftover="$(netstat -ano 2>/dev/null | grep -E "127\.0\.0\.1:$port .*LISTENING|0\.0\.0\.0:$port .*LISTENING" | awk '{print $5}' | head -1 || true)"
      if [ -n "$leftover" ]; then
        kill "$leftover" 2>/dev/null || true
        echo "swept leftover listener on port $port (pid $leftover)"
      fi
    done
    rm -f "$STATE/ports"
  fi
}

if [ "$DO_STOP" = "1" ]; then
  stop_all
  exit 0
fi

mkdir -p "$STATE"
stop_all
printf 'GATEWAY_PORT=%s\nBRIDGE_PORT=%s\n' "$GATEWAY_PORT" "$BRIDGE_PORT" > "$STATE/ports"

# ---------------------------------------------------------------- token
if [ -z "$TOKEN" ]; then
  if [ -f "$TOKEN_FILE" ]; then
    TOKEN="$(cat "$TOKEN_FILE")"
  else
    TOKEN="$("$PY" -c "import secrets;print(secrets.token_urlsafe(32))")"
    printf '%s' "$TOKEN" > "$TOKEN_FILE"
    echo "generated token -> $TOKEN_FILE"
  fi
fi

LAN_IP="$("$PY" -c "import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(('8.8.8.8',80));print(s.getsockname()[0])" 2>/dev/null || true)"
if [ -z "$LAN_IP" ]; then
  LAN_IP="$(ipconfig 2>/dev/null | tr -d '\r' | grep -oE '192\.168\.[0-9]+\.[0-9]+|10\.[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
fi
[ -n "$LAN_IP" ] || LAN_IP="127.0.0.1"

# The gateway answers keep-alive, so curl's exit status can be 28 (timeout) even
# after a complete 200 response — always branch on the status code.
http_code() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 4 -H "X-Hermes-Session-Token: $TOKEN" "$1" 2>/dev/null || true
}

# ---------------------------------------------------------------- gateway
if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$GATEWAY_PORT/api/status" 2>/dev/null || true)" = "200" ]; then
  echo "port $GATEWAY_PORT is already serving something."
  echo "  - if it is a previous run of this script: bash tools/start-server.sh --stop"
  echo "  - otherwise pass --gateway-port <free port> (and --bridge-port too)"
  exit 1
fi

echo "starting gateway on 127.0.0.1:$GATEWAY_PORT ..."
HERMES_DASHBOARD_SESSION_TOKEN="$TOKEN" \
  "$HERMES_BIN" serve --skip-build --host 127.0.0.1 --port "$GATEWAY_PORT" \
  > "$STATE/gateway.log" 2>&1 &
echo $! > "$STATE/gateway.pid"

ready=0
for i in $(seq 1 150); do
  if [ "$(http_code "http://127.0.0.1:$GATEWAY_PORT/api/sessions?limit=1")" = "200" ]; then
    ready=1
    break
  fi
  if [ $((i % 15)) -eq 0 ]; then printf '  still starting (%ss)...\n' "$i"; fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "gateway did not become ready in 150s — see $STATE/gateway.log" >&2
  tail -20 "$STATE/gateway.log" >&2 || true
  stop_all
  exit 1
fi

# ---------------------------------------------------------------- bridge
echo "starting LAN bridge on 0.0.0.0:$BRIDGE_PORT ..."
"$PY" "$HERE_WIN/loopback-bridge.py" --gateway-port "$GATEWAY_PORT" --listen-port "$BRIDGE_PORT" \
  > "$STATE/bridge.log" 2>&1 &
echo $! > "$STATE/bridge.pid"
sleep 2

if [ "$(http_code "http://$LAN_IP:$BRIDGE_PORT/api/sessions?limit=1")" != "200" ]; then
  echo "bridge check failed — see $STATE/bridge.log" >&2
  tail -20 "$STATE/bridge.log" >&2 || true
  stop_all
  exit 1
fi

cat <<EOF

──────────────────────────── 手机端填这个 ────────────────────────────
  网关地址   http://$LAN_IP:$BRIDGE_PORT
  Token      $TOKEN

  手机与电脑需在同一局域网。App 内点"测试并保存"即可进入。
  停止:      bash tools/start-server.sh --stop   （Windows 双击 stop-server.bat）
  换 token:  删除 tools/.session-token 后重启
─────────────────────────────────────────────────────────────────────
EOF

if [ "$FOREGROUND" = "1" ]; then
  echo
  echo "服务运行中：保持这个窗口开着，按 Ctrl+C（或关闭窗口）即停止。"
  trap 'echo; stop_all; exit 0' INT TERM
  while true; do sleep 3600; done
fi
