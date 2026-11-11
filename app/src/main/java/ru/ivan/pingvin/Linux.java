package ru.ivan.pingvin;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.tukaani.xz.XZInputStream;

/** Debian-окружение без root на базе PRoot. */
public class Linux {
    public static final String ROOTFS_VER = "v4.29.0";
    public interface Log { void line(String s); }

    public final Context ctx;
    public final File base, rootfs, shared, tmp;

    public Linux(Context c) {
        ctx = c.getApplicationContext();
        base = ctx.getFilesDir();
        rootfs = new File(base, "debian");
        shared = new File(base, "shared");
        tmp = new File(base, "tmp");
        shared.mkdirs(); tmp.mkdirs();
    }

    public static String arch() {
        for (String a : Build.SUPPORTED_ABIS) {
            if (a.equals("arm64-v8a")) return "aarch64";
            if (a.equals("x86_64")) return "x86_64";
        }
        return "aarch64";
    }

    public boolean installed() { return new File(rootfs, ".pingvin-ready").exists(); }
    String lib(String n) { return ctx.getApplicationInfo().nativeLibraryDir + "/" + n; }

    /** Скачивает и распаковывает Debian 13 (trixie) rootfs из proot-distro. */
    public void install(Log log) throws Exception {
        String a = arch();
        String url = "https://github.com/termux/proot-distro/releases/download/" + ROOTFS_VER + "/debian-trixie-" + a + "-pd-" + ROOTFS_VER + ".tar.xz";
        if (new File(rootfs, "etc/debian_version").exists() && new File(rootfs, "usr/bin/bash").exists()) {
            log.line("Rootfs уже распакован — пропускаю загрузку.");
        } else {
        File tar = new File(ctx.getCacheDir(), "rootfs.tar.xz");
        boolean embedded = Arrays.asList(ctx.getAssets().list("")).contains("rootfs-" + a + ".tar.xz");
        if (!embedded) { log.line("Скачиваю Debian 13 (" + a + ")…"); download(url, tar, log); }
        else log.line("Debian 13 (" + a + ") встроен в APK — загрузка не нужна.");
        log.line("Распаковываю…");
        if (rootfs.exists()) deleteTree(rootfs);
        rootfs.mkdirs();
        try (InputStream in = new XZInputStream(new BufferedInputStream(embedded ? ctx.getAssets().open("rootfs-" + a + ".tar.xz") : new FileInputStream(tar), 1 << 16))) {
            Tar.extract(in, rootfs, 1, log);
        }
        tar.delete();
        }
        configure();
        log.line("Проверяю запуск…");
        new File(base, ".noseccomp").delete();
        Result r = exec("uname -m && cat /etc/debian_version", 60);
        log.line(r.out.trim());
        if (r.code != 0 || r.out.contains("not implemented")) {
            log.line("Повтор без seccomp-ускорения (PROOT_NO_SECCOMP=1)…");
            new FileOutputStream(new File(base, ".noseccomp")).close();
            r = exec("uname -m && cat /etc/debian_version", 60);
            log.line(r.out.trim());
        }
        if (r.code != 0) throw new IOException("proot не запустился: " + r.out);
        new FileOutputStream(new File(rootfs, ".pingvin-ready")).close();
        log.line("Готово.");
    }

    /** DNS, apt без песочницы, хелперы обмена с Android. */
    /** Распаковывает встроенные arm64-пакеты дисплея в /opt/pingvin/debs, если Xvnc ещё не установлен. */
    public synchronized boolean ensureGuiDebs() {
        if (!android.os.Build.SUPPORTED_ABIS[0].startsWith("arm64")) return false;
        if (new File(rootfs, "usr/bin/Xvnc").exists() || isLink(new File(rootfs, "usr/bin/Xvnc"))) return false;
        File d = new File(rootfs, "opt/pingvin/debs"), ok = new File(d, ".complete");
        if (ok.exists()) return true;
        try (InputStream in = ctx.getAssets().open("gui-debs-aarch64.tar")) {
            d.mkdirs(); Tar.extract(in, d, 0, null); ok.createNewFile(); return true;
        } catch (Exception e) { android.util.Log.e("pingvin", "gui debs", e); return false; }
    }

