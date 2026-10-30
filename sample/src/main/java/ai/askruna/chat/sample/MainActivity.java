package ai.askruna.chat.sample;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

import ai.askruna.chat.RunaChat;

/**
 * A stand-in for the Quicklly app: a cart, a product screen, a search screen with Ask Quicklly in search
 * (SearchActivity), an "Ask Quicklly" button and a checkout. Everything Quicklly writes is in options(),
 * callbacks(), openChat() and placeOrder().
 */
public class MainActivity extends Activity {

    static final String TAG = "RunaSample";

    /** Runa's own tests: the folder the pages are served from (e.g. http://quicklly.localhost:5521/). Null = live. */
    static String pageBase;

    private TextView cartLine;

    // Test hooks: adb shell am broadcast -a ai.askruna.chat.sample.BUMP --ei delta 1
    private final BroadcastReceiver bump = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            SampleCart.bumpFirst(i.getIntExtra("delta", 1));
            Log.i(TAG, "app-side cart change → " + SampleCart.count() + " items");
            RunaChat.notifyCartChanged();      // tell an open chat
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RunaChat.warmUp(this);
        if (getIntent().hasExtra("page_base")) pageBase = getIntent().getStringExtra("page_base");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Quicklly (sample app)");
        title.setTextSize(22);
        box.addView(title);
        cartLine = new TextView(this);
        cartLine.setTextSize(16);
        box.addView(cartLine);
        Button ask = new Button(this);
        ask.setText("Ask Quicklly");
        ask.setOnClickListener(v -> openChat(null));
        box.addView(ask);
        Button deep = new Button(this);
        deep.setText("Ask about paneer (deep link)");
        deep.setOnClickListener(v -> openChat("What can I cook tonight with paneer?"));
        box.addView(deep);
        Button search = new Button(this);
        search.setText("Search");
        search.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        box.addView(search);
        Button product = new Button(this);
        product.setText("Product: okra");
        product.setOnClickListener(v -> startActivity(new Intent(this, ProductActivity.class)
            .putExtra("pid", "1013592").putExtra("sid", "113189").putExtra("title", "Okra (bhindi) 1 Lbs").putExtra("price", 3.39)));
        box.addView(product);
        Button order = new Button(this);
        order.setText("Place order");
        order.setOnClickListener(v -> placeOrder());
        box.addView(order);
        setContentView(box);

        IntentFilter f = new IntentFilter("ai.askruna.chat.sample.BUMP");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bump, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(bump, f);

        if (getIntent().getBooleanExtra("open_chat", false)) openChat(null);
        String q = getIntent().getStringExtra("search");
        if (q != null) startActivity(new Intent(this, SearchActivity.class).putExtra("query", q));
        String pp = getIntent().getStringExtra("product");     // tests: "pid|sid|title" opens that product screen
        if (pp != null) {
            String[] f3 = pp.split("\\|", 3);
            startActivity(new Intent(this, ProductActivity.class).putExtra("pid", f3[0]).putExtra("sid", f3.length > 1 ? f3[1] : "")
                .putExtra("title", f3.length > 2 ? f3[2] : "").putExtra("price", 0.0));
        }
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override protected void onDestroy() { unregisterReceiver(bump); super.onDestroy(); }

    private void refresh() {
        StringBuilder s = new StringBuilder("Cart: " + SampleCart.count() + " item(s)");
        for (SampleCart.Line l : SampleCart.lines()) s.append("\n  ").append(l.qty).append(" × ").append(l.title).append(" (pid ").append(l.pid).append(")");
        cartLine.setText(s.toString());
    }

    // ─── This is the whole integration ──────────────────────────────────────────────────────

    /** Who and where the shopper is — the same for the chat, the search and product views and the order report. */
    static RunaChat.Options options() {
        RunaChat.Options o = new RunaChat.Options("quicklly")   // the client key from Runa
            .zip("60610")
            .userId("sample-user-1")
            .address("1140 N Wells St, Chicago").city("Chicago").state("IL")
            .debug(true)
            .preview(true);   // a tester build: show what is still in preview for shoppers (the product view)
        if (pageBase != null) o.pageUrl(pageBase + "chat-app.html");   // Runa's tests only
        return o;
    }

    /** Your cart and your product screen — the same for the chat and the search and product views. */
    static RunaChat.Callbacks callbacks() {
        return new RunaChat.Callbacks() {
            @Override public void setQuantity(RunaChat.Product p, int quantity) {
                Log.i(TAG, "setQuantity pid=" + p.pid + " sid=" + p.sid + " qty=" + quantity + " title=" + p.title
                    + " minOrder=" + p.minOrder + " deliveryFee=" + p.deliveryFee + " range=" + p.deliveryRange + " instant=" + p.instantDelivery);
                SampleCart.set(p.pid, p.sid, p.title, p.price, quantity);   // your add-to-cart
            }

            @Override public List<RunaChat.CartItem> getCart() {
                return SampleCart.snapshot();                                   // your cart
            }

            @Override public boolean openProduct(Activity from, RunaChat.Product p) {
                Log.i(TAG, "openProduct pid=" + p.pid + " sid=" + p.sid);
                from.startActivity(new Intent(from, ProductActivity.class)
                    .putExtra("pid", p.pid).putExtra("sid", p.sid).putExtra("title", p.title)
                    .putExtra("price", p.price == null ? 0 : p.price));
                return true;
            }

            @Override public boolean openLink(Activity from, String url) {
                Log.i(TAG, "openLink " + url);
                if (url.endsWith("runa-fallback-test")) return false;   // test only: let the library's in-app browser show
                Toast.makeText(from, "Would open " + url, Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override public void onClose() { Log.i(TAG, "chat closed"); }

            @Override public void onMessage(String type, JSONObject payload) { Log.i(TAG, "message " + type + " " + payload); }
        };
    }

    private void openChat(String question) {
        RunaChat.open(this, options().question(question), callbacks());
    }

    /** After your checkout succeeds: the order number, what was paid for the products, and the lines. */
    private void placeOrder() {
        List<SampleCart.Line> lines = SampleCart.lines();
        double total = 0;
        for (SampleCart.Line l : lines) total += (l.price == null ? 0 : l.price) * l.qty;
        RunaChat.Order order = new RunaChat.Order("SAMPLE-" + (System.currentTimeMillis() / 1000), Math.round(total * 100) / 100.0);
        for (SampleCart.Line l : lines) order.line(l.pid, l.sid, l.price == null ? 0 : l.price, l.qty);
        final int n = lines.size();
        RunaChat.orderPlaced(this, options(), order, (reported, reason) -> {
            Log.i(TAG, "order reported=" + reported + " reason=" + reason + " lines=" + n);
            Toast.makeText(this, reported ? "Order reported" : "Order not reported: " + reason, Toast.LENGTH_SHORT).show();
        });
        Log.i(TAG, "placeOrder " + n + " line(s), total " + total);
        SampleCart.clear();
        refresh();
    }
}
