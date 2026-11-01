package com.nous.hermes.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Hermes for Android.
 *
 * The app is a thin native shell: it bundles the same React renderer the Hermes
 * desktop app runs, serves it from a loopback HTTP server (so the gateway's CORS
 * and the renderer's absolute-path imports behave exactly like on desktop), and
 * implements the `window.hermesDesktop` bridge in JavaScript (see
 * assets/www/hermes-android-bridge.js). Everything the agent does — sessions,
 * streaming, tools, skills, cron, settings — travels over the gateway's normal
 * REST + JSON-RPC WebSocket protocol, so feature parity comes from reusing the
 * desktop bundle rather than re-implementing it.
 */
public class MainActivity extends Activity implements AssetServer.ConfigProvider {

    private static final String PREFS = "hermes_android";
    private static final String KEY_LEGACY_URL = "base_url";
    private static final String KEY_LEGACY_TOKEN = "token";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_ACTIVE = "active_server";

    private static final int REQUEST_FILE = 1001;

    private final ExecutorService probePool = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private AssetServer server;
    private WebView webView;
    private ProgressBar spinner;
    private ValueCallback<Uri[]> pendingFileCallback;
    private String pendingBaseUrl = "";
    private String pendingToken = "";
    private String screen = "list";
    private final java.util.List<ServerProfile> servers = new java.util.ArrayList<>();

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadServers();
        ServerProfile active = activeServer();
        if (active != null) {
            pendingBaseUrl = active.url;
            pendingToken = active.token;
            showWeb();
        } else {
            showServerList();
        }
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        probePool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public String baseUrl() {
        return pendingBaseUrl;
    }

    @Override
    public String token() {
        return pendingToken;
    }

    // --------------------------------------------------------- server profiles

    static final class ServerProfile {
        final String id;
        String name;
        String url;
        String token;

        ServerProfile(String id, String name, String url, String token) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
            this.token = token == null ? "" : token;
        }

        String label() {
            return name == null || name.trim().isEmpty() ? defaultName(url) : name.trim();
        }
    }

    private void loadServers() {
        servers.clear();
        String raw = prefs.getString(KEY_SERVERS, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    String id = o.optString("id");
                    if (id.isEmpty()) id = UUID.randomUUID().toString();
                    servers.add(new ServerProfile(id, o.optString("name"), o.optString("url"), o.optString("token")));
                }
            } catch (JSONException ignored) {
                // Corrupt store: start clean rather than crash on launch.
            }
        }

        // Migrate the single-server installs from before the list existed.
        if (servers.isEmpty()) {
            String legacyUrl = prefs.getString(KEY_LEGACY_URL, "");
            String legacyToken = prefs.getString(KEY_LEGACY_TOKEN, "");
            if (legacyUrl != null && !legacyUrl.isEmpty()) {
                ServerProfile migrated = new ServerProfile(
                        UUID.randomUUID().toString(), defaultName(legacyUrl), legacyUrl,
                        legacyToken == null ? "" : legacyToken);
                servers.add(migrated);
                prefs.edit().putString(KEY_ACTIVE, migrated.id).apply();
                saveServers();
            }
        }
    }

    private void saveServers() {
        JSONArray array = new JSONArray();
        for (ServerProfile profile : servers) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", profile.id);
                o.put("name", profile.name);
                o.put("url", profile.url);
                o.put("token", profile.token);
            } catch (JSONException ignored) {
                continue;
            }
            array.put(o);
        }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply();
    }

    private ServerProfile activeServer() {
        String activeId = prefs.getString(KEY_ACTIVE, "");
        if (activeId == null || activeId.isEmpty()) return null;
        for (ServerProfile profile : servers) {
            if (profile.id.equals(activeId)) return profile;
        }
        return null;
    }

    private void upsertServer(ServerProfile profile) {
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id.equals(profile.id)) {
                servers.set(i, profile);
                saveServers();
                return;
            }
        }
        servers.add(profile);
        saveServers();
    }

    private void deleteServer(ServerProfile profile) {
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id.equals(profile.id)) {
                servers.remove(i);
                break;
            }
        }
        if (profile.id.equals(prefs.getString(KEY_ACTIVE, ""))) {
            prefs.edit().remove(KEY_ACTIVE).apply();
        }
        saveServers();
    }

    private static String defaultName(String url) {
        String value = url == null ? "" : url.trim();
        value = value.replaceFirst("^[a-zA-Z]+://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.isEmpty() ? "Hermes" : value;
    }

    // ------------------------------------------------------------ server list

    private void showServerList() {
        screen = "list";
        teardownWeb();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);

        LinearLayout root = column(dp(20), dp(40), dp(20), dp(24));
        root.addView(heading(getString(R.string.servers_title)));
        root.addView(caption(getString(R.string.servers_hint)), marginTop(dp(8)));

        String activeId = prefs.getString(KEY_ACTIVE, "");
        if (servers.isEmpty()) {
            root.addView(caption(getString(R.string.servers_empty)), marginTop(dp(28)));
        }
        for (final ServerProfile profile : servers) {
            boolean active = profile.id.equals(activeId);
            root.addView(serverCard(profile, active), marginTop(dp(10)));
        }

        Button add = new Button(this);
        add.setText(R.string.servers_add);
        root.addView(add, marginTop(dp(24)));
        add.setOnClickListener(v -> showServerForm(null));

        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        installSystemBarInsets();
    }

    private View serverCard(final ServerProfile profile, boolean active) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(14), dp(12), dp(10), dp(12));
        card.setBackground(roundedSurface());

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(profile.label() + (active ? "  ✓" : ""));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(Color.parseColor(active ? "#1B7F3B" : "#111111"));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        labels.addView(name);

        TextView url = new TextView(this);
        url.setText(profile.url);
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        url.setTextColor(Color.parseColor("#666666"));
        url.setSingleLine(true);
        labels.addView(url, marginTop(dp(2)));

        TextView token = new TextView(this);
        token.setText(profile.token.isEmpty() ? "无 token" : "token " + mask(profile.token));
        token.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        token.setTextColor(Color.parseColor("#999999"));
        labels.addView(token, marginTop(dp(2)));

        card.addView(labels);

        Button edit = smallButton(getString(R.string.servers_edit));
        edit.setOnClickListener(v -> showServerForm(profile));
        card.addView(edit);

        Button delete = smallButton("✕");
        delete.setOnClickListener(v -> confirmDelete(profile));
        card.addView(delete);

        card.setOnClickListener(v -> connectTo(profile));
        return card;
    }

    private void confirmDelete(final ServerProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.servers_delete_title))
                .setMessage(profile.label() + "\n" + profile.url)
                .setPositiveButton(getString(R.string.servers_delete), (dialog, which) -> {
                    deleteServer(profile);
                    showServerList();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void connectTo(final ServerProfile profile) {
        Toast.makeText(this, getString(R.string.servers_connecting, profile.label()), Toast.LENGTH_SHORT).show();
        probePool.execute(() -> {
            final String error = probe(profile.url, profile.token);
            runOnUiThread(() -> {
                if (error == null) {
                    prefs.edit().putString(KEY_ACTIVE, profile.id).apply();
                    pendingBaseUrl = profile.url;
                    pendingToken = profile.token;
                    showWeb();
                } else {
                    Toast.makeText(this, getString(R.string.setup_fail) + error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ------------------------------------------------------------ server form

    private void showServerForm(final ServerProfile existing) {
        screen = "form";
        final boolean editing = existing != null;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);

        LinearLayout root = column(dp(24), dp(40), dp(24), dp(24));
        root.addView(heading(getString(editing ? R.string.server_edit_title : R.string.server_add_title)));

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.server_name_hint);
        nameInput.setSingleLine(true);
        if (editing) nameInput.setText(existing.name);
        root.addView(nameInput, marginTop(dp(20)));

        final EditText urlInput = new EditText(this);
        urlInput.setHint(R.string.setup_hint_url);
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        urlInput.setText(editing ? existing.url : "http://192.168.1.20:9122");
        root.addView(urlInput, marginTop(dp(8)));

        final EditText tokenInput = new EditText(this);
        tokenInput.setHint(R.string.setup_hint_token);
        tokenInput.setSingleLine(true);
        if (editing) tokenInput.setText(existing.token);
        root.addView(tokenInput, marginTop(dp(8)));

        final TextView status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTextColor(Color.parseColor("#B00020"));
        root.addView(status, marginTop(dp(16)));

        final Button save = new Button(this);
        save.setText(R.string.setup_connect);
        root.addView(save, marginTop(dp(8)));

        Button saveOnly = new Button(this);
        saveOnly.setText(R.string.server_save_only);
        root.addView(saveOnly, marginTop(dp(4)));
        saveOnly.setOnClickListener(v -> {
            String url = normalize(urlInput.getText().toString().trim());
            if (url.isEmpty()) {
                status.setText(R.string.server_error_url);
                return;
            }
            String name = nameInput.getText().toString().trim();
            ServerProfile profile = new ServerProfile(
                    editing ? existing.id : UUID.randomUUID().toString(),
                    name.isEmpty() ? defaultName(url) : name,
                    url, tokenInput.getText().toString().trim());
            upsertServer(profile);
            showServerList();
        });

        Button cancel = new Button(this);
        cancel.setText(R.string.cancel);
        root.addView(cancel, marginTop(dp(4)));
        cancel.setOnClickListener(v -> showServerList());

        if (editing) {
            Button remove = new Button(this);
            remove.setText(R.string.servers_delete_this);
            remove.setTextColor(Color.parseColor("#B00020"));
            root.addView(remove, marginTop(dp(4)));
            remove.setOnClickListener(v -> confirmDelete(existing));
        }

        save.setOnClickListener(v -> {
            final String url = normalize(urlInput.getText().toString().trim());
            final String token = tokenInput.getText().toString().trim();
            final String name = nameInput.getText().toString().trim();
            if (url.isEmpty()) {
                status.setText(R.string.server_error_url);
                return;
            }
            status.setTextColor(Color.parseColor("#666666"));
            status.setText(R.string.setup_testing);
            save.setEnabled(false);
            probePool.execute(() -> {
                final String error = probe(url, token);
                runOnUiThread(() -> {
                    save.setEnabled(true);
                    if (error != null) {
                        status.setTextColor(Color.parseColor("#B00020"));
                        status.setText(getString(R.string.setup_fail) + error);
                        return;
                    }
                    ServerProfile profile = new ServerProfile(
                            editing ? existing.id : UUID.randomUUID().toString(),
                            name.isEmpty() ? defaultName(url) : name, url, token);
                    upsertServer(profile);
                    prefs.edit().putString(KEY_ACTIVE, profile.id).apply();
                    pendingBaseUrl = profile.url;
                    pendingToken = profile.token;
                    showWeb();
                });
            });
        });

        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        installSystemBarInsets();
    }

    // ------------------------------------------------------------- ui helpers

    private LinearLayout column(int left, int top, int right, int bottom) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(left, top, right, bottom);
        return layout;
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = value;
        return params;
    }

    private TextView heading(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        view.setTextColor(Color.BLACK);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView caption(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(Color.parseColor("#666666"));
        return view;
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(4);
        button.setLayoutParams(params);
        return button;
    }

    private android.graphics.drawable.GradientDrawable roundedSurface() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(12));
        drawable.setColor(Color.WHITE);
        drawable.setStroke(dp(1), Color.parseColor("#E2E2E2"));
        return drawable;
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 8) return "••••";
        return token.substring(0, 4) + "…" + token.substring(token.length() - 4);
    }

    /** GET /api/status. Null means reachable; otherwise a human-readable error. */
    private String probe(String baseUrl, String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/api/status");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("X-Hermes-Session-Token", token);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            if (code == 200) return null;
            if (code == 401 || code == 403) return "认证失败（HTTP " + code + "），请检查 Token";
            return "HTTP " + code;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String normalize(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) return "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    // ---------------------------------------------------------------- web view

    private void showWeb() {
        screen = "web";
        teardownWeb();
        FrameLayout container = new FrameLayout(this);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " HermesAndroid/0.1");
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        webView.setBackgroundColor(Color.WHITE);
        webView.addJavascriptInterface(new NativeBridge(), "HermesNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (scheme.startsWith("http") && !isLocal(uri)) {
                    openExternally(uri);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (spinner != null) spinner.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String[] accept = params.getAcceptTypes();
                String type = "*/*";
                if (accept != null) {
                    for (String candidate : accept) {
                        if (candidate != null && candidate.contains("/") && !candidate.startsWith(".")) {
                            type = candidate;
                            break;
                        }
                    }
                }
                intent.setType(type);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingFileCallback = null;
                    return false;
                }
                return true;
            }
        });

        spinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(dp(36), dp(36));
        spinnerLp.gravity = Gravity.CENTER;
        spinner.setLayoutParams(spinnerLp);

        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(spinner);
        setContentView(container);
        installSystemBarInsets();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        startServerAndLoad();
    }

    private void startServerAndLoad() {
        try {
            if (server != null) server.stop();
            server = new AssetServer(this, this);
            int port = server.start();
            webView.loadUrl("http://127.0.0.1:" + port + "/index.html");
        } catch (Exception e) {
            Toast.makeText(this, "启动本地前端失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Drops the current WebView and its loopback server before showing another screen. */
    private void teardownWeb() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
    }

    private boolean isLocal(Uri uri) {
        String host = uri.getHost();
        return host != null && (host.equals("127.0.0.1") || host.equals("localhost"));
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        result[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(result);
                pendingFileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if ("form".equals(screen)) {
            showServerList();
            return;
        }
        if (!"web".equals(screen)) {
            finish();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Hermes")
                .setItems(new CharSequence[]{"服务器列表", "退出"}, (dialog, which) -> {
                    if (which == 0) {
                        showServerList();
                    } else {
                        finish();
                    }
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Apps targeting SDK 35+ are drawn edge-to-edge, so the WebView would run
     * under the status bar and the gesture/navigation bar. Pad the activity's
     * content with the system-bar (and IME) insets instead — the renderer then
     * lays out inside the usable area, and the composer clears the keyboard.
     */
    private void installSystemBarInsets() {
        final View content = findViewById(android.R.id.content);
        if (content == null) return;
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        content.requestApplyInsets();
    }

    /** Small native surface for the JS bridge (window.open is not enough on Android). */
    private final class NativeBridge {
        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> {
                if (url == null || url.isEmpty()) return;
                openExternally(Uri.parse(url));
            });
        }

        @JavascriptInterface
        public String platform() {
            return "android";
        }

        @JavascriptInterface
        public String appVersion() {
            return "0.1.3";
        }
    }
}
