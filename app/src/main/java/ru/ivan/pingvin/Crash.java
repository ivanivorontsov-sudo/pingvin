package ru.ivan.pingvin;

import android.content.Context;
import java.io.*;

/** Глобальный обработчик падений: стектрейс -> files/crash.txt, показывается при следующем запуске. */
public class Crash {
    static boolean installed;
    static File file(Context c) { return new File(c.getFilesDir(), "crash.txt"); }
    static synchronized void install(Context ctx) {
        if (installed) return; installed = true;
        final Context c = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (PrintWriter w = new PrintWriter(new FileWriter(file(c), true))) {
                w.println("=== " + new java.util.Date() + " поток " + t.getName() + " (версия " + ver(c) + ")");
                e.printStackTrace(w);
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
    static String ver(Context c) { try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; } }
    /** Текст последних падений или null. */
    static String read(Context c) {
        File f = file(c); if (!f.isFile()) return null;
        try { return new String(ApkMaker.readAll(new FileInputStream(f)), "UTF-8"); } catch (IOException e) { return null; }
    }
}
