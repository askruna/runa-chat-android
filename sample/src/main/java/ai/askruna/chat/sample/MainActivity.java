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
 * A stand-in for the Quicklly app: a cart, a product screen and an "Ask Quicklly" button.
 * Everything Quicklly writes is in openChat(): the options and the callbacks.
 */
public class MainActivity extends Activity {

    static final String TAG = "RunaSample";

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
        setContentView(box);

        IntentFilter f = new IntentFilter("ai.askruna.chat.sample.BUMP");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bump, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(bump, f);

        if (getIntent().getBooleanExtra("open_chat", false)) openChat(null);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override protected void onDestroy() { unregisterReceiver(bump); super.onDestroy(); }

    private void refresh() {
        StringBuilder s = new StringBuilder("Cart: " + SampleCart.count() + " item(s)");
        for (SampleCart.Line l : SampleCart.lines()) s.append("\n  ").append(l.qty).append(" × ").append(l.title).append(" (pid ").append(l.pid).append(")");
        cartLine.setText(s.toString());
    }

    // ─── This is the whole integration ──────────────────────────────────────────────────────
    private void openChat(String question) {
        RunaChat.open(this,
            new RunaChat.Options("quicklly")            // the client key from Runa
                .zip("60610")
                .userId("sample-user-1")
                .address("1140 N Wells St, Chicago").city("Chicago").state("IL")
                .question(question)
                .debug(true),
            new RunaChat.Callbacks() {
                @Override public void setQuantity(RunaChat.Product p, int quantity) {
                    Log.i(TAG, "setQuantity pid=" + p.pid + " sid=" + p.sid + " qty=" + quantity + " title=" + p.title
                        + " minOrder=" + p.minOrder + " deliveryFee=" + p.deliveryFee + " range=" + p.deliveryRange + " instant=" + p.instantDelivery);
                    SampleCart.set(p.pid, p.sid, p.title, p.price, quantity);   // your add-to-cart
                }

                @Override public List<RunaChat.CartItem> getCart() {
                    return SampleCart.snapshot();                                   // your cart
                }

                @Override public boolean openProduct(Activity chat, RunaChat.Product p) {
                    Log.i(TAG, "openProduct pid=" + p.pid + " sid=" + p.sid);
                    chat.startActivity(new Intent(chat, ProductActivity.class)
                        .putExtra("pid", p.pid).putExtra("sid", p.sid).putExtra("title", p.title)
                        .putExtra("price", p.price == null ? 0 : p.price));
                    return true;
                }

                @Override public boolean openLink(Activity chat, String url) {
                    Log.i(TAG, "openLink " + url);
                    if (url.endsWith("runa-fallback-test")) return false;   // test only: let the library's in-app browser show
                    Toast.makeText(chat, "Would open " + url, Toast.LENGTH_SHORT).show();
                    return true;
                }

                @Override public void onClose() { Log.i(TAG, "chat closed"); }

                @Override public void onMessage(String type, JSONObject payload) { Log.i(TAG, "message " + type + " " + payload); }
            });
    }
}
