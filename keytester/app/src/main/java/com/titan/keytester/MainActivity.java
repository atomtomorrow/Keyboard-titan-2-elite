package com.titan.keytester;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hardware key tester: shows every KeyEvent, counts DOWN/UP per key,
 * flags lost UP events, very short presses and chatter (double DOWN).
 */
public class MainActivity extends Activity {

    private static final int MAX_LOG = 300;
    private static final long CHATTER_MS = 60;   // two DOWNs closer than this = suspicious
    private static final long SHORT_MS = 25;     // press shorter than this = suspicious

    static volatile MainActivity instance;
    private TextView heldView, statsView, logView, svcView;
    private Button svcButton;
    private ScrollView logScroll;

    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final Map<Integer, int[]> stats = new TreeMap<>(); // [down, up, chatter, short, lostUp]
    private final Map<Integer, Long> downTime = new HashMap<>();
    private final Map<Integer, Long> lastDown = new HashMap<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        TextView hint = text(13, Color.GRAY);
        hint.setText("Жми клавиши. Каждое нажатие = DOWN + UP. "
                + "⚠ = потерян UP, дребезг (2 DOWN < " + CHATTER_MS + " мс) или слишком короткое (< " + SHORT_MS + " мс).");
        root.addView(hint);

        svcView = text(15, Color.WHITE);
        root.addView(svcView);
        svcButton = new Button(this);
        svcButton.setText("Включить службу Key Tester (Спец. возможности)");
        svcButton.setFocusable(false);
        svcButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(svcButton);

        heldView = text(18, Color.YELLOW);
        heldView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        heldView.setText("Зажато: —");
        root.addView(heldView);

        Button clear = new Button(this);
        clear.setText("Очистить");
        clear.setFocusable(false);
        clear.setOnClickListener(v -> {
            log.clear(); stats.clear(); downTime.clear(); lastDown.clear();
            refresh();
            root.requestFocus();
        });
        root.addView(clear);

        statsView = text(13, Color.WHITE);
        statsView.setTypeface(Typeface.MONOSPACE);
        root.addView(statsView);

        logScroll = new ScrollView(this);
        logScroll.setFocusable(false);
        logView = text(12, Color.rgb(140, 255, 140));
        logView.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        root.requestFocus();
        instance = this;
        updateSvc();
        refresh();
    }

    @Override protected void onResume() { super.onResume(); instance = this; updateSvc(); }
    @Override protected void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    static void notifyServiceState() {
        MainActivity a = instance;
        if (a != null) a.runOnUiThread(a::updateSvc);
    }

    private void updateSvc() {
        if (svcView == null) return;
        if (KeyService.running) {
            svcView.setText("Служба: ВКЛ — видим сырые нажатия до клавиатуры (IME)");
            svcView.setTextColor(Color.rgb(140, 255, 140));
            svcButton.setVisibility(View.GONE);
        } else {
            svcView.setText("Служба: ВЫКЛ — клавиатура (IME) съедает нажатия, Shift не видно. Включи службу:");
            svcView.setTextColor(Color.rgb(255, 120, 120));
            svcButton.setVisibility(View.VISIBLE);
        }
    }

    /** Raw events from the accessibility service (before IME). */
    void onRawKey(KeyEvent e) {
        final KeyEvent c = new KeyEvent(e);
        runOnUiThread(() -> handle(c, "RAW"));
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setFocusable(false);
        t.setGravity(Gravity.START);
        return t;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        if (KeyService.running) return true; // service already logs raw events
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) {
            addLog(fmt.format(new Date()) + " APP текст от IME: \"" + e.getCharacters() + "\" (не нажатие)");
            refresh();
            return true;
        }
        handle(e, "APP");
        return true;
    }

    private void addLog(String line) {
        log.addFirst(line);
        while (log.size() > MAX_LOG) log.removeLast();
    }

    private void handle(KeyEvent e, String src) {
        int code = e.getKeyCode();
        long now = SystemClock.uptimeMillis();
        int[] s = stats.get(code);
        if (s == null) { s = new int[5]; stats.put(code, s); }

        StringBuilder flag = new StringBuilder();
        String act;

        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            act = "DOWN";
            if (e.getRepeatCount() > 0) {
                act = "REPEAT#" + e.getRepeatCount();
            } else {
                s[0]++;
                if (downTime.containsKey(code)) { s[4]++; flag.append(" ⚠ нет UP у прошлого нажатия"); }
                Long prev = lastDown.get(code);
                if (prev != null && now - prev < CHATTER_MS) {
                    s[2]++; flag.append(" ⚠ дребезг ").append(now - prev).append("мс");
                }
                lastDown.put(code, now);
                downTime.put(code, now);
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            act = "UP  ";
            s[1]++;
            Long d = downTime.remove(code);
            if (d != null) {
                long dur = now - d;
                flag.append(" ").append(dur).append("мс");
                if (dur < SHORT_MS) { s[3]++; flag.append(" ⚠ коротко"); }
            } else {
                flag.append(" ⚠ UP без DOWN");
            }
            if ((e.getFlags() & KeyEvent.FLAG_CANCELED) != 0) flag.append(" CANCELED");
        } else {
            act = "MULTI";
        }

        InputDevice dev = e.getDevice();
        String line = fmt.format(new Date()) + " " + src + " " + act + " "
                + KeyEvent.keyCodeToString(code).replace("KEYCODE_", "")
                + " code=" + code + " scan=" + e.getScanCode()
                + " meta=0x" + Integer.toHexString(e.getMetaState())
                + (dev != null ? " dev=" + dev.getName() : "")
                + flag;

        addLog(line);
        refresh();
    }

    private void refresh() {
        // held keys
        if (downTime.isEmpty()) {
            heldView.setText("Зажато: —");
        } else {
            StringBuilder h = new StringBuilder("Зажато: ");
            for (int c : downTime.keySet()) h.append(KeyEvent.keyCodeToString(c).replace("KEYCODE_", "")).append(' ');
            heldView.setText(h.toString());
        }

        // stats table
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-14s %5s %5s %4s %4s %4s%n", "KEY", "DOWN", "UP", "ДРБ", "КОР", "-UP"));
        for (Map.Entry<Integer, int[]> en : stats.entrySet()) {
            int[] s = en.getValue();
            String name = KeyEvent.keyCodeToString(en.getKey()).replace("KEYCODE_", "");
            if (name.length() > 14) name = name.substring(0, 14);
            boolean bad = s[0] != s[1] || s[2] > 0 || s[3] > 0 || s[4] > 0;
            sb.append(String.format(Locale.US, "%-14s %5d %5d %4d %4d %4d%s%n",
                    name, s[0], s[1], s[2], s[3], s[4], bad ? " ⚠" : ""));
        }
        statsView.setText(sb.toString());

        StringBuilder l = new StringBuilder();
        for (String s : log) l.append(s).append('\n');
        logView.setText(l.toString());
        logScroll.scrollTo(0, 0);
    }
}
