package ru.ivan.pingvin;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import java.util.List;

/** Терминал на основе Termux terminal-view/terminal-emulator (Apache-2.0). */
public class TerminalActivity extends Activity implements TerminalSessionClient, TerminalViewClient {
    TerminalView view; TerminalSession session; int textSize = 28;
    boolean ctrl; boolean keep;
    static TerminalSession background;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); Crash.install(this);
        Linux lx = new Linux(this);
        String cmd = getIntent().getStringExtra("cmd");
        if (cmd == null) cmd = "cd /root; exec bash -l";
        else cmd = cmd + "; echo; echo '[Процесс завершён, Enter — оболочка]'; read x; exec bash -l";
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        view = new TerminalView(this, null);
        view.setTerminalViewClient(this);
        view.setTextSize(textSize);
        view.setTypeface(Typeface.MONOSPACE);
        view.setFocusableInTouchMode(true);
        root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout keys = new LinearLayout(this);
        String[][] ks = {{"✕", "close"}, {"^C", "\003"}, {"ESC", "\033"}, {"TAB", "\t"}, {"CTRL", null}, {"↑", "\033[A"}, {"↓", "\033[B"}, {"←", "\033[D"}, {"→", "\033[C"}, {"|", "|"}, {"/", "/"}, {"-", "-"}, {"📋", "menu"}};
        for (String[] k : ks) {
            Button bt = new Button(this); bt.setText(k[0]); bt.setAllCaps(false); bt.setMinWidth(0); bt.setPadding(4, 0, 4, 0);
            final String seq = k[1];
            bt.setOnClickListener(v -> {
                if (seq == null) { ctrl = !ctrl; bt.setPressed(ctrl); }
                else if (seq.equals("close")) askClose();
                else if (seq.equals("menu")) logMenu(null);
                else if (session != null) session.write(seq.getBytes(), 0, seq.getBytes().length);
                view.requestFocus();
            });
            keys.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(keys);
        setContentView(root);
        if (getIntent().getStringExtra("cmd") == null && background != null && background.isRunning()) {
            session = background; background = null; session.updateTerminalSessionClient(this);
        } else {
            List<String> c = lx.command(cmd, true);
            session = new TerminalSession(lx.prootPath(), "/", c.toArray(new String[0]), lx.envArray(), 3000, this);
        }
        view.attachSession(session);
        view.requestFocus();
    }

    @Override protected void onDestroy() { super.onDestroy(); if (session != null && isFinishing() && !keep) session.finishIfRunning(); }

    @Override public void onBackPressed() { askClose(); }

    void askClose() {
        if (session == null || !session.isRunning()) { finish(); return; }
        new android.app.AlertDialog.Builder(this).setTitle("Терминал")
            .setItems(new String[]{"Закрыть терминал (убить процесс)", "Оставить работать в фоне", "Отмена"}, (d, w) -> {
                if (w == 0) { session.finishIfRunning(); finish(); }
                else if (w == 1) {
                    if (background != null && background != session) background.finishIfRunning();
                    background = session; keep = true; finish();
                    android.widget.Toast.makeText(this, "Работает в фоне. Кнопка «Терминал» вернёт его.", android.widget.Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    // TerminalSessionClient
    @Override public void onTextChanged(TerminalSession s) { view.onScreenUpdated(); }
    @Override public void onTitleChanged(TerminalSession s) { setTitle(s.getTitle()); }
    @Override public void onSessionFinished(TerminalSession s) { if (s == background) background = null; if (!isFinishing()) finish(); }
    @Override public void onCopyTextToClipboard(TerminalSession s, String t) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("terminal", t)); }
    @Override public void onPasteTextFromClipboard(TerminalSession s) {
        ClipData c = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip();
        if (c != null && c.getItemCount() > 0) { CharSequence t = c.getItemAt(0).coerceToText(this); if (t != null) s.getEmulator().paste(t.toString()); } }
    @Override public void onBell(TerminalSession s) {}
    @Override public void onColorsChanged(TerminalSession s) {}
    @Override public void onTerminalCursorStateChange(boolean st) {}
    @Override public Integer getTerminalCursorStyle() { return 0; }
    // TerminalViewClient
    @Override public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) { textSize = Math.max(12, Math.min(72, Math.round(textSize * scale))); view.setTextSize(textSize); return 1f; }
        return scale; }
    @Override public void onSingleTapUp(MotionEvent e) {
        view.requestFocus();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(view, 0); }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean m) {}
    @Override public boolean onKeyDown(int k, KeyEvent e, TerminalSession s) { return false; }
    @Override public boolean onKeyUp(int k, KeyEvent e) { return false; }
    @Override public boolean onLongPress(MotionEvent e) { logMenu(MotionEvent.obtain(e)); return true; }
    String allText() { try { return session.getEmulator().getScreen().getTranscriptText(); } catch (Exception ex) { return ""; } }
    void logMenu(MotionEvent e) {
        new android.app.AlertDialog.Builder(this).setTitle("Вывод терминала")
            .setItems(new String[]{"Копировать всё", "Поделиться", "Выделить текст", "Вставить"}, (d, w) -> {
                if (w == 0) LogShare.copy(this, allText());
                else if (w == 1) LogShare.share(this, allText());
                else if (w == 2) { try { view.startTextSelectionMode(e != null ? e : MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, view.getWidth() / 2f, view.getHeight() / 2f, 0)); } catch (Throwable t) { android.widget.Toast.makeText(this, "Выделение недоступно: " + t, android.widget.Toast.LENGTH_SHORT).show(); } }
                else if (w == 3 && session != null) onPasteTextFromClipboard(session);
            }).show();
    }
    @Override public boolean readControlKey() { return ctrl; }
    @Override public boolean readAltKey() { return false; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int cp, boolean c, TerminalSession s) { ctrl = false; return false; }
    @Override public void onEmulatorSet() {}
    @Override public void logError(String t, String m) { android.util.Log.e(t, m); }
    @Override public void logWarn(String t, String m) {}
    @Override public void logInfo(String t, String m) {}
    @Override public void logDebug(String t, String m) {}
    @Override public void logVerbose(String t, String m) {}
    @Override public void logStackTraceWithMessage(String t, String m, Exception e) { android.util.Log.e(t, m, e); }
    @Override public void logStackTrace(String t, Exception e) { android.util.Log.e(t, "err", e); }
}
