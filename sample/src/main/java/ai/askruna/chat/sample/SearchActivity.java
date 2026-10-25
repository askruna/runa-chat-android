package ai.askruna.chat.sample;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.askruna.chat.RunaChat;
import ai.askruna.chat.RunaSearchView;

/**
 * Stands in for the app's search screen: the search field, Ask Quicklly in search right under it, then the app's
 * own "N products found" and results. The integration is the three RunaSearchView lines marked below.
 */
public class SearchActivity extends Activity {

    private EditText field;
    private RunaSearchView answer;
    private TextView found;
    private LinearLayout results;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(pad, pad, pad, pad / 2);
        field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Search");
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setContentDescription("search field");
        field.setOnEditorActionListener((v, id, e) -> { if (id == EditorInfo.IME_ACTION_SEARCH || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { runSearch(); return true; } return false; });
        bar.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button go = new Button(this);
        go.setText("Go");
        go.setOnClickListener(v -> runSearch());
        bar.addView(go);
        page.addView(bar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        // ─── Ask Quicklly in search: under the search field, above your results ─────────────
        answer = new RunaSearchView(this);
        answer.setup(this, MainActivity.options(), MainActivity.callbacks());
        answer.setOnHeightChangeListener((view, px) -> Log.i(MainActivity.TAG, "search view height " + px));
        list.addView(answer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        found = new TextView(this);
        found.setTextSize(16);
        found.setPadding(pad, pad, pad, pad / 2);
        list.addView(found);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(pad, 0, pad, pad);
        list.addView(results);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(page);

        String q = getIntent().getStringExtra("query");
        if (q != null) { field.setText(q); runSearch(); }
    }

    @Override protected void onDestroy() { answer.destroy(); super.onDestroy(); }

    private void runSearch() {
        String q = field.getText().toString().trim();
        List<String> titles = appResults(q);
        found.setText(titles.size() + " products found for item '" + q + "'");
        results.removeAllViews();
        for (String t : titles) { TextView row = new TextView(this); row.setText("• " + t); row.setTextSize(15); row.setPadding(0, 10, 0, 10); results.addView(row); }
        Log.i(MainActivity.TAG, "app search '" + q + "' → " + titles.size() + " results");
        answer.search(q, titles.size(), titles.subList(0, Math.min(10, titles.size())));   // ← on every search
    }

    /** Your own search results (here: canned, like the Quicklly app's own results for these searches). */
    private static List<String> appResults(String q) {
        String s = q.toLowerCase();
        if (s.contains("milk")) return Arrays.asList("Amul Water Buffalo And Cow Milk Butter 3.5OZ", "Amul Water Buffalo Cow Milk Butter 500GM", "Milk-Organic Valley 2% Reduced Fat 1GALLON", "Amul Butter Water Buffalo And Cow Milk 17.64OZ");
        if (s.contains("rice")) return Arrays.asList("Royal Basmati Rice 10 lb", "Deep Basmati Rice 10 lb", "Tilda Pure Basmati 5 lb");
        return new ArrayList<>();
    }
}
