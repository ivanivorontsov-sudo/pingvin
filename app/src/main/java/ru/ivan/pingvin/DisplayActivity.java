package ru.ivan.pingvin;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Встроенный дисплей: Xvnc (TigerVNC) + встроенный VNC-клиент (RfbClient/VncView), с диагностикой. */
public class DisplayActivity extends Activity implements RfbClient.Listener {
    static final int N = 1, PORT = 5900 + N, TIMEOUT_MS = 6 * 60_000;
    static Process holder;
    TextView log; ScrollView sv; VncView vnc; RfbClient rfb; Linux lx; volatile boolean ready, failed; volatile Process progProc; int reconnects;
    final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(this);
        lx = new Linux(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        btn(bar, "Копировать", v -> LogShare.copy(this, log.getText()));
        btn(bar, "Поделиться", v -> LogShare.share(this, log.getText()));
        btn(bar, "Диагностика", v -> diag());
        root.addView(bar);
        log = new TextView(this); log.setTextIsSelectable(true); log.setPadding(16, 16, 16, 16); log.setTypeface(android.graphics.Typeface.MONOSPACE);
        sv = new ScrollView(this); sv.addView(log);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        say("Запускаю рабочий стол (первый раз распаковываются встроенные пакеты, 1–5 мин)…");
        String prog = getIntent().getStringExtra("prog");
        h.postDelayed(() -> { if (!ready && !failed) { say("\n⚠ Дисплей не запустился за 6 минут."); diag(); } }, TIMEOUT_MS);
        new Thread(() -> {
            try {
                lx.ensureExtras(this::say);
                if (lx.ensureGuiDebs()) say("Встроенные пакеты дисплея распакованы в /opt/pingvin/debs.");
                if (!ensureServer()) return;
                ready = true; connect();
                if (prog != null) runProg(prog);
            } catch (Exception e) { failed = true; say("✖ " + e); diag(); }
        }).start();
    }

    /** Xvnc живёт в отдельном долгоживущем процессе (holder), не связанном с программами: их выход его не убивает. */
    boolean ensureServer() throws Exception {
        synchronized (DisplayActivity.class) {
            if (quickAlive()) { say("Дисплей уже запущен — подключаюсь."); return true; }
            if (holder != null) { holder.destroy(); holder = null; }
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float k = Math.max(1f, dm.density / 1.6f);
            int gw = ((int) (dm.widthPixels / k)) & ~7, gh = ((int) ((dm.heightPixels - 64 * dm.density) / k)) & ~7;
            Process p = lx.start("PINGVIN_GEOMETRY=" + gw + "x" + gh + " PINGVIN_HOLD=1 pingvin-desktop");
            holder = p;
            p.getOutputStream().close();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String l;
            while ((l = br.readLine()) != null) {
                if (l.contains("PINGVIN_DISPLAY_READY")) return true;
                say(l);
            }
            failed = true; int code = p.waitFor(); holder = null;
            say("\n✖ Ошибка запуска дисплея, код " + code); diag(); return false;
        }
    }

    void runProg(String prog) throws Exception {
        say("Запускаю: " + prog);
        Process p = lx.start("export DISPLAY=:" + N + "; exec pingvin-run " + prog);
        progProc = p; p.getOutputStream().close();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String l; while ((l = br.readLine()) != null) say(l);
        int code = p.waitFor();
        if (isFinishing()) return;
        say("Программа завершилась (код " + code + ")");
        if (code == 0) runOnUiThread(() -> { if (!isFinishing()) { Toast.makeText(this, "Программа завершилась", Toast.LENGTH_SHORT).show(); finish(); } });
        else runOnUiThread(() -> { if (isFinishing()) return;
            if (log.getParent() != null) ((android.view.ViewGroup) log.getParent()).removeView(log);
            new android.app.AlertDialog.Builder(this).setTitle("Ошибка (код " + code + ") — лог").setView(wrap(log))
                .setPositiveButton("Закрыть дисплей", (d, w) -> finish()).setNeutralButton("Копировать", (d, w) -> LogShare.copy(this, log.getText()))
                .setNegativeButton("Поделиться", (d, w) -> LogShare.share(this, log.getText())).setCancelable(false).show(); });
    }

    void btn(LinearLayout bar, String t, android.view.View.OnClickListener c) {
        Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setOnClickListener(c); bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    }

