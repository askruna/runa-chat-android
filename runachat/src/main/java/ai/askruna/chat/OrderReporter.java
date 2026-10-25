package ai.askruna.chat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@link RunaChat#orderPlaced}: loads Runa's order page in a WebView that is never shown, hands it the order once the
 * page says `ready`, and lets go when the page answers `done` (or after {@link #TIMEOUT_MS}). The page reads what
 * Ask Quicklly showed, opened and added from the storage the chat and the search view share (same origin) and
 * reports the order with Ask Quicklly's sales in it — the same rule and the same event as on the website.
 */
final class OrderReporter {

    private static final long TIMEOUT_MS = 20000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final RunaChat.Options options;
    private final RunaChat.Order order;
    private final RunaChat.OrderListener listener;
    private WebView webView;
    private String origin;
    private boolean finished;

    OrderReporter(Context context, RunaChat.Options options, RunaChat.Order order, RunaChat.OrderListener listener) {
        this.context = context;
        this.options = options;
        this.order = order;
        this.listener = listener;
    }

    void start() {
        main.post(this::load);
        main.postDelayed(() -> finish(false, "timeout"), TIMEOUT_MS);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void load() {
        try {
            webView = new WebView(context);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setAllowFileAccess(false);
            webView.getSettings().setAllowContentAccess(false);
            webView.addJavascriptInterface(new Bridge(), "RunaAndroid");
            webView.setWebViewClient(new WebViewClient());
            Uri.Builder b = Uri.parse(options.pageFor("order-app.html")).buildUpon();
            RunaChat.putShopper(b, options, context);
            String url = b.build().toString();
            origin = RunaChat.originOf(url);
            webView.loadUrl(url);
        } catch (Throwable t) {
            Log.e(RunaChat.TAG, "orderPlaced: could not load the order page", t);
            finish(false, "no webview");
        }
    }

    /** Exposed to the page as window.RunaAndroid. Called on a WebView thread. */
    final class Bridge {
        @JavascriptInterface
        public void postMessage(String json) {
            main.post(() -> handle(json));
        }
    }

    private void handle(String json) {
        if (webView == null || finished) return;
        String at = webView.getUrl();
        if (at == null || origin == null || !at.startsWith(origin)) return;
        JSONObject msg;
        try { msg = new JSONObject(json); } catch (JSONException e) { return; }
        String type = msg.optString("type", "");
        JSONObject payload = msg.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        if (options.debug) Log.d(RunaChat.TAG, "order page→app " + type + " " + payload);
        if ("ready".equals(type)) {
            try {
                JSONArray items = new JSONArray();
                for (RunaChat.OrderLine l : order.lines) {
                    if (l == null || l.pid == null) continue;
                    items.put(new JSONObject().put("pid", l.pid).put("sid", l.sid == null ? "" : l.sid).put("price", l.price).put("qty", l.quantity));
                }
                JSONObject p = new JSONObject().put("orderNumber", order.orderNumber == null ? "" : order.orderNumber)
                    .put("total", order.total).put("currency", order.currency).put("items", items);
                JSONObject m = new JSONObject().put("type", "orderPlaced").put("payload", p).put("requestId", "order").put("v", RunaChatActivity.BRIDGE_VERSION);
                webView.evaluateJavascript("window.RunaBridge&&window.RunaBridge.receive(" + JSONObject.quote(m.toString()) + ");", null);
            } catch (JSONException e) {
                finish(false, "bad order");
            }
        } else if ("done".equals(type)) {
            finish(payload.optBoolean("ok", false), payload.isNull("reason") ? null : payload.optString("reason", null));
        }
    }

    private void finish(boolean ok, String reason) {
        if (finished) return;
        finished = true;
        if (options.debug) Log.d(RunaChat.TAG, "orderPlaced " + (ok ? "reported" : "not reported: " + reason));
        // a moment for the page's own request to leave before the WebView goes (it is sent with keepalive)
        main.postDelayed(() -> {
            if (webView != null) { webView.removeJavascriptInterface("RunaAndroid"); webView.destroy(); webView = null; }
        }, 1500);
        if (listener != null) {
            try { listener.onOrderReported(ok, reason); } catch (Throwable t) { Log.w(RunaChat.TAG, "OrderListener failed", t); }
        }
    }
}
