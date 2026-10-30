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
import android.view.ViewTreeObserver;
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
 * Ask Quicklly on your product screen: what goes with the product — "Recipes" (the dishes it is for, every ingredient
 * from the same store, one "Add all") or "Goes great with" — and the Ask Quicklly line under it, as on quicklly.com's
 * product pages.
 *
 * <pre>
 * RunaProductView bundle = new RunaProductView(this);          // or &lt;ai.askruna.chat.RunaProductView&gt; in XML
 * productDetails.addView(bundle);                             // under your product details, full width
 * bundle.setup(this, options, callbacks);                     // the same Options and Callbacks as the chat
 * bundle.show("1013592", "113189", "Okra (bhindi) 1 Lbs");    // the product on screen: its id, its store's id, its name
 * </pre>
 *
 * The view is as tall as its content and appears once it has something to show; a product with nothing to suggest
 * leaves it collapsed. ADD, "Add all" and the steppers go through your {@link RunaChat.Callbacks#setQuantity} /
 * {@link RunaChat.Callbacks#getCart}, a product tap through {@link RunaChat.Callbacks#openProduct}, and the Ask
 * Quicklly line opens the chat screen with its question. Main thread only.
 */
public class RunaProductView extends FrameLayout {

    /** Optional: the view changed height (px; 0 = collapsed). For layouts that need to know. */
    public interface OnHeightChangeListener {
        void onHeightChanged(RunaProductView view, int heightPx);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Activity host;
    private RunaChat.Options options;
    private RunaChat.Callbacks callbacks;
    private OnHeightChangeListener heightListener;
    private WebView webView;
    private String origin;
    private boolean pageReady;
    private String loadedKey;
    private JSONObject pending;          // a product shown before the page said ready
    private int heightPx;
    private String lastViewport = "";

    public RunaProductView(Context context) { super(context); init(); }
    public RunaProductView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public RunaProductView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        setVisibility(GONE);              // nothing until there is something to show
    }

    /**
     * Connect the view once, before the first {@link #show}: the activity it lives in, the same {@link RunaChat.Options}
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

    /**
     * The product on your screen: its id and its store's id as on quicklly.com (/grocery-store/&lt;handle&gt;/&lt;id&gt;), and its
     * name. The name is optional but recommended: a food product without a bundle still gets the Ask Quicklly line.
     * Call again when the screen shows another product (the view is reused).
     */
    public void show(String productId, String storeId, String title) {
        if (options == null) throw new IllegalStateException("call setup(activity, options, callbacks) first");
        String pid = productId == null ? "" : productId.trim();
        if (!pid.matches("\\d+")) { clear(); return; }
        JSONObject payload = new JSONObject();
        try {
            payload.put("pid", pid);
            payload.put("sid", storeId == null ? "" : storeId.trim());
            payload.put("title", title == null ? "" : title.trim());
        } catch (JSONException ignored) { }
        if (webView == null) { load(payload); return; }
        if (pageReady) post("product", payload); else pending = payload;
    }

    public void show(String productId, String storeId) { show(productId, storeId, null); }

    /** Hide the view (e.g. the product screen is being reused for something else). The next {@link #show} shows it again. */
    public void clear() {
        if (webView != null && pageReady) post("product", new JSONObject());
        setHeight(0);
    }

    /** Free the WebView. Call from your activity's onDestroy if the view lives longer than its screen. */
    public void destroy() {
        RunaChat.removeProductView(this);
        if (webView != null) {
            webView.removeJavascriptInterface("RunaAndroid");
            removeView(webView);
            webView.destroy();
            webView = null;
        }
        pageReady = false;
    }

    // Which part of the view the shopper sees: the page types the Ask Quicklly line once it is in the middle of the
    // screen (the website's rule — the WebView cannot know where the app scrolled it).
    private final ViewTreeObserver.OnScrollChangedListener onScroll = this::sendViewport;
    private final ViewTreeObserver.OnGlobalLayoutListener onLayout = this::sendViewport;

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        RunaChat.addProductView(this);
        ViewTreeObserver vto = getViewTreeObserver();
        vto.addOnScrollChangedListener(onScroll);
        vto.addOnGlobalLayoutListener(onLayout);
    }

    @Override protected void onDetachedFromWindow() {
        RunaChat.removeProductView(this);
        ViewTreeObserver vto = getViewTreeObserver();
        vto.removeOnScrollChangedListener(onScroll);
        vto.removeOnGlobalLayoutListener(onLayout);
        super.onDetachedFromWindow();
    }

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
        // The product row and the recipe tabs scroll sideways inside the page: a sideways drag stays with them instead
        // of the parent list.
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
        loadedKey = keyOf(first);
        webView.loadUrl(url);
    }

    private String buildUrl(JSONObject first) {
        Uri.Builder b = Uri.parse(options.pageFor("product-app.html")).buildUpon();
        RunaChat.putShopper(b, options, getContext());
        RunaChat.put(b, "pid", first.optString("pid", ""));
        RunaChat.put(b, "sid", first.optString("sid", ""));
        RunaChat.put(b, "title", first.optString("title", ""));
        if (options.preview) RunaChat.put(b, "preview", "1");
        return b.build().toString();
    }

    private static String keyOf(JSONObject p) { return p.optString("pid", "") + "|" + p.optString("sid", ""); }

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
        if (options.debug) Log.d(RunaChat.TAG, "product page→app " + type + " " + payload);
        try {
            switch (type) {
                case "ready":
                    pageReady = true;
                    sendCart();
                    lastViewport = "";
                    sendViewport();
                    if (pending != null) { JSONObject p = pending; pending = null; if (!keyOf(p).equals(loadedKey)) post("product", p); }
                    break;
                case "resize":
                    setHeight((int) Math.ceil(payload.optDouble("height", 0) * getResources().getDisplayMetrics().density));
                    break;
                case "setQty": {
                    RunaChat.Product product = new RunaChat.Product(payload);
                    callbacks.setQuantity(product, Math.max(0, payload.optInt("qty", 0)));
                    sendCart();       // the acknowledgement: the page redraws its steppers and "Add all" from it
                    break;
                }
                case "openProduct": {
                    RunaChat.Product product = new RunaChat.Product(payload);
                    if (!callbacks.openProduct(host, product) && !product.url.isEmpty()) RunaChat.openInApp(host, product.url);
                    break;
                }
                case "openChat": {
                    // the Ask Quicklly line: the chat screen with its question already sent ("Plan a meal with …"),
                    // its messages and adds credited to the product page
                    RunaChat.Options o = options.copy();
                    String text = payload.optString("text", "").trim();
                    o.question = text.isEmpty() ? null : text;
                    o.entry = payload.optString("entry", "product_page");
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
            Log.e(RunaChat.TAG, "product view: handling '" + type + "' failed", t);
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
        main.post(this::sendViewport);
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

    /**
     * The part of the view inside the middle of the screen — clear of its top 20% and bottom 15%, the website's rule —
     * in the page's CSS px, sent when it changes. Empty (top = bottom = 0): off screen or hidden.
     */
    private void sendViewport() {
        if (!pageReady || webView == null) return;
        int top = 0, bottom = 0;
        View root = getRootView();
        if (isShown() && getHeight() > 0 && root != null && root.getHeight() > 0) {
            int[] at = new int[2], rootAt = new int[2];
            getLocationOnScreen(at);
            root.getLocationOnScreen(rootAt);
            int bandTop = rootAt[1] + Math.round(root.getHeight() * 0.20f), bandBottom = rootAt[1] + Math.round(root.getHeight() * 0.85f);
            int vTop = Math.max(at[1], bandTop), vBottom = Math.min(at[1] + getHeight(), bandBottom);
            if (vBottom > vTop) {
                float d = getResources().getDisplayMetrics().density;
                top = Math.round((vTop - at[1]) / d);
                bottom = Math.round((vBottom - at[1]) / d);
            }
        }
        String key = top + "," + bottom;
        if (key.equals(lastViewport)) return;
        lastViewport = key;
        try { post("viewport", new JSONObject().put("top", top).put("bottom", bottom)); } catch (JSONException ignored) { }
    }

    private void post(String type, JSONObject payload) {
        if (webView == null) return;
        try {
            JSONObject msg = new JSONObject().put("type", type).put("payload", payload).put("v", RunaChatActivity.BRIDGE_VERSION);
            if (options.debug && !"viewport".equals(type)) Log.d(RunaChat.TAG, "product app→page " + type + " " + payload);
            webView.evaluateJavascript("window.RunaBridge&&window.RunaBridge.receive(" + JSONObject.quote(msg.toString()) + ");", null);
        } catch (JSONException ignored) { }
    }
}
