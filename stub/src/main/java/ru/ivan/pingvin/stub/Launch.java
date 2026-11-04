package ru.ivan.pingvin.stub;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Ярлык-приложение, созданное «Пингвином»: запускает Linux-программу через общий runtime. */
public class Launch extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            String cfg = read("pingvin_app.properties");
            Intent i = new Intent("ru.ivan.pingvin.RUN");
            i.setClassName("ru.ivan.pingvin", "ru.ivan.pingvin.RunActivity");
            i.putExtra("config", cfg);
            Intent in = getIntent();
            if (Intent.ACTION_SEND.equals(in.getAction())) {
                Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) { i.putExtra(Intent.EXTRA_STREAM, u); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); i.setClipData(in.getClipData()); }
                String t = in.getStringExtra(Intent.EXTRA_TEXT);
                if (t != null) i.putExtra(Intent.EXTRA_TEXT, t);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Нужно приложение «Пингвин» (ru.ivan.pingvin): " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
    private String read(String n) throws Exception {
        try (InputStream is = getAssets().open(n)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int r;
            while ((r = is.read(buf)) > 0) o.write(buf, 0, r);
            return o.toString("UTF-8");
        }
    }
}
