package ru.ivan.pingvin;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Properties;

public class MainActivity extends Activity {
    Linux lx; TextView status, log; LinearLayout apps; EditText pkg; ScrollView sv;
    static final int PICK_INSTALL = 1, PICK_IMPORT = 2;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(this);
        lx = new Linux(this);
        Bridge.start(this);
        String cr = Crash.read(this);
        if (cr != null) {
            TextView t = new TextView(this); t.setText(cr); t.setTextIsSelectable(true); t.setTypeface(android.graphics.Typeface.MONOSPACE); t.setTextSize(11); t.setPadding(24, 24, 24, 24);
            ScrollView s2 = new ScrollView(this); s2.addView(t);
            new android.app.AlertDialog.Builder(this).setTitle("Прошлое падение приложения").setView(s2)
                .setPositiveButton("Копировать", (d, w) -> ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("pingvin crash", cr)))
                .setNeutralButton("Удалить", (d, w) -> Crash.file(this).renameTo(new java.io.File(getFilesDir(), "crash-old.txt")))
                .setNegativeButton("Закрыть", null).show();
        }
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(24, 24, 24, 24);
        sv = new ScrollView(this); sv.addView(l); setContentView(sv);
        status = new TextView(this); status.setTextSize(18); l.addView(status);
        btn(l, "Установить / переустановить Debian 13", v -> installDebian());
        btn(l, "Терминал", v -> startActivity(new Intent(this, TerminalActivity.class)));
        btn(l, "Рабочий стол (встроенный дисплей)", v -> startActivity(new Intent(this, DisplayActivity.class)));
        pkg = new EditText(this); pkg.setHint("имя пакета Debian, напр. cowsay или gimp"); pkg.setSingleLine(); l.addView(pkg);
        btn(l, "Установить по имени", v -> { String n = pkg.getText().toString().trim(); if (n.matches("[a-z0-9.+-]+")) term("pingvin-install " + n); else toast("Неверное имя"); });
        btn(l, "Установить из файла (.deb / AppImage / tar / .zip / .exe / .msi)", v -> pick(PICK_INSTALL));
        btn(l, "Браузер Linux (Firefox ESR, встроен)", v -> display("pingvin-browser"));
        ffOpt(l, "Firefox: отключить песочницу (если падают вкладки)", "ff-nosandbox");
        ffOpt(l, "Firefox: однопроцессный режим (если падают вкладки)", "ff-single");
        btn(l, "Браузер Windows (K-Meleon через Wine, встроен)", v -> display("pingvin-winbrowser"));
        btn(l, "Windows-программы: проверить Wine (встроен)", v -> term("pingvin-wine --setup"));
        btn(l, "DOS-программы и игры (DOSBox-X, ставится при первом запуске)", v -> display("pingvin-dos --menu"));
        btn(l, "Эмуляторы консолей (каталог, скачать внутрь)", v -> display("pingvin-emu --menu"));
        btn(l, "Сбросить Wine (пересоздать префикс)", v -> term("pingvin-wine --reset"));
        btn(l, "Установить Wine Mono (.NET-программы, ~80 МБ)", v -> term("pingvin-wine --mono"));
        btn(l, "Диагностика системы (SoC, страница памяти, адреса)", v -> term("pingvin-sysinfo"));
        btn(l, "Передать файл в Linux (/root/Android/inbox)", v -> pick(PICK_IMPORT));
        btn(l, "Доступ к памяти телефона (/sdcard)", v -> storage());
        TextView h = new TextView(this);
        h.setText("\nОбмен файлами: папка /root/Android в Linux; /sdcard — память телефона (после разрешения).\nИз Linux: android-share ФАЙЛ, android-open ФАЙЛ|URL.\nx86_64-программы запускаются через box64 (pingvin-run).\nWindows .exe/.msi: pingvin-wine ФАЙЛ — встроенный Hangover Wine 11.16 (родной arm64; x86 через Box64/FEX), без загрузки.\n\nПрограммы:");
        l.addView(h);
        apps = new LinearLayout(this); apps.setOrientation(LinearLayout.VERTICAL); l.addView(apps);
        log = new TextView(this); log.setTextIsSelectable(true); log.setTypeface(android.graphics.Typeface.MONOSPACE); l.addView(log);
        btn(l, "Копировать лог", v -> LogShare.copy(this, log.getText()));
        btn(l, "Поделиться логом", v -> LogShare.share(this, log.getText()));
        new Thread(() -> lx.ensureExtras(this::logl)).start();
    }

    void display(String prog) {
        if (!lx.installed()) { toast("Сначала установите Debian"); return; }
        startActivity(new Intent(this, DisplayActivity.class).putExtra("prog", prog));
    }

    @Override protected void onResume() { super.onResume(); refresh(); menuSig = menuSignature(); h.postDelayed(menuPoll, 2000); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(menuPoll); }
    final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    String menuSig = "";
    final Runnable menuPoll = new Runnable() { public void run() {
        new Thread(() -> { String sg = menuSignature(); if (!sg.equals(menuSig)) { menuSig = sg; runOnUiThread(MainActivity.this::refresh); } }).start();
        h.postDelayed(this, 2000); } };
    /** Подпись содержимого папок с ярлыками: меняется, когда установщик добавил/удалил .desktop. */
    String menuSignature() { StringBuilder b = new StringBuilder(); for (File f : desktopFiles()) b.append(f.getPath()).append(f.lastModified()).append(';');
        File sig = new File(lx.rootfs, "root/.pingvin/menu-changed"); b.append(sig.lastModified()); return b.toString(); }
    java.util.List<File> desktopFiles() {
        java.util.List<File> out = new java.util.ArrayList<>();
        walk(new File(lx.rootfs, "root/.local/share/applications"), out, 0); walk(new File(lx.rootfs, "usr/share/applications"), out, 0);
        java.util.Collections.sort(out); return out; }
    void walk(File d, java.util.List<File> out, int depth) { File[] fs = d.listFiles(); if (fs == null || depth > 6) return;
        for (File f : fs) { if (f.isDirectory()) walk(f, out, depth + 1); else if (f.getName().endsWith(".desktop")) out.add(f); } }
    static final java.util.Set<String> HIDE = new java.util.HashSet<>(Arrays.asList("openbox.desktop", "xterm.desktop", "uxterm.desktop", "debian-xterm.desktop", "debian-uxterm.desktop", "wine.desktop", "pingvin-wine-open.desktop", "pingvin-pkg-open.desktop"));
    Properties readDesktop(File f) {
        Properties p = new Properties(); boolean in = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) { l = l.trim();
                if (l.startsWith("[")) { in = l.equals("[Desktop Entry]"); continue; }
                int i = l.indexOf('='); if (in && i > 0 && !p.containsKey(l.substring(0, i))) p.setProperty(l.substring(0, i), l.substring(i + 1)); }
        } catch (IOException e) { return null; }
        return p; }
    android.graphics.Bitmap icon(String name) {
        if (name == null || name.isEmpty()) return null;
        File f = name.startsWith("/") ? new File(lx.rootfs, name.substring(1)) : null;
        if (f == null || !f.exists()) for (String sz : new String[]{"48x48", "64x64", "32x32", "128x128", "256x256"}) for (String base : new String[]{"root/.local/share/icons/hicolor/", "usr/share/icons/hicolor/"}) {
            File c = new File(lx.rootfs, base + sz + "/apps/" + name + ".png"); if (c.exists()) { f = c; break; } }
        if ((f == null || !f.exists())) { File c = new File(lx.rootfs, "usr/share/pixmaps/" + name + ".png"); if (c.exists()) f = c; }
        if (f == null || !f.exists() || !f.getName().endsWith(".png")) return null;
        return android.graphics.BitmapFactory.decodeFile(f.getPath()); }
    void menuApps() {
        TextView hd = new TextView(this); hd.setText("\nМеню (установленные программы, обновляется само):"); apps.addView(hd);
        for (File f : desktopFiles()) {
            if (HIDE.contains(f.getName())) continue;
            Properties p = readDesktop(f); if (p == null) continue;
            if (!"Application".equals(p.getProperty("Type")) || "true".equalsIgnoreCase(p.getProperty("NoDisplay")) || "true".equalsIgnoreCase(p.getProperty("Hidden")) || p.getProperty("Exec") == null) continue;
            if ("true".equalsIgnoreCase(p.getProperty("Terminal"))) continue;
            String name = p.getProperty("Name[ru]", p.getProperty("Name", f.getName()));
            boolean wine = f.getPath().contains("/applications/wine/") || p.getProperty("Exec").contains("wine");
            LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.graphics.Bitmap bm = icon(p.getProperty("Icon"));
            if (bm != null) { android.widget.ImageView iv = new android.widget.ImageView(this); iv.setImageBitmap(bm); int sz = (int) (36 * getResources().getDisplayMetrics().density); row.addView(iv, new LinearLayout.LayoutParams(sz, sz)); }
            TextView t = new TextView(this); t.setText(" " + name + (wine ? " [Windows]" : "")); row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            String rel = "/" + lx.rootfs.toURI().relativize(f.toURI()).getPath();
            Button run = new Button(this); run.setText("▶"); run.setOnClickListener(v -> display("pingvin-launch " + Bridge.q(rel)));
            row.addView(run); apps.addView(row);
        } }

    /** Переключатель-флаг /root/.pingvin/NAME; pingvin-browser применяет/снимает его при каждом запуске Firefox. */
    void ffOpt(LinearLayout l, String t, String name) {
        android.widget.CheckBox c = new android.widget.CheckBox(this); c.setText(t);
        java.io.File f = new java.io.File(lx.rootfs, "root/.pingvin/" + name);
        java.io.File fa = new java.io.File(lx.rootfs, "root/.pingvin/" + name + ".auto");
        c.setChecked(f.exists() || fa.exists());
        c.setOnCheckedChangeListener((b, on) -> { try { f.getParentFile().mkdirs(); if (on) new java.io.FileOutputStream(f).close(); else { f.delete(); fa.delete(); } } catch (Exception e) { Toast.makeText(this, "" + e, Toast.LENGTH_SHORT).show(); }
            Toast.makeText(this, "Применится при следующем запуске Firefox", Toast.LENGTH_SHORT).show(); });
        l.addView(c);
    }
    void btn(LinearLayout l, String t, View.OnClickListener c) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setOnClickListener(c); l.addView(b); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    void logl(String s) { runOnUiThread(() -> { log.append(s + "\n"); }); }
    void term(String cmd) { if (!lx.installed()) { toast("Сначала установите Debian"); return; } startActivity(new Intent(this, TerminalActivity.class).putExtra("cmd", cmd)); }

    void refresh() {
        status.setText(lx.installed() ? "Debian установлен (" + Linux.arch() + ")" : "Debian не установлен");
        apps.removeAllViews();
        if (lx.installed()) try { menuApps(); } catch (Exception e) { logl("меню: " + e); }
        File[] f = new File(lx.rootfs, "root/.pingvin/apps").listFiles((d, n) -> n.endsWith(".properties"));
        if (f == null) return;
        Arrays.sort(f);
        for (File x : f) {
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(x)) { p.load(in); } catch (IOException e) { continue; }
            String name = p.getProperty("name", x.getName().replace(".properties", ""));
            String gui = p.getProperty("gui", "0");
            LinearLayout row = new LinearLayout(this);
            TextView t = new TextView(this); t.setText(name + ("1".equals(gui) ? " [GUI]" : "")); row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            Button run = new Button(this); run.setText("▶"); run.setOnClickListener(v -> startActivity(new Intent(this, RunActivity.class).putExtra("name", name).putExtra("gui", gui)));
            Button apk = new Button(this); apk.setText("APK"); apk.setOnClickListener(v -> makeApk(x, name));
            Button gt = new Button(this); gt.setText("GUI±"); gt.setOnClickListener(v -> { p.setProperty("gui", "1".equals(gui) ? "0" : "1"); save(x, p); refresh(); });
            row.addView(run); row.addView(gt); row.addView(apk); apps.addView(row);
        }
    }

    void save(File x, Properties p) {
        StringBuilder s = new StringBuilder();
        for (String k : new String[]{"name", "cmd", "gui", "libs"}) s.append(k).append('=').append(p.getProperty(k, "")).append('\n');
        try (OutputStream o = new FileOutputStream(x)) { o.write(s.toString().getBytes(StandardCharsets.UTF_8)); } catch (IOException e) { toast(e.toString()); }
    }

    void installDebian() {
        log.setText("");
        new Thread(() -> {
            try { lx.install(this::logl); lx.ensureExtras(this::logl); } catch (Exception e) { logl("ОШИБКА: " + e); }
            runOnUiThread(this::refresh);
        }).start();
    }

    void pick(int code) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i, code);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        new Thread(() -> {
            try {
                String p = Bridge.importUri(this, u);
                logl("Скопировано: " + p);
                if (req == PICK_INSTALL) runOnUiThread(() -> term("pingvin-install " + Bridge.q(p)));
            } catch (Exception e) { logl("Ошибка: " + e); }
        }).start();
    }

    void storage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) { toast("Доступ уже есть"); return; }
            try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
        } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 3);
    }

    /** Переупаковка программы в отдельный подписанный APK-ярлык. */
    void makeApk(File cfgFile, String name) {
        logl("Собираю APK для " + name + "…");
        new Thread(() -> {
            try {
                String cfg = new String(ApkMaker.readAll(new FileInputStream(cfgFile)), StandardCharsets.UTF_8);
                String pk = ApkMaker.packageFor(name);
                File dir = new File(getCacheDir(), "out/apk"); dir.mkdirs();
                File uns = new File(dir, "u.apk"), out = new File(dir, name + ".apk");
                try (InputStream t = getAssets().open("stub.apk")) { ApkMaker.build(t, uns, pk, name, null, cfg); }
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
                if (!ks.containsAlias("pingvin-apps")) {
                    KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
                    g.initialize(new KeyGenParameterSpec.Builder("pingvin-apps", KeyProperties.PURPOSE_SIGN)
                            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).setKeySize(2048)
                            .setCertificateSubject(new javax.security.auth.x500.X500Principal("CN=Pingvin apps"))
                            .setCertificateNotAfter(new java.util.Date(System.currentTimeMillis() + 30L * 365 * 86400000L)).build());
                    g.generateKeyPair();
                }
                PrivateKey key = (PrivateKey) ks.getKey("pingvin-apps", null);
                X509Certificate cert = (X509Certificate) ks.getCertificate("pingvin-apps");
                ApkMaker.sign(uns, out, key, cert); uns.delete();
                logl("Готово: " + pk + " (" + out.length() / 1024 + " КБ)");
                Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", out);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("APK «" + name + "» готов")
                        .setPositiveButton("Установить", (a, c) -> {
                            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive");
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(i); })
                        .setNeutralButton("Поделиться", (a, c) -> {
                            Intent i = new Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM, u);
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(i, "APK")); })
                        .setNegativeButton("Закрыть", null).show());
            } catch (Throwable e) { logl("Ошибка APK: " + e); android.util.Log.e("pingvin", "apk", e); }
        }).start();
    }
}