    public static final String EXTRAS_VER = "1.15";
    static final Object EXTRAS_LOCK = new Object();
    /** Однократно: обновляет скрипты после обновления APK, распаковывает встроенный Wine (Hangover, arm64) и Firefox ESR. */
    static final String[] WINE_KEYS = {"usr/lib/wine/aarch64-unix/ntdll.so", "usr/lib/wine/aarch64-unix/wine", "usr/lib/wine/aarch64-windows/kernel32.dll",
        "usr/lib/wine/aarch64-windows/ntdll.dll", "usr/lib/wine/aarch64-windows/cmd.exe", "usr/lib/wine/aarch64-windows/wowbox64.dll", "usr/lib/wine/aarch64-windows/libarm64ecfex.dll",
        "usr/lib/wine/i386-windows/kernel32.dll", "usr/lib/wine/i386-windows/cmd.exe", "usr/bin/wineserver", "opt/kmeleon/k-meleon.exe", "opt/pingvin/.wine-ready"};
    String wineMissing() { StringBuilder b = new StringBuilder(); for (String k : WINE_KEYS) { if (k.endsWith(".wine-ready")) continue; File f = new File(rootfs, k); if (!f.exists() || f.length() == 0) b.append(k).append(' '); } return b.toString(); }
    boolean wineOk() { return wineMissing().isEmpty() && new File(rootfs, "opt/pingvin/.wine-ready").exists() && !new File(rootfs, "opt/pingvin/.wine-reextract").exists(); }
    public void ensureExtras(Log log) {
        synchronized (EXTRAS_LOCK) {
            if (!installed()) return;
            File mark = new File(rootfs, "opt/pingvin/.extras-" + EXTRAS_VER);
            boolean arm = Build.SUPPORTED_ABIS[0].startsWith("arm64");
            if (mark.exists() && (!arm || wineOk())) return;
            try {
                if (log != null) log.line("Обновляю окружение «Пингвина» до " + EXTRAS_VER + " (один раз)…");
                configure();
                if (Build.SUPPORTED_ABIS[0].startsWith("arm64")) {
                    List<String> as = Arrays.asList(ctx.getAssets().list(""));
                    if (as.contains("browser-debs-aarch64.tar") && !new File(rootfs, "usr/lib/firefox-esr/firefox-esr").exists()) {
                        File d = new File(rootfs, "opt/pingvin/xdebs"); d.mkdirs(); new File(d, ".complete").delete();
                        if (log != null) log.line("Распаковываю встроенный браузер Firefox ESR…");
                        try (InputStream in = ctx.getAssets().open("browser-debs-aarch64.tar")) { Tar.extract(in, d, 0, null); }
                        new File(d, ".complete").createNewFile();
                    }
                    if (as.contains("box64-x86libs.tar") && !new File(rootfs, "usr/lib/box64-x86_64-linux-gnu/libgcc_s.so.1").exists()) {
                        try (InputStream in = ctx.getAssets().open("box64-x86libs.tar")) { Tar.extract(in, rootfs, 0, null); }
                    }
                    if (as.contains("wine-prefix.tar.xz")) {
                        File pt = new File(rootfs, "opt/pingvin/wine-prefix.tar.xz"), ptv = new File(rootfs, "opt/pingvin/.wine-prefix-" + EXTRAS_VER);
                        if (!pt.exists() || !ptv.exists()) {
                            if (log != null) log.line("Копирую готовый префикс Windows…");
                            pt.getParentFile().mkdirs(); File tmp = new File(pt.getPath() + ".part");
                            try (InputStream in = ctx.getAssets().open("wine-prefix.tar.xz"); OutputStream o = new FileOutputStream(tmp)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); }
                            tmp.renameTo(pt); ptv.createNewFile();
                        }
                    }
                    if (as.contains("wine-aarch64.tar.xz") && !wineOk()) {
                        if (log != null) log.line("Wine отсутствует или распакован не полностью — распаковываю заново.");
                        File wm = new File(rootfs, "opt/pingvin/.wine-ready"); wm.delete();
                        if (log != null) log.line("Распаковываю встроенный Wine (Hangover 11.16 arm64 + FEX + Box64) и K-Meleon, ~1 ГБ, 1–3 мин…");
                        try (InputStream in = new XZInputStream(new BufferedInputStream(ctx.getAssets().open("wine-aarch64.tar.xz"), 1 << 16))) { Tar.extract(in, rootfs, 0, null); }
                        new File(rootfs, "opt/wine").renameTo(new File(rootfs, "opt/wine-old-kron4ek"));
                        if (!wineMissing().isEmpty()) throw new IOException("после распаковки Wine нет ключевых файлов: " + wineMissing());
                        new File(rootfs, "opt/pingvin/.wine-reextract").delete();
                        wm.getParentFile().mkdirs(); write("opt/pingvin/.wine-ready", "hangover-11.16\n");
                        if (log != null) log.line("Wine распакован.");
                    }
                }
                mark.getParentFile().mkdirs(); mark.createNewFile();
            } catch (Exception e) { if (log != null) log.line("Ошибка распаковки встроенных компонентов: " + e); android.util.Log.e("pingvin", "extras", e); }
        }
    }

    public void configure() throws IOException {
        write("etc/resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 77.88.8.8\n");
        write("etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost\n127.0.1.1 pingvin\n");
        write("etc/hostname", "pingvin\n");
        write("etc/gai.conf", "precedence ::ffff:0:0/96  100\n");
        write("etc/apt/apt.conf.d/98pingvin-net", "Acquire::ForceIPv4 \"true\";\nAcquire::Retries \"3\";\n");
        write("etc/apt/apt.conf.d/99pingvin", "APT::Sandbox::User \"root\";\nDpkg::Use-Pty \"0\";\nAPT::Install-Recommends \"0\";\n");
        for (String sc : ctx.getAssets().list("scripts")) {
            File f = new File(rootfs, (sc.endsWith(".sh") ? "etc/profile.d/" : "usr/local/bin/") + sc); f.getParentFile().mkdirs();
            if (f.exists() || isLink(f)) f.delete();
            try (InputStream in = ctx.getAssets().open("scripts/" + sc); OutputStream o = new FileOutputStream(f)) {
                byte[] bb = new byte[8192]; int r; while ((r = in.read(bb)) > 0) o.write(bb, 0, r);
            }
            f.setExecutable(true, false);
        }
        for (String sc : ctx.getAssets().list("opt")) { // обои и пр. → /opt/pingvin
            File f = new File(rootfs, "opt/pingvin/" + sc); f.getParentFile().mkdirs();
            if (f.exists() && f.length() > 0) continue;
            try (InputStream in = ctx.getAssets().open("opt/" + sc); OutputStream o = new FileOutputStream(f)) {
                byte[] bb = new byte[65536]; int r; while ((r = in.read(bb)) > 0) o.write(bb, 0, r);
            }
        }
        ensureGuiDebs();
        new File(rootfs, "root/.pingvin/queue").mkdirs();
        new File(rootfs, "root/Android").mkdirs();
    }

    void write(String rel, String s) throws IOException {
        File f = new File(rootfs, rel); f.getParentFile().mkdirs();
        if (f.exists() || isLink(f)) f.delete();
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }
    static boolean isLink(File f) { return java.nio.file.Files.isSymbolicLink(f.toPath()); }

    public List<String> command(String shellCmd, boolean login) {
        List<String> c = new ArrayList<>();
        c.add(lib("libproot.so"));
        c.add("-r"); c.add(rootfs.getAbsolutePath());
        c.add("-0"); c.add("--link2symlink"); c.add("--sysvipc"); c.add("-L");
        c.add("--kill-on-exit");
        for (String b : new String[]{"/dev", "/proc", "/sys", "/dev/urandom:/dev/random"}) { c.add("-b"); c.add(b); }
        c.add("-b"); c.add(tmp.getAbsolutePath() + ":/dev/shm");
        c.add("-b"); c.add(shared.getAbsolutePath() + ":/root/Android");
        if (new File("/storage/emulated/0").canRead()) { c.add("-b"); c.add("/storage/emulated/0:/sdcard"); }
        c.add("-w"); c.add("/root");
        c.add("/usr/bin/env"); c.add("-i");
        c.add("HOME=/root"); c.add("USER=root"); c.add("LANG=C.UTF-8"); c.add("TERM=xterm-256color"); c.add("PINGVIN_APP=1");
        c.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games");
        c.add("TMPDIR=/tmp"); c.add("DEBIAN_FRONTEND=noninteractive");
        c.add("PULSE_SERVER=127.0.0.1");
        c.add("/bin/bash"); if (login) c.add("-l");
        c.add("-c"); c.add(shellCmd);
        return c;
    }

    public Process start(String shellCmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command(shellCmd, false));
        Map<String, String> env = pb.environment();
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("PROOT_LOADER", lib("libprootloader.so"));
        env.put("PROOT_LOADER_32", lib("libprootloader32.so"));
        env.remove("LD_PRELOAD"); env.put("LD_LIBRARY_PATH", ctx.getApplicationInfo().nativeLibraryDir);
        if (noSeccomp()) env.put("PROOT_NO_SECCOMP", "1");
        pb.redirectErrorStream(true);
        return pb.start();
    }

    public String[] envArray() {
        return new String[]{"PROOT_TMP_DIR=" + tmp.getAbsolutePath(), "PROOT_LOADER=" + lib("libprootloader.so"),
                "PROOT_LOADER_32=" + lib("libprootloader32.so"), "TERM=xterm-256color", "PINGVIN_APP=1", "HOME=/root", "LD_LIBRARY_PATH=" + ctx.getApplicationInfo().nativeLibraryDir,
                noSeccomp() ? "PROOT_NO_SECCOMP=1" : "PINGVIN=1"};
    }
    public boolean noSeccomp() { return new File(base, ".noseccomp").exists(); }
    public String prootPath() { return lib("libproot.so"); }

    public static class Result { public int code; public String out; }

    public Result exec(String cmd, int timeoutSec) throws Exception {
        Process p = start(cmd);
        p.getOutputStream().close();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int r; InputStream in = p.getInputStream();
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while ((r = in.read(b)) > 0) { o.write(b, 0, r); if (System.currentTimeMillis() > end) { p.destroy(); break; } }
        Result res = new Result(); res.code = p.waitFor(); res.out = o.toString("UTF-8");
        return res;
    }

    /** Запуск с построчным выводом в лог. */
    public int run(String cmd, Log log) throws Exception {
        Process p = start(cmd);
        p.getOutputStream().close();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String l; while ((l = br.readLine()) != null) log.line(l);
        return p.waitFor();
    }

    public static void download(String url, File out, Log log) throws IOException {
        for (int redirects = 0; redirects < 8; redirects++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code / 100 == 3) { url = c.getHeaderField("Location"); continue; }
            if (code != 200) throw new IOException("HTTP " + code + " " + url);
            long total = c.getContentLengthLong(), got = 0, lastPct = -1;
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] b = new byte[65536]; int r;
                while ((r = in.read(b)) > 0) {
                    o.write(b, 0, r); got += r;
                    long pct = total > 0 ? got * 100 / total : -1;
                    if (pct != lastPct && pct % 10 == 0) { log.line("  " + pct + "% (" + got / 1048576 + " МБ)"); lastPct = pct; }
                }
            }
            return;
        }
        throw new IOException("too many redirects");
    }

    public static void deleteTree(File f) {
        if (f.isDirectory() && !isLink(f)) { File[] l = f.listFiles(); if (l != null) for (File x : l) deleteTree(x); }
        f.delete();
    }
}
