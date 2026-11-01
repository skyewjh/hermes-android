#!/usr/bin/env bash
# One-command mobile server: Hermes gateway (loopback) + LAN bridge.
#
#   bash tools/start-server.sh              # start, print phone URL + token
#   bash tools/start-server.sh --stop       # stop both processes
#   bash tools/start-server.sh --token XXX  # use a specific token
#   bash tools/start-server.sh --gateway-port 9119 --bridge-port 9122
#
# The token is generated once and reused from tools/.session-token, so the phone
# keeps working across restarts. Delete that file to rotate it.
set -euo pipefail

ROOT="D:/hermes_android"
STATE="$ROOT/tools/.run"
TOKEN_FILE="$ROOT/tools/.session-token"
GATEWAY_PORT=9119
BRIDGE_PORT=9122
TOKEN=""
DO_STOP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --stop) DO_STOP=1; shift ;;
    --token) TOKEN="$2"; shift 2 ;;
    --gateway-port) GATEWAY_PORT="$2"; shift 2 ;;
    --bridge-port) BRIDGE_PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

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
}

if [ "$DO_STOP" = "1" ]; then
  stop_all
  exit 0
fi

mkdir -p "$STATE"
stop_all

# ---------------------------------------------------------------- token
if [ -z "$TOKEN" ]; then
  if [ -f "$TOKEN_FILE" ]; then
    TOKEN="$(cat "$TOKEN_FILE")"
  else
    TOKEN="$(python -c "import secrets;print(secrets.token_urlsafe(32))")"
    printf '%s' "$TOKEN" > "$TOKEN_FILE"
    echo "generated token -> $TOKEN_FILE"
  fi
fi

LAN_IP="$(python -c "import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(('8.8.8.8',80));print(s.getsockname()[0])")"

# The gateway answers with keep-alive, so curl's exit status can be 28 (timeout)
# even after a complete 200 response — always branch on the status code.
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
  hermes serve --skip-build --host 127.0.0.1 --port "$GATEWAY_PORT" \
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
python "$ROOT/tools/loopback-bridge.py" --gateway-port "$GATEWAY_PORT" --listen-port "$BRIDGE_PORT" \
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
  停止:      bash tools/start-server.sh --stop
  换 token:  删除 tools/.session-token 后重启
─────────────────────────────────────────────────────────────────────
EOF