    boolean ffHint;
    void say(String s) { if (!ffHint && s != null && (s.contains("Exiting due to channel error") || s.contains("tab just crashed") || s.contains("[Child ") && s.contains("Crash"))) { ffHint = true;
            runOnUiThread(() -> Toast.makeText(this, "Вкладка Firefox упала? Включите в настройках Пингвина «Firefox: отключить песочницу» и/или «однопроцессный режим».", Toast.LENGTH_LONG).show()); }
        runOnUiThread(() -> { log.append(s + "\n"); sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN)); }); }

    void diag() {
        new Thread(() -> {
            try {
                Linux.Result r = lx.exec("echo '--- процессы:'; for p in /proc/[0-9]*; do c=$(tr '\\0' ' ' < $p/cmdline 2>/dev/null); case \"$c\" in *Xvnc*|*openbox*|*dpkg*) echo \"$c\";; esac; done; "
                    + "echo '--- порт " + PORT + ":'; (exec 3<>/dev/tcp/127.0.0.1/" + PORT + ") 2>/dev/null && echo открыт || echo закрыт; "
                    + "echo '--- Xvnc:'; command -v Xvnc || echo 'Xvnc не установлен'; ls /opt/pingvin/debs 2>/dev/null | wc -l; "
                    + "for f in /tmp/pingvin-desktop.log /tmp/pingvin-debs.log /tmp/xvnc.log /tmp/openbox.log; do echo \"--- $f:\"; tail -15 $f 2>/dev/null; done", 60);
                say("\n=== Диагностика ===\n" + r.out); String cr = Crash.read(this); if (cr != null) say("--- прошлые падения приложения:\n" + cr);
            } catch (Exception e) { say("диагностика: " + e); }
        }).start();
    }

    boolean quickAlive() { try (java.net.Socket s = new java.net.Socket()) { s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 500); return true; } catch (IOException e) { return false; } }

    void connect() {
        String pass = "";
        try { pass = new String(ApkMaker.readAll(new FileInputStream(new File(lx.rootfs, "root/.pingvin/vncpass"))), StandardCharsets.UTF_8).trim(); } catch (IOException e) { say("Нет пароля VNC: " + e); }
        Exception last = null;
        for (int i = 0; i < 10; i++) {
            try {
                rfb = new RfbClient(); rfb.connect("127.0.0.1", PORT, pass, this);
                runOnUiThread(this::showVnc);
                new Thread(rfb::loop).start();
                return;
            } catch (Exception e) { last = e; try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
        }
        failed = true; say("✖ Не удалось подключиться к Xvnc (127.0.0.1:" + PORT + "): " + last); diag();
    }

    void showVnc() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        vnc = new VncView(this); vnc.attach(rfb);
        root.addView(vnc, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout keys = new LinearLayout(this);
        btn(keys, "⌨", v -> vnc.showKeyboard());
        btn(keys, "Esc", v -> vnc.tap(0xff1b));
        btn(keys, "Tab", v -> vnc.tap(0xff09));
        btn(keys, "←", v -> vnc.tap(0xff51)); btn(keys, "↑", v -> vnc.tap(0xff52)); btn(keys, "↓", v -> vnc.tap(0xff54)); btn(keys, "→", v -> vnc.tap(0xff53));
        btn(keys, "1:1", v -> vnc.resetZoom());
        btn(keys, "Лог", v -> { if (log.getParent() != null) ((android.view.ViewGroup) log.getParent()).removeView(log); new android.app.AlertDialog.Builder(this).setView(wrap(log)).setPositiveButton("OK", null).setNeutralButton("Копировать", (d, w) -> LogShare.copy(this, log.getText())).setNegativeButton("Поделиться", (d, w) -> LogShare.share(this, log.getText())).show(); });
        root.addView(keys);
        setContentView(root);
        vnc.requestFocus();
    }

    ScrollView wrap(TextView t) { if (sv.getParent() != null) ((android.view.ViewGroup) sv.getParent()).removeView(sv); if (t.getParent() == null) sv.addView(t); return sv; }

    @Override public void onResize(int w, int hh) { if (vnc != null) vnc.resized(); }
    @Override public void onUpdate(int x, int y, int w, int hh) { if (vnc != null) vnc.updated(); }
    int restarts;
    @Override public void onClosed(Exception e) {
        if (isFinishing() || isDestroyed()) return;
        say("Соединение с дисплеем закрыто" + (e != null ? ": " + e : "") + " — переподключаюсь…");
        new Thread(() -> { try { Thread.sleep(700); } catch (InterruptedException ignored) {}
            if (isFinishing() || isDestroyed()) return;
            if (++reconnects > 20) { say("✖ Слишком много переподключений"); return; }
            if (quickAlive()) { connect(); return; }
            if (restarts >= 3) { say("✖ Xvnc не отвечает (порт " + PORT + "), перезапуски не помогли. Нажмите «Лог»/«Диагностика»."); return; }
            restarts++; say("⚠ Xvnc упал — перезапускаю дисплей (" + restarts + "/3)…");
            try { if (ensureServer()) { say("Дисплей перезапущен."); connect(); } } catch (Exception ex) { say("✖ Перезапуск дисплея: " + ex); }
        }).start();
    }
    @Override protected void onDestroy() { super.onDestroy(); if (rfb != null) rfb.close(); if (progProc != null) progProc.destroy(); }
}
