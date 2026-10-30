package ai.askruna.chat.sample;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ai.askruna.chat.RunaChat;
import ai.askruna.chat.RunaProductView;

/**
 * Stands in for the app's product screen (what openProduct lands on): the product's details, then Ask Quicklly's
 * bundle and line under them. The integration is the three RunaProductView lines marked below.
 */
public class ProductActivity extends Activity {

    private RunaProductView bundle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String pid = getIntent().getStringExtra("pid");
        final String sid = getIntent().getStringExtra("sid");
        final String title = getIntent().getStringExtra("title");
        final double price = getIntent().getDoubleExtra("price", 0);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad / 2);
        TextView t = new TextView(this);
        t.setTextSize(20);
        t.setText(title + "\npid " + pid + " · store " + sid);
        box.addView(t);
        // your product photo, price and buttons — tall, as in the Quicklly app, so the bundle starts below the fold
        TextView photo = new TextView(this);
        photo.setText("(product photo)");
        photo.setBackgroundColor(0xFFF2F2F2);
        photo.setTextColor(0xFF888888);
        photo.setGravity(android.view.Gravity.CENTER);
        box.addView(photo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (360 * getResources().getDisplayMetrics().density)));
        Button plus = new Button(this);
        plus.setText("+1 in cart");
        plus.setOnClickListener(v -> {
            SampleCart.set(pid, sid, title, price, SampleCart.qty(pid) + 1);
            Log.i(MainActivity.TAG, "product screen +1 → qty " + SampleCart.qty(pid));
            RunaChat.notifyCartChanged();
        });
        box.addView(plus);
        page.addView(box);

        // ─── Ask Quicklly on the product screen: under your product details ─────────────────
        bundle = new RunaProductView(this);
        bundle.setup(this, MainActivity.options(), MainActivity.callbacks());
        bundle.setOnHeightChangeListener((view, px) -> Log.i(MainActivity.TAG, "product view height " + px));
        page.addView(bundle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bundle.show(pid, sid, title);                                                   // ← the product on screen

        TextView more = new TextView(this);
        more.setText("Product details · Shipping & delivery …");
        more.setPadding(pad, pad, pad, pad * 6);
        page.addView(more);
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override protected void onDestroy() { bundle.destroy(); super.onDestroy(); }
}
