package ai.askruna.chat.sample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.askruna.chat.RunaChat;

/** Stands in for the app's own cart (in Quicklly's app: its SQLite cart tables). */
final class SampleCart {
    static final class Line {
        final String pid, sid, title;
        final Double price;
        int qty;
        Line(String pid, String sid, String title, Double price, int qty) { this.pid = pid; this.sid = sid; this.title = title; this.price = price; this.qty = qty; }
    }

    private static final Map<String, Line> lines = new LinkedHashMap<>();

    static synchronized void set(String pid, String sid, String title, Double price, int qty) {
        if (qty <= 0) { lines.remove(pid); return; }
        Line l = lines.get(pid);
        if (l == null) lines.put(pid, new Line(pid, sid, title, price, qty));
        else l.qty = qty;
    }

    static synchronized void bumpFirst(int delta) {
        for (Line l : lines.values()) { set(l.pid, l.sid, l.title, l.price, l.qty + delta); return; }
    }

    static synchronized int qty(String pid) { Line l = lines.get(pid); return l == null ? 0 : l.qty; }

    static synchronized int count() { int n = 0; for (Line l : lines.values()) n += l.qty; return n; }

    static synchronized List<Line> lines() { return new ArrayList<>(lines.values()); }

    static synchronized List<RunaChat.CartItem> snapshot() {
        List<RunaChat.CartItem> out = new ArrayList<>();
        for (Line l : lines.values()) out.add(new RunaChat.CartItem(l.pid, l.sid, l.qty));
        return out;
    }
}
