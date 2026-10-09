package ai.askruna.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import androidx.browser.customtabs.CustomTabsIntent;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * The chat screen: a full-screen WebView on Runa's page plus the JSON message bridge
 * (window.RunaAndroid.postMessage → app, window.RunaBridge.receive ← app). Opened by
 * {@link RunaChat#open}; declared in this library's manifest.
 */
public final class RunaChatActivity extends Activity {

    private static final String TAG = "RunaChat";
    private static final String BRIDGE_VERSION = "1.0";
    private static final long BACK_TIMEOUT_MS = 400;

    private static WeakReference<RunaChatActivity> currentRef = new WeakReference<>(null);

    static RunaChatActivity current() { return currentRef.get(); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private RunaChat.Session session;
    private WebView webView;
    private ProgressBar spinner;
    private View errorView;
    private String origin;
    private boolean pageReady;
    private int backSeq;
    private String pendingBack;
    private Object backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = RunaChat.session;
        if (session == null) { finish(); return; }   // process was restarted without RunaChat.open()
        currentRef = new WeakReference<>(this);

        String url = buildUrl(session.options);
        origin = originOf(url);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);    // debug builds of the app only
        }
        webView = new WebView(this);
        setUpWebView(webView);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        root.addView(spinner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        applyInsets(root);
        setContentView(root);
        setUpWindow();          // after setContentView: the window's decor (and its insets controller) exists now
        registerBack();
        webView.loadUrl(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) sendCart();     // back from the app's cart or product screen
    }

    @Override
    protected void onDestroy() {
        if (backCallback != null && Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
        }
        if (webView != null) {
            webView.removeJavascriptInterface("RunaAndroid");
            webView.destroy();
            webView = null;
        }
        if (currentRef.get() == this) currentRef = new WeakReference<>(null);
        if (isFinishing() && session != null) {
            try { session.callbacks.onClose(); } catch (Throwable t) { Log.w(TAG, "onClose failed", t); }
            if (RunaChat.session == session) RunaChat.session = null;   // release the app's callbacks
        }
        super.onDestroy();
    }

    // ── Window: edge to edge, light status bar, keyboard + system bars as padding ────────────────

    private void setUpWindow() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        } else {
            w.setStatusBarColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= 23) {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
    }

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            // Status bar, navigation bar and the keyboard all become padding: the page always fits
            // between them, and its input stays above the keyboard (Android 15 edge-to-edge too).
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        // Older Android: the window is not edge to edge and adjustResize (manifest) shrinks it for the keyboard.
    }

    // ── WebView ──────────────────────────────────────────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setUpWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // the conversation and the shopper id live in localStorage
        s.setTextZoom(100);                    // the page sizes itself; ignore system font scaling
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        wv.setBackgroundColor(Color.WHITE);
        wv.addJavascriptInterface(new Bridge(), "RunaAndroid");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeOutside(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                return routeOutside(u);
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                spinner.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showError();
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                if (Build.VERSION.SDK_INT < 23) showError();
            }
        });
    }

    /** The WebView stays on the chat page; any other URL leaves through the app. */
    private boolean routeOutside(String u) {
        if (u != null && origin != null && u.startsWith(origin)) return false;
        openLink(u);
        return true;
    }

    private View buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.WHITE);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView msg = new TextView(this);
        msg.setText("We couldn't load the assistant.\nPlease check your connection.");
        msg.setGravity(Gravity.CENTER);
        msg.setTextSize(16);
        msg.setTextColor(Color.parseColor("#333333"));
        box.addView(msg);
        Button retry = new Button(this);
        retry.setText("Try again");
        retry.setOnClickListener(v -> {
            errorView.setVisibility(View.GONE);
            spinner.setVisibility(View.VISIBLE);
            pageReady = false;
            webView.reload();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(retry, lp);
        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> finish());
        box.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void showError() {
        spinner.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
    }

    // ── Page → app ───────────────────────────────────────────────────────────────────────────

    /** Exposed to the page as window.RunaAndroid. Called on a WebView thread. */
    final class Bridge {
        @JavascriptInterface
        public void postMessage(String json) {
            main.post(() -> handle(json));
        }
    }

    private void handle(String json) {
        if (webView == null) return;
        String at = webView.getUrl();
        if (at == null || origin == null || !at.startsWith(origin)) return;   // only our page talks to the app
        JSONObject msg;
        try { msg = new JSONObject(json); } catch (JSONException e) { return; }
        String type = msg.optString("type", "");
        JSONObject payload = msg.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        if (session.options.debug) Log.d(TAG, "page→app " + type + " " + payload);
        RunaChat.Callbacks cb = session.callbacks;
        try {
            switch (type) {
                case "ready":
                    pageReady = true;
                    sendCart();
                    break;
                case "setQty": {
                    RunaChat.Product product = new RunaChat.Product(payload);
                    int qty = Math.max(0, payload.optInt("qty", 0));
                    cb.setQuantity(product, qty);
                    sendCart();       // the acknowledgement: the page redraws its steppers from it
                    break;
                }
                case "openProduct": {
                    RunaChat.Product product = new RunaChat.Product(payload);
                    if (!cb.openProduct(this, product) && !product.url.isEmpty()) openInApp(product.url);
                    break;
                }
                case "openExternal":
                    openLink(payload.optString("url", ""));
                    break;
                case "close":
                    finish();
                    break;
                case "backHandled":
                    if (msg.optString("requestId", "").equals(pendingBack)) {
                        pendingBack = null;
                        if (!payload.optBoolean("handled", false)) finish();
                    }
                    break;
                default:
                    cb.onMessage(type, payload);
                    break;
            }
        } catch (Throwable t) {
            Log.e(TAG, "handling '" + type + "' failed", t);
        }
    }

    private void openLink(String u) {
        if (u == null || u.isEmpty()) return;
        if (session != null && session.callbacks.openLink(this, u)) return;
        openInApp(u);
    }

    /** The fallback when the app does not handle a link itself: an in-app browser sheet over the chat
     *  (Chrome Custom Tabs, with its own close button), so the shopper never leaves the app. Anything
     *  that is not http(s) — mailto:, tel:, another app's scheme — goes to the system. */
    private void openInApp(String u) {
        Uri uri = Uri.parse(u);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (scheme.equals("http") || scheme.equals("https")) {
            try {
                new CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "custom tab failed, opening the browser", t);
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no app can open " + u);
        }
    }

    // ── App → page ───────────────────────────────────────────────────────────────────────────

    void sendCart() {
        if (!pageReady || session == null) return;
        JSONArray items = new JSONArray();
        try {
            List<RunaChat.CartItem> cart = session.callbacks.getCart();
            if (cart != null) {
                for (RunaChat.CartItem it : cart) {
                    if (it == null || it.pid == null) continue;
                    items.put(new JSONObject().put("pid", it.pid).put("sid", it.sid == null ? "" : it.sid).put("qty", it.quantity));
                }
            }
            post("cart", new JSONObject().put("items", items), null);
        } catch (Throwable t) {
            Log.e(TAG, "getCart failed", t);
        }
    }

    void sendAddress(String zip, String address, String city, String state) {
        if (!pageReady) return;   // the next open builds the URL from the updated options
        try {
            post("addressChanged", new JSONObject().put("zip", zip).put("address", address).put("city", city).put("state", state), null);
        } catch (JSONException ignored) { }
    }

    private void post(String type, JSONObject payload, String requestId) {
        if (webView == null) return;
        try {
            JSONObject msg = new JSONObject().put("type", type).put("payload", payload).put("v", BRIDGE_VERSION);
            if (requestId != null) msg.put("requestId", requestId);
            if (session.options.debug) Log.d(TAG, "app→page " + type + " " + payload);
            webView.evaluateJavascript("window.RunaBridge&&window.RunaBridge.receive(" + JSONObject.quote(msg.toString()) + ");", null);
        } catch (JSONException ignored) { }
    }

    // ── Back: the page closes its own menus first, then the screen closes ───────────────────

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback cb = this::onBackRequested;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        onBackRequested();
    }

    private void onBackRequested() {
        if (!pageReady || webView == null) { finish(); return; }
        final String id = "b" + (++backSeq);
        pendingBack = id;
        post("back", new JSONObject(), id);
        main.postDelayed(() -> {           // the page did not answer: close
            if (id.equals(pendingBack)) { pendingBack = null; finish(); }
        }, BACK_TIMEOUT_MS);
    }

    // ── URL ──────────────────────────────────────────────────────────────────────────────────

    private String buildUrl(RunaChat.Options o) {
        Uri.Builder b = Uri.parse(o.pageUrl()).buildUpon();
        put(b, "zip", o.zip);
        put(b, "userId", o.userId);
        put(b, "platform", "android");
        put(b, "appVersion", appVersion());
        put(b, "address", o.address);
        put(b, "city", o.city);
        put(b, "state", o.state);
        put(b, "storeId", o.storeId);
        put(b, "q", o.question);
        if (o.debug) put(b, "debug", "1");
        return b.build().toString();
    }

    private static void put(Uri.Builder b, String key, String value) {
        if (value != null && !value.trim().isEmpty()) b.appendQueryParameter(key, value.trim());
    }

    private String appVersion() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return p.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    private static String originOf(String u) {
        Uri x = Uri.parse(u);
        return x.getScheme() + "://" + x.getAuthority() + "/";
    }
}
