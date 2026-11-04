package ru.ivan.pingvin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

/** Приём «Поделиться» из Android: файлы попадают в /root/Android/inbox. */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) { ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) uris.addAll(l); }
        else if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) uris.add(in.getData());
        else { Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) uris.add(u); }
        String text = in.getStringExtra(Intent.EXTRA_TEXT);
        new Thread(() -> {
            StringBuilder msg = new StringBuilder(); String installable = null;
            try {
                for (Uri u : uris) {
                    String p = Bridge.importUri(this, u);
                    msg.append(p).append('\n');
                    String lc = p.toLowerCase();
                    if (lc.endsWith(".deb") || lc.endsWith(".appimage") || lc.endsWith(".exe") || lc.endsWith(".msi") || lc.matches(".*\\.(tar|tar\\.gz|tgz|tar\\.xz|txz|tar\\.bz2|tar\\.zst|zip)$")) installable = p;
                }
                if (uris.isEmpty() && text != null) {
                    java.io.File f = new java.io.File(new Linux(this).shared, "inbox/shared-" + System.currentTimeMillis() + ".txt");
                    f.getParentFile().mkdirs();
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(text.getBytes("UTF-8")); }
                    msg.append("/root/Android/inbox/").append(f.getName()).append('\n');
                }
            } catch (Exception e) { msg.append("Ошибка: ").append(e); }
            String inst = installable;
            runOnUiThread(() -> {
                AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle("Сохранено в Linux").setMessage(msg)
                        .setOnDismissListener(x -> finish())
                        .setNegativeButton("OK", null)
                        .setNeutralButton("Терминал", (x, y) -> startActivity(new Intent(this, TerminalActivity.class)));
                if (inst != null) d.setPositiveButton("Установить", (x, y) -> startActivity(new Intent(this, TerminalActivity.class).putExtra("cmd", "pingvin-install " + Bridge.q(inst))));
                d.show();
            });
        }).start();
    }
}
