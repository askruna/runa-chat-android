package ai.askruna.chat.sample;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import ai.askruna.chat.RunaChat;

/** Stands in for the app's product screen: what openProduct lands on. */
public class ProductActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String pid = getIntent().getStringExtra("pid");
        final String sid = getIntent().getStringExtra("sid");
        final String title = getIntent().getStringExtra("title");
        final double price = getIntent().getDoubleExtra("price", 0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setTextSize(20);
        t.setText(title + "\npid " + pid + " · store " + sid);
        box.addView(t);
        Button plus = new Button(this);
        plus.setText("+1 in cart");
        plus.setOnClickListener(v -> {
            SampleCart.set(pid, sid, title, price, SampleCart.qty(pid) + 1);
            Log.i(MainActivity.TAG, "product screen +1 → qty " + SampleCart.qty(pid));
            RunaChat.notifyCartChanged();
        });
        box.addView(plus);
        setContentView(box);
    }
}
