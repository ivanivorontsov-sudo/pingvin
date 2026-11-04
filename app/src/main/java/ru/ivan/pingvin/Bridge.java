package ru.ivan.pingvin;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Мост Linux -> Android: android-share / android-open кладут заявки в /root/.pingvin/queue. */
public class Bridge {
    static Thread thread;

    public static synchronized void start(Context c) {
        if (thread != null) return;
        Context ctx = c.getApplicationContext();
        Linux lx = new Linux(ctx);
        thread = new Thread(() -> {
            File q = new File(lx.rootfs, "root/.pingvin/queue");
            while (true) {
                try {
                    File[] l = q.listFiles((d, n) -> n.endsWith(".req"));
                    if (l != null) { Arrays.sort(l); for (File f : l) { handle(ctx, lx, f); f.delete(); } }
                    Thread.sleep(800);
                } catch (InterruptedException e) { return; } catch (Throwable t) { android.util.Log.e("pingvin", "bridge", t); }
            }
        }, "pingvin-bridge");
        thread.setDaemon(true); thread.start();
    }

    /** Путь внутри Linux -> путь на Android. */
    public static File hostPath(Linux lx, String p) {
        if (p.startsWith("/root/Android")) return new File(lx.shared, p.substring("/root/Android".length()));
        if (p.startsWith("/sdcard")) return new File("/storage/emulated/0" + p.substring(7));
        return new File(lx.rootfs, p);
    }

    public static Uri export(Context ctx, File src) throws IOException {
        File out = new File(ctx.getCacheDir(), "out/" + System.nanoTime());
        out.mkdirs();
        File dst = new File(out, src.getName());
        Tar.copy(src, dst);
        return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", dst);
    }

    public static String mime(String name) {
        int i = name.lastIndexOf('.');
        String m = i < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(i + 1).toLowerCase());
        return m == null ? "application/octet-stream" : m;
    }

    static void handle(Context ctx, Linux lx, File req) throws IOException {
        String[] lines = new String(ApkMaker.readAll(new FileInputStream(req)), StandardCharsets.UTF_8).split("\n");
        if (lines.length < 2) return;
        String kind = lines[0].trim(), arg = lines[1].trim();
        Intent i;
        if (kind.equals("display")) {
            i = new Intent(ctx, DisplayActivity.class);
        } else if (kind.equals("text")) {
            String text = new String(ApkMaker.readAll(new FileInputStream(hostPath(lx, arg))), StandardCharsets.UTF_8);
            i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
            i = Intent.createChooser(i, "Поделиться текстом");
        } else if (kind.equals("open") && (arg.startsWith("http") || arg.startsWith("mailto:"))) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(arg));
        } else {
            File f = hostPath(lx, arg);
            if (!f.isFile()) { toast(ctx, "Файл не найден: " + arg); return; }
            Uri u = export(ctx, f);
            String m = mime(f.getName());
            if (kind.equals("open")) i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, m);
            else { i = new Intent(Intent.ACTION_SEND).setType(m).putExtra(Intent.EXTRA_STREAM, u); i.setClipData(ClipData.newRawUri("", u)); }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i = Intent.createChooser(i, kind.equals("open") ? "Открыть" : "Поделиться");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent fi = i;
        new Handler(Looper.getMainLooper()).post(() -> { try { ctx.startActivity(fi); } catch (Exception e) { toast(ctx, e.toString()); } });
    }

    static void toast(Context c, String s) { new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(c, s, Toast.LENGTH_LONG).show()); }

    /** Копирует content:// во /root/Android/inbox и возвращает путь внутри Linux. */
    public static String importUri(Context ctx, Uri u) throws IOException {
        Linux lx = new Linux(ctx);
        String name = "file";
        try (android.database.Cursor c = ctx.getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        } catch (Exception ignored) { if (u.getLastPathSegment() != null) name = u.getLastPathSegment(); }
        name = name.replaceAll("[/\\\\]", "_");
        File dir = new File(lx.shared, "inbox"); dir.mkdirs();
        File f = new File(dir, name);
        try (InputStream in = ctx.getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(f)) {
            byte[] b = new byte[65536]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r);
        }
        return "/root/Android/inbox/" + name;
    }

    public static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
