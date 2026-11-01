#!/usr/bin/env python
"""HTTP/WebSocket bridge: expose a loopback-bound Hermes gateway to your phone.

Why this exists
---------------
The Hermes gateway deliberately refuses a non-loopback bind unless an auth
provider (password or OAuth) is configured, and it rejects requests whose Host
header does not match the interface it bound to (DNS-rebinding defence). A plain
TCP forwarder therefore gets 400s: the phone's Host header names the PC's LAN
address while the gateway only trusts loopback names.

This bridge keeps the gateway on 127.0.0.1 and forwards a LAN port to it,
rewriting `Host` to the loopback authority on every request (HTTP and the
WebSocket upgrade alike). The gateway then treats the phone like a local
dashboard client, so the session token keeps working.

Usage
-----
    python tools/loopback-bridge.py --gateway-port 9119 --listen-port 9122

Then in the Android app use  http://<PC-LAN-IP>:9122  as the gateway URL and the
same HERMES_DASHBOARD_SESSION_TOKEN the gateway was started with.

Security: anyone who can reach the listen port can reach the gateway, and the
token is the only thing protecting it. Run it only on a network you trust.
"""
from __future__ import annotations

import argparse
import socket
import socketserver
import sys
import threading

MAX_HEADER = 64 * 1024


def read_headers(sock: socket.socket) -> tuple[bytes, bytes]:
    """Return (header_block, bytes_already_read_past_it)."""
    buffer = b""
    while b"\r\n\r\n" not in buffer:
        chunk = sock.recv(8192)
        if not chunk:
            raise ConnectionError("client closed before headers")
        buffer += chunk
        if len(buffer) > MAX_HEADER:
            raise ConnectionError("headers too large")
    head, _, rest = buffer.partition(b"\r\n\r\n")
    return head + b"\r\n\r\n", rest


def rewrite_headers(head: bytes, upstream_host: str, upstream_port: int, upgrade: bool = False) -> bytes:
    lines = head.decode("latin-1").split("\r\n")
    out = [lines[0]]
    drop = {"host", "keep-alive", "proxy-connection"}
    if not upgrade:
        drop.add("connection")
    for line in lines[1:]:
        if not line:
            continue
        name = line.split(":", 1)[0].strip().lower()
        if name in drop:
            continue
        out.append(line)
    out.append(f"Host: {upstream_host}:{upstream_port}")
    if not upgrade:
        out.append("Connection: close")
    return ("\r\n".join(out) + "\r\n\r\n").encode("latin-1")


def pump(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


class Bridge(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, listen, upstream, verbose=False):
        self.upstream = upstream
        self.verbose = verbose
        super().__init__(listen, Handler)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        client: socket.socket = self.request
        upstream = None
        try:
            client.settimeout(60)
            head, rest = read_headers(client)
            is_ws = b"upgrade: websocket" in head.lower()

            upstream = socket.create_connection(self.server.upstream, timeout=10)
            upstream.settimeout(None)
            upstream.sendall(rewrite_headers(head, *self.server.upstream, upgrade=is_ws))

            # Request body (POST/PUT): whatever arrived with the headers plus the
            # remainder promised by Content-Length.
            header_map = {}
            for line in head.decode("latin-1").split("\r\n")[1:]:
                if ":" in line:
                    key, value = line.split(":", 1)
                    header_map[key.strip().lower()] = value.strip()
            body = rest
            try:
                length = 0 if is_ws else int(header_map.get("content-length", "0") or 0)
            except ValueError:
                length = 0
            while len(body) < length:
                chunk = client.recv(min(65536, length - len(body)))
                if not chunk:
                    break
                body += chunk
            if body:
                upstream.sendall(body)

            if is_ws:
                watcher = threading.Thread(target=pump, args=(client, upstream), daemon=True)
                watcher.start()
                pump(upstream, client)
                watcher.join(timeout=1)
            else:
                pump(upstream, client)
        except Exception as exc:  # noqa: BLE001 - one bad request must not stop the bridge
            if getattr(self.server, "verbose", False):
                print(f"[bridge] {type(exc).__name__}: {exc}", file=sys.stderr)
        finally:
            if upstream is not None:
                try:
                    upstream.close()
                except OSError:
                    pass
            try:
                client.close()
            except OSError:
                pass


def lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


def main() -> int:
    parser = argparse.ArgumentParser(description="Expose a loopback Hermes gateway on the LAN")
    parser.add_argument("--gateway-port", type=int, default=9119, help="port the gateway listens on (127.0.0.1)")
    parser.add_argument("--listen-port", type=int, default=9122, help="LAN port to expose")
    parser.add_argument("--listen-host", default="0.0.0.0")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    upstream = ("127.0.0.1", args.gateway_port)
    with Bridge((args.listen_host, args.listen_port), upstream, args.verbose) as server:
        ip = lan_ip()
        print(f"[bridge] {args.listen_host}:{args.listen_port} -> 127.0.0.1:{args.gateway_port}")
        print(f"[bridge] phone gateway URL: http://{ip}:{args.listen_port}")
        print("[bridge] token: the HERMES_DASHBOARD_SESSION_TOKEN the gateway was started with")
        print("[bridge] Ctrl+C to stop")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\n[bridge] stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
