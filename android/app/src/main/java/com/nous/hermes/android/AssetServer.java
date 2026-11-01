package com.nous.hermes.android;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serves the bundled desktop renderer (assets/www) to the WebView over
 * http://127.0.0.1:<port>.
 *
 * Why a local HTTP server instead of file:// or WebViewAssetLoader: the Hermes
 * gateway only allows CORS from loopback origins ("^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?$"),
 * and the renderer calls the gateway's REST API with fetch() from its own origin.
 * Serving the bundle from 127.0.0.1 makes those calls pass the gateway's CORS
 * check exactly like the desktop's own dashboard does.
 */
final class AssetServer {

    interface ConfigProvider {
        String baseUrl();

        String token();
    }

    private static final String ASSET_ROOT = "www";

    private final AssetManager assets;
    private final ConfigProvider config;
    private final ExecutorService pool = Executors.newFixedThreadPool(12);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread acceptThread;

    AssetServer(Context context, ConfigProvider config) {
        this.assets = context.getAssets();
        this.config = config;
    }

    int start() throws IOException {
        server = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "hermes-assets");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return server.getLocalPort();
    }

    void stop() {
        running.set(false);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket client = server.accept();
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (running.get()) {
                    // Transient accept failure; keep serving.
                    continue;
                }
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(15_000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            // Drain headers so the client's write side completes.
            String header;
            do {
                header = readLine(in);
            } while (header != null && !header.isEmpty());

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeSimple(out, 405, "text/plain; charset=utf-8", "method not allowed");
                return;
            }

            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            path = URLDecoder.decode(path, "UTF-8");
            if (path.isEmpty() || "/".equals(path)) path = "/index.html";

            if (path.equals("/hermes-android-bridge.js")) {
                byte[] body = injectedBridge().getBytes(StandardCharsets.UTF_8);
                writeBytes(out, 200, "text/javascript; charset=utf-8", body, false);
                return;
            }

            String assetPath = ASSET_ROOT + path;
            if (assetPath.contains("..")) {
                writeSimple(out, 400, "text/plain; charset=utf-8", "bad path");
                return;
            }

            try (InputStream asset = assets.open(assetPath)) {
                String mime = mimeType(path);
                boolean cacheable = !path.endsWith(".html");
                writeStream(out, mime, asset, cacheable);
            } catch (IOException missing) {
                writeSimple(out, 404, "text/plain; charset=utf-8", "not found: " + path);
            }
        } catch (Exception ignored) {
            // A dropped connection is normal (WebView cancels prefetches).
        }
    }

    /** The bridge file plus the connection the native setup screen stored. */
    private String injectedBridge() throws IOException {
        String source;
        try (InputStream is = assets.open(ASSET_ROOT + "/hermes-android-bridge.js")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = is.read(chunk)) != -1) buffer.write(chunk, 0, read);
            source = buffer.toString("UTF-8");
        }
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("baseUrl", config.baseUrl());
            cfg.put("token", config.token());
        } catch (Exception ignored) {
        }
        return "window.__HERMES_ANDROID_CONFIG__ = " + cfg.toString() + ";\n" + source;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static String mimeType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".map")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static void writeSimple(OutputStream out, int status, String mime, String body) throws IOException {
        writeBytes(out, status, mime, body.getBytes(StandardCharsets.UTF_8), false);
    }

    private static void writeBytes(OutputStream out, int status, String mime, byte[] body, boolean cacheable)
            throws IOException {
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : "Error";
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        head.append("Content-Type: ").append(mime).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append(cacheable ? "Cache-Control: max-age=600\r\n" : "Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void writeStream(OutputStream out, String mime, InputStream body, boolean cacheable)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: ").append(mime).append("\r\n");
        head.append("Transfer-Encoding: chunked\r\n");
        head.append(cacheable ? "Cache-Control: max-age=600\r\n" : "Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));

        byte[] chunk = new byte[16 * 1024];
        int read;
        while ((read = body.read(chunk)) != -1) {
            out.write((Integer.toHexString(read) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(chunk, 0, read);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
