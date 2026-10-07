package ai.askruna.chat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;

import org.json.JSONObject;

import java.util.List;

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
 * The chat itself is a web page hosted by Runa, shown full screen; it is updated without app
 * releases. The app only answers the two calls above (plus the optional ones in {@link Callbacks}).
 * All callbacks run on the main thread.
 */
public final class RunaChat {

    private RunaChat() {}

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
        boolean debug;

        /** @param client your client key from Runa, e.g. "quicklly" */
        public Options(String client) { this.client = client == null ? "" : client.trim().toLowerCase(java.util.Locale.ROOT); }

        /** The chat page for this client. Runa hosts it; the key is all the app needs to know. */
        String pageUrl() {
            return pageUrl != null ? pageUrl : "https://" + client + ".askruna.ai/app/chat-app.html";
        }
        /** Runa's own use: point at a test page instead of the client's live page. */
        public Options pageUrl(String url) { this.pageUrl = url; return this; }

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

    static Session session;

    /** Open the chat screen. */
    public static void open(Activity from, Options options, Callbacks callbacks) {
        if (from == null || options == null || callbacks == null) throw new IllegalArgumentException("activity, options and callbacks are required");
        if (!options.client.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Options needs your client key from Runa, e.g. \"quicklly\"");
        if (!options.pageUrl().startsWith("https://")) throw new IllegalArgumentException("the chat page must be https");
        if (options.zip == null || options.zip.trim().isEmpty()) throw new IllegalArgumentException("Options.zip(…) is required");
        session = new Session(options, callbacks);
        from.startActivity(new Intent(from, RunaChatActivity.class));
    }

    /** Call after the cart changes outside the chat (cart screen, product screen) while the chat may be open. */
    public static void notifyCartChanged() {
        RunaChatActivity chat = RunaChatActivity.current();
        if (chat != null) chat.runOnUiThread(chat::sendCart);
    }

    /** The shopper changed the delivery address. An open chat re-scopes to it and starts a new conversation. */
    public static void updateAddress(String zip, String address, String city, String state) {
        Session s = session;
        if (s != null) {
            s.options.zip(zip).address(address).city(city).state(state);
        }
        RunaChatActivity chat = RunaChatActivity.current();
        if (chat != null) chat.runOnUiThread(() -> chat.sendAddress(zip, address, city, state));
    }

    /** Optional: call once early (e.g. when the grocery tab opens) so the first open of the chat is faster. */
    public static void warmUp(Context context) {
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try { WebSettings.getDefaultUserAgent(app); } catch (Throwable ignored) { }
        });
    }
}
