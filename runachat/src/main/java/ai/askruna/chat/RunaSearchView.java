package ai.askruna.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Ask Quicklly in search: the assistant's answer above your own search results — the products the shopper meant
 * (e.g. milk when the search for "cow milk" lists butter first), a line about them and a few follow-up chips.
 *
 * <pre>
 * RunaSearchView answer = new RunaSearchView(this);            // or &lt;ai.askruna.chat.RunaSearchView&gt; in XML
 * resultsHeader.addView(answer);                              // above your results, full width
 * answer.setup(this, options, callbacks);                     // the same Options and Callbacks as the chat
 * answer.search("cow milk", 76, firstTitles);                 // on every search the shopper makes
 * </pre>
 *
 * The view is as tall as its answer and changes height as the answer arrives (a one-line working state, then the
 * products); with nothing to show it collapses to nothing. ADD and the quantity steppers go through your
 * {@link RunaChat.Callbacks#setQuantity} / {@link RunaChat.Callbacks#getCart}, a product tap through
 * {@link RunaChat.Callbacks#openProduct}, and a chip opens the chat screen with that question. Main thread only.
 */
public class RunaSearchView extends FrameLayout {

    /** Optional: the view changed height (px; 0 = collapsed). For layouts that need to know. */
    public interface OnHeightChangeListener {
        void onHeightChanged(RunaSearchView view, int heightPx);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Activity host;
    private RunaChat.Options options;
    private RunaChat.Callbacks callbacks;
    private OnHeightChangeListener heightListener;
    private WebView webView;
    private String origin;
    private boolean pageReady;
    private String loadedQuery;
    private JSONObject pending;          // a search made before the page said ready
    private int heightPx;

    public RunaSearchView(Context context) { super(context); init(); }
    public RunaSearchView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public RunaSearchView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        setVisibility(GONE);              // nothing until the first answer has a height
    }

    /**
     * Connect the view once, before the first {@link #search}: the activity it lives in, the same {@link RunaChat.Options}
     * (client key, ZIP, userId…) and {@link RunaChat.Callbacks} as the chat.
     */
    public void setup(Activity activity, RunaChat.Options options, RunaChat.Callbacks callbacks) {
        if (activity == null || options == null || callbacks == null) throw new IllegalArgumentException("activity, options and callbacks are required");
        RunaChat.checkOptions(options);
        this.host = activity;
        this.options = options.copy();
        this.callbacks = callbacks;
    }

    public void setOnHeightChangeListener(OnHeightChangeListener listener) { this.heightListener = listener; }

    /** The shopper searched. Shows the answer for that query (the view keeps its place; only its content changes). */
    public void search(String query) { search(query, -1, null); }

    /**
     * The shopper searched, with what your own results show: how many products your search found and the first few
     * product names (up to 10, top to bottom). Optional, but they let Ask Quicklly tell when your results already
     * answer the search, and say so instead of repeating them.
     */
    public void search(String query, int resultCount, List<String> firstTitles) {
        if (options == null) throw new IllegalStateException("call setup(activity, options, callbacks) first");
        String q = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (q.isEmpty()) { clear(); return; }
        JSONObject payload = new JSONObject();
        try {
            payload.put("query", q);
            if (resultCount >= 0) payload.put("results", resultCount);
            JSONArray titles = new JSONArray();
            if (firstTitles != null) for (String t : firstTitles) { if (t != null && !t.trim().isEmpty() && titles.length() < 10) titles.put(t.trim()); }
            payload.put("titles", titles);
        } catch (JSONException ignored) { }
        if (webView == null) { load(payload); return; }
        if (pageReady) post("search", payload); else pending = payload;
    }

    /** Hide the answer (e.g. the search field was cleared). The next {@link #search} shows it again. */
    public void clear() {
        if (webView != null && pageReady) post("search", new JSONObject());
        setHeight(0);
    }

    /** Free the WebView. Call from your activity's onDestroy if the view lives longer than its screen. */
    public void destroy() {
        RunaChat.removeSearchView(this);
        if (webView != null) {
            webView.removeJavascriptInterface("RunaAndroid");
            removeView(webView);
            webView.destroy();
            webView = null;
        }
        pageReady = false;
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); RunaChat.addSearchView(this); }
    @Override protected void onDetachedFromWindow() { RunaChat.removeSearchView(this); super.onDetachedFromWindow(); }

    // ── WebView ──────────────────────────────────────────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface", "ClickableViewAccessibility"})
    private void load(JSONObject first) {
        if ((getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);    // debug builds of the app only
        }
        webView = new WebView(getContext());
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // the shopper id and what Ask Quicklly showed live in localStorage
        s.setTextZoom(100);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setBackgroundColor(Color.WHITE);
        webView.addJavascriptInterface(new Bridge(), "RunaAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return routeOutside(request.getUrl().toString()); }
            @Override @SuppressWarnings("deprecation") public boolean shouldOverrideUrlLoading(WebView view, String u) { return routeOutside(u); }
        });
        // The product row scrolls sideways inside the page: a sideways drag stays with it instead of the parent list.
        webView.setOnTouchListener(new OnTouchListener() {
            float x0, y0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { x0 = e.getX(); y0 = e.getY(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    ViewParent p = getParent();
                    if (p != null && Math.abs(e.getX() - x0) > Math.abs(e.getY() - y0)) p.requestDisallowInterceptTouchEvent(true);
                }
                return false;
            }
        });
        addView(webView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String url = buildUrl(first);
        origin = RunaChat.originOf(url);
        loadedQuery = first.optString("query", "");
        webView.loadUrl(url);
    }

    private String buildUrl(JSONObject first) {
        Uri.Builder b = Uri.parse(options.pageFor("search-app.html")).buildUpon();
        RunaChat.putShopper(b, options, getContext());
        RunaChat.put(b, "q", first.optString("query", ""));
        if (first.has("results")) RunaChat.put(b, "results", String.valueOf(first.optInt("results")));
        JSONArray t = first.optJSONArray("titles");
        if (t != null && t.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.length(); i++) { if (i > 0) sb.append('|'); sb.append(t.optString(i).replace('|', ' ')); }
            RunaChat.put(b, "titles", sb.toString());
        }
        return b.build().toString();
    }

    /** The view stays on Runa's page; any other URL leaves through the app. */
    private boolean routeOutside(String u) {
        if (u != null && origin != null && u.startsWith(origin)) return false;
        openLink(u);
        return true;
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
        if (webView == null || callbacks == null) return;
        String at = webView.getUrl();
        if (at == null || origin == null || !at.startsWith(origin)) return;   // only our page talks to the app
        JSONObject msg;
        try { msg = new JSONObject(json); } catch (JSONException e) { return; }
        String type = msg.optString("type", "");
        JSONObject payload = msg.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        if (options.debug) Log.d(RunaChat.TAG, "search page→app " + type + " " + payload);
        try {
            switch (type) {
                case "ready":
                    pageReady = true;
                    sendCart();
                    if (pending != null) { JSONObject p = pending; pending = null; if (!p.optString("query", "").equals(loadedQuery)) post("search", p); }
                    break;
                case "resize":
                    setHeight((int) Math.ceil(payload.optDouble("height", 0) * getResources().getDisplayMetrics().density));
                    break;
                case "setQty": {
                    RunaChat.Product product = new RunaChat.Product(payload);
                    callbacks.setQuantity(product, Math.max(0, payload.optInt("qty", 0)));
                    sendCart();       // the acknowledgement: the page redraws its steppers from it
                    break;
                }
                case "openProduct": {
                    RunaChat.Product product = new RunaChat.Product(payload);
                    if (!callbacks.openProduct(host, product) && !product.url.isEmpty()) RunaChat.openInApp(host, product.url);
                    break;
                }
                case "openChat": {
                    // a chip or "Ask me anything": the chat screen, with the question already sent; its messages and
                    // adds are credited to Ask Quicklly in search
                    RunaChat.Options o = options.copy();
                    String text = payload.optString("text", "").trim();
                    o.question = text.isEmpty() ? null : text;
                    o.entry = payload.optString("entry", "search_answers");
                    RunaChat.open(host, o, callbacks);
                    break;
                }
                case "openExternal":
                    openLink(payload.optString("url", ""));
                    break;
                default:
                    callbacks.onMessage(type, payload);
                    break;
            }
        } catch (Throwable t) {
            Log.e(RunaChat.TAG, "search view: handling '" + type + "' failed", t);
        }
    }

    private void openLink(String u) {
        if (u == null || u.isEmpty() || host == null) return;
        if (callbacks != null && callbacks.openLink(host, u)) return;
        RunaChat.openInApp(host, u);
    }

    private void setHeight(int px) {
        px = Math.max(0, px);
        if (px == heightPx && getVisibility() == (px > 0 ? VISIBLE : GONE)) return;
        heightPx = px;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) { lp.height = px > 0 ? px : ViewGroup.LayoutParams.WRAP_CONTENT; setLayoutParams(lp); }
        else setMinimumHeight(px);
        setVisibility(px > 0 ? VISIBLE : GONE);
        if (heightListener != null) heightListener.onHeightChanged(this, px);
    }

    // ── App → page ───────────────────────────────────────────────────────────────────────────

    void sendCart() {
        if (!pageReady || callbacks == null) return;
        JSONArray items = new JSONArray();
        try {
            List<RunaChat.CartItem> cart = callbacks.getCart();
            if (cart != null) {
                for (RunaChat.CartItem it : cart) {
                    if (it == null || it.pid == null) continue;
                    items.put(new JSONObject().put("pid", it.pid).put("sid", it.sid == null ? "" : it.sid).put("qty", it.quantity));
                }
            }
            post("cart", new JSONObject().put("items", items));
        } catch (Throwable t) {
            Log.e(RunaChat.TAG, "getCart failed", t);
        }
    }

    void sendAddress(String zip, String address, String city, String state) {
        if (options != null) options.zip(zip).address(address).city(city).state(state);
        if (!pageReady) return;
        try {
            post("addressChanged", new JSONObject().put("zip", zip).put("address", address).put("city", city).put("state", state));
        } catch (JSONException ignored) { }
    }

    private void post(String type, JSONObject payload) {
        if (webView == null) return;
        try {
            JSONObject msg = new JSONObject().put("type", type).put("payload", payload).put("v", RunaChatActivity.BRIDGE_VERSION);
            if (options.debug) Log.d(RunaChat.TAG, "search app→page " + type + " " + payload);
            webView.evaluateJavascript("window.RunaBridge&&window.RunaBridge.receive(" + JSONObject.quote(msg.toString()) + ");", null);
        } catch (JSONException ignored) { }
    }
}
