package ru.ivan.pingvin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Копирование/отправка логов: буфер обмена (хвост ~100 КБ), Share — файлом целиком + текстом-хвостом. */
public final class LogShare {
    static final int MAX = 100_000;
    static String tail(CharSequence s) { String t = s == null ? "" : s.toString(); return t.length() > MAX ? "…(обрезано, показан хвост)\n" + t.substring(t.length() - MAX) : t; }
    public static void copy(Context c, CharSequence s) {
        try {
            ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("pingvin log", tail(s)));
            Toast.makeText(c, (s != null && s.length() > MAX) ? "Скопирован хвост лога (100 КБ). Целиком — «Поделиться»" : "Лог скопирован", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(c, "Не удалось скопировать: " + e, Toast.LENGTH_LONG).show(); }
    }
    public static void share(Context c, CharSequence s) {
        Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Пингвин — лог");
        i.putExtra(Intent.EXTRA_TEXT, tail(s));
        try {
            File d = new File(c.getCacheDir(), "out"); d.mkdirs();
            File f = new File(d, "pingvin-log.txt");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write((s == null ? "" : s.toString()).getBytes(StandardCharsets.UTF_8)); }
            Uri u = androidx.core.content.FileProvider.getUriForFile(c, "ru.ivan.pingvin.files", f);
            i.putExtra(Intent.EXTRA_STREAM, u); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        c.startActivity(Intent.createChooser(i, "Поделиться логом"));
    }
}
