package ru.ivan.pingvin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.io.StringReader;
import java.util.Properties;

/** Запуск установленной Linux-программы (из списка или из созданного APK-ярлыка). */
public class RunActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Linux lx = new Linux(this);
            if (!lx.installed()) { Toast.makeText(this, "Сначала установите Debian в «Пингвине»", Toast.LENGTH_LONG).show(); startActivity(new Intent(this, MainActivity.class)); finish(); return; }
            Bridge.start(this);
            Intent in = getIntent();
            Properties p = new Properties();
            String cfg = in.getStringExtra("config");
            if (cfg != null) p.load(new StringReader(cfg));
            String name = in.getStringExtra("name");
            if (name == null) name = p.getProperty("name");
            String gui = in.getStringExtra("gui");
            if (gui == null) gui = p.getProperty("gui", "0");
            if (name == null || !name.matches("[A-Za-z0-9._+-]+")) throw new IllegalArgumentException("bad name");
            String args = "";
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) args = " " + Bridge.q(Bridge.importUri(this, u));
            String text = in.getStringExtra(Intent.EXTRA_TEXT);
            if (u == null && text != null) args = " " + Bridge.q(text);
            if ("1".equals(gui)) startActivity(new Intent(this, DisplayActivity.class).putExtra("prog", name + args));
            else startActivity(new Intent(this, TerminalActivity.class).putExtra("cmd", "pingvin-run " + name + args));
        } catch (Exception e) { Toast.makeText(this, "Ошибка: " + e, Toast.LENGTH_LONG).show(); }
        finish();
    }
}
