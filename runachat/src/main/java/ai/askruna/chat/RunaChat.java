package ai.askruna.chat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;

import androidx.browser.customtabs.CustomTabsIntent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Runa Chat — the AI shopping assistant as a screen in your app.
 *
 * <pre>
 * RunaChat.open(activity,
 *     new RunaChat.Options("quicklly")              // your client key from Runa
 *         .zip("60610").userId("12345"),
 *     new RunaChat.Callbacks() {
 *         public void setQuantity(RunaChat.Product product, int quantity) { ... your add-to-cart ... }
 *         public List&lt;RunaChat.CartItem&gt; getCart() { ... what is in your cart ... }
 *     });
 * </pre>
 *
 * The chat itself is hosted and updated by Runa, shown full screen; improvements reach your users without app
 * releases. The app only answers the two calls above (plus the optional ones in {@link Callbacks}).
 * All callbacks run on the main thread.
 *
 * <p>Also in this library: {@link RunaSearchView}, Ask Quicklly's answer above your own search results (same
 * callbacks), and {@link #orderPlaced}, which reports an order so the sales Ask Quicklly brought are counted.
 */
public final class RunaChat {

    private RunaChat() {}

    static final String TAG = "RunaChat";

    /** What the app does for the chat. Only {@link #setQuantity} and {@link #getCart} are required. */
    public interface Callbacks {
        /** Set this product's quantity in the app's cart. Absolute: 0 removes the line. */
        void setQuantity(Product product, int quantity);

        /** What is in the app's cart right now (product id, store id, quantity). */
        List<CartItem> getCart();

        /**
         * The shopper tapped a product card. Open your product screen and return true.
         * Return false to open the product's web page in the browser instead.
         */
        default boolean openProduct(Activity chat, Product product) { return false; }

        /** An outside link (a recipe, a web page). Return false to open it in the browser. */
        default boolean openLink(Activity chat, String url) { return false; }

        /** The chat screen was closed (the ✕, or back). */
        default void onClose() {}

        /** Any other message from the chat page, for example analytics events ("track"). */
        default void onMessage(String type, JSONObject payload) {}
    }

    /** Who the client is and who / where the shopper is. Only the client key and zip are required. */
    public static final class Options {
        final String client;
        String pageUrl;
        String zip, userId, address, city, state, storeId, question;
        /** Where a chat started, for the client's analytics: "search_answers" when opened from {@link RunaSearchView}. */
        String entry;
        boolean debug;

        /** @param client your client key from Runa, e.g. "quicklly" */
        public Options(String client) { this.client = client == null ? "" : client.trim().toLowerCase(java.util.Locale.ROOT); }

        /** The chat page for this client. Runa hosts it; the key is all the app needs to know. */
        String pageUrl() {
            return pageFor("chat-app.html");
        }
        /** One of this client's pages: chat-app.html, search-app.html (RunaSearchView), order-app.html (orderPlaced). */
        String pageFor(String page) {
            if (pageUrl == null) return "https://" + client + ".askruna.ai/app/" + page;
            int slash = pageUrl.lastIndexOf('/');
            return slash < 0 ? pageUrl : pageUrl.substring(0, slash + 1) + page;    // a test folder holds all three pages
        }
        /** Runa's own use: point at a test page (the chat page; the other pages are taken from the same folder). */
        public Options pageUrl(String url) { this.pageUrl = url; return this; }

        Options copy() {
            Options o = new Options(client);
            o.pageUrl = pageUrl; o.zip = zip; o.userId = userId; o.address = address; o.city = city; o.state = state;
            o.storeId = storeId; o.question = question; o.entry = entry; o.debug = debug;
            return o;
        }

        /** The delivery ZIP. Required: the chat shows the stores that deliver there. */
        public Options zip(String zip) { this.zip = zip; return this; }
        /** The customer id, or a stable id for guests. Keeps the conversation and analytics per shopper. */
        public Options userId(String userId) { this.userId = userId; return this; }
        /** The "Shopping in" label, shown in the chat's store picker. */
        public Options address(String address) { this.address = address; return this; }
        public Options city(String city) { this.city = city; return this; }
        public Options state(String state) { this.state = state; return this; }
        /** Open scoped to one store (store slug, e.g. "quicklly_desi-india-bazaar"). */
        public Options storeId(String storeId) { this.storeId = storeId; return this; }
        /** A question the chat sends right away ("What can I cook with paneer?"). */
        public Options question(String question) { this.question = question; return this; }
        /** Log every message between the app and the page (tag "RunaChat"). */
        public Options debug(boolean debug) { this.debug = debug; return this; }
    }

    /** A product the chat wants in the cart. Ids are Quicklly's numeric ids, as strings. */
    public static final class Product {
        public final String pid;
        public final String sid;
        public final String title;
        public final Double price;
        public final String image;
        public final String storeName;
        public final String storeSlug;
        public final String url;
        public final boolean fastDelivery;
        /** The store's terms for the shopper's ZIP, from Quicklly's own store list. null = unknown. */
        public final Double minOrder;
        public final Double deliveryFee;
        /** Their delivery label for the store, e.g. "Delivery In 3 hours or less" or "5:30 PM - 8:30 PM". */
        public final String deliveryRange;
        public final boolean instantDelivery;
        public final String storeImage;
        /** The whole message payload, for anything not listed above. */
        public final JSONObject raw;

        Product(JSONObject p) {
            raw = p;
            pid = p.optString("pid", "");
            sid = p.optString("sid", "");
            title = p.optString("title", "");
            price = p.isNull("price") || !p.has("price") ? null : p.optDouble("price");
            image = p.optString("image", "");
            storeName = p.optString("storeName", "");
            storeSlug = p.optString("storeSlug", "");
            url = p.optString("url", "");
            fastDelivery = "1".equals(p.optString("fastdelivery", ""));
            minOrder = p.isNull("minOrder") || !p.has("minOrder") ? null : p.optDouble("minOrder");
            deliveryFee = p.isNull("deliveryFee") || !p.has("deliveryFee") ? null : p.optDouble("deliveryFee");
            deliveryRange = p.optString("deliveryRange", "");
            instantDelivery = p.optBoolean("instantDelivery", false);
            storeImage = p.optString("storeImage", "");
        }

        @Override public String toString() { return "Product{pid=" + pid + ", sid=" + sid + ", title=" + title + "}"; }
    }

    /** One line of the app's cart. */
    public static final class CartItem {
        public final String pid;
        public final String sid;
        public final int quantity;

        public CartItem(String pid, String sid, int quantity) {
            this.pid = pid;
            this.sid = sid;
            this.quantity = quantity;
        }
    }

    static final class Session {
        final Options options;
        final Callbacks callbacks;
        Session(Options options, Callbacks callbacks) { this.options = options; this.callbacks = callbacks; }
    }

    /** An order the shopper just placed in your app, for {@link #orderPlaced}. */
    public static final class Order {
        final String orderNumber;
        final double total;
        String currency = "USD";
        final List<OrderLine> lines = new ArrayList<>();

        /**
         * @param orderNumber your order number, e.g. "QD237954" (an order is reported once per number)
         * @param total       what the shopper pays for the products, before delivery fee and tip
         */
        public Order(String orderNumber, double total) { this.orderNumber = orderNumber; this.total = total; }

        /** One product line: your product id, store id, unit price and quantity. */
        public Order line(String pid, String sid, double price, int quantity) { lines.add(new OrderLine(pid, sid, price, quantity)); return this; }
        public Order currency(String currency) { if (currency != null && !currency.trim().isEmpty()) this.currency = currency.trim(); return this; }
    }

    /** One line of an {@link Order}. */
    public static final class OrderLine {
        public final String pid, sid;
        public final double price;
        public final int quantity;
        public OrderLine(String pid, String sid, double price, int quantity) { this.pid = pid; this.sid = sid; this.price = price; this.quantity = quantity; }
    }

    /** Optional: told when {@link #orderPlaced} has reported the order (or why it did not). Main thread. */
    public interface OrderListener {
        void onOrderReported(boolean reported, String reason);
    }

    static Session session;

    /** Open the chat screen. */
    public static void open(Activity from, Options options, Callbacks callbacks) {
        if (from == null || options == null || callbacks == null) throw new IllegalArgumentException("activity, options and callbacks are required");
        checkOptions(options);
        session = new Session(options, callbacks);
        from.startActivity(new Intent(from, RunaChatActivity.class));
    }

    static void checkOptions(Options options) {
        if (!options.client.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Options needs your client key from Runa, e.g. \"quicklly\"");
        if (!allowedPage(options.pageUrl())) throw new IllegalArgumentException("the chat page must be https");
        if (options.zip == null || options.zip.trim().isEmpty()) throw new IllegalArgumentException("Options.zip(…) is required");
    }

    /** Runa's pages are https; plain http only on this machine (localhost, *.localhost — Runa's own tests). */
    static boolean allowedPage(String url) {
        if (url == null) return false;
        if (url.startsWith("https://")) return true;
        String host = Uri.parse(url).getHost();
        return url.startsWith("http://") && host != null && (host.equals("localhost") || host.endsWith(".localhost") || host.equals("127.0.0.1"));
    }

    /** Call after the cart changes outside the chat (cart screen, product screen) while the chat may be open. */
    public static void notifyCartChanged() {
        RunaChatActivity chat = RunaChatActivity.current();
        if (chat != null) chat.runOnUiThread(chat::sendCart);
        for (RunaSearchView v : searchViews()) v.post(v::sendCart);
    }

    /** The shopper changed the delivery address. An open chat re-scopes to it and starts a new conversation. */
    public static void updateAddress(String zip, String address, String city, String state) {
        Session s = session;
        if (s != null) {
            s.options.zip(zip).address(address).city(city).state(state);
        }
        RunaChatActivity chat = RunaChatActivity.current();
        if (chat != null) chat.runOnUiThread(() -> chat.sendAddress(zip, address, city, state));
        for (RunaSearchView v : searchViews()) v.post(() -> v.sendAddress(zip, address, city, state));
    }

    /**
     * Report an order right after checkout, so the products Ask Quicklly brought to it are counted as its sales
     * (what it added or opened in the 7 days before, the same rule as on your website). Call it once per order,
     * on the main thread, with the same client key and userId as the chat; it returns at once and works in the
     * background (a few seconds, no UI). Optional {@code listener} hears the outcome.
     */
    public static void orderPlaced(Context context, Options options, Order order, OrderListener listener) {
        if (context == null || options == null || order == null) throw new IllegalArgumentException("context, options and order are required");
        if (!options.client.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Options needs your client key from Runa, e.g. \"quicklly\"");
        new OrderReporter(context.getApplicationContext(), options.copy(), order, listener).start();
    }

    public static void orderPlaced(Context context, Options options, Order order) { orderPlaced(context, options, order, null); }

    // ── shared by the screen, the search view and the order reporter ─────────────────────────

    private static final Set<RunaSearchView> SEARCH_VIEWS = Collections.newSetFromMap(new WeakHashMap<>());
    static synchronized void addSearchView(RunaSearchView v) { SEARCH_VIEWS.add(v); }
    static synchronized void removeSearchView(RunaSearchView v) { SEARCH_VIEWS.remove(v); }
    static synchronized List<RunaSearchView> searchViews() { return new ArrayList<>(SEARCH_VIEWS); }

    /** Every page gets who and where the shopper is and which app build asks. */
    static void putShopper(Uri.Builder b, Options o, Context c) {
        put(b, "zip", o.zip);
        put(b, "userId", o.userId);
        put(b, "platform", "android");
        put(b, "appVersion", appVersion(c));
        put(b, "address", o.address);
        put(b, "city", o.city);
        put(b, "state", o.state);
        put(b, "storeId", o.storeId);
        if (o.debug) put(b, "debug", "1");
    }

    static void put(Uri.Builder b, String key, String value) {
        if (value != null && !value.trim().isEmpty()) b.appendQueryParameter(key, value.trim());
    }

    static String appVersion(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    static String originOf(String u) {
        Uri x = Uri.parse(u);
        return x.getScheme() + "://" + x.getAuthority() + "/";
    }

    /** The fallback when the app does not handle a link itself: an in-app browser sheet (Chrome Custom Tabs, with
     *  its own close button), so the shopper never leaves the app. Anything that is not http(s) — mailto:, tel:,
     *  another app's scheme — goes to the system. */
    static void openInApp(Activity from, String u) {
        if (from == null || u == null || u.isEmpty()) return;
        Uri uri = Uri.parse(u);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("http") || scheme.equals("https")) {
            try {
                new CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(from, uri);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "custom tab failed, opening the browser", t);
            }
        }
        try {
            from.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no app can open " + u);
        }
    }

    /** Optional: call once early (e.g. when the grocery tab opens) so the first open of the chat is faster. */
    public static void warmUp(Context context) {
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try { WebSettings.getDefaultUserAgent(app); } catch (Throwable ignored) { }
        });
    }
}
