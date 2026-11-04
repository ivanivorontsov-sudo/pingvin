package ru.ivan.pingvin;

import android.content.Context;
import android.graphics.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;

/** Native VNC view: draws RfbClient framebuffer scaled to fit; touch = mouse, soft keyboard = keys. */
public class VncView extends View {
    RfbClient rfb; Bitmap bmp; final Matrix m = new Matrix(), inv = new Matrix(); final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
    float zoom = 1, panX, panY; boolean multi; ScaleGestureDetector sg; float lastFx, lastFy;
    int mask; float downX, downY; long downT; boolean dragging, longFired;
    final Runnable longPress = () -> { longFired = true; int[] q = fbPt(downX, downY); rfb.pointer(q[0], q[1], 4); rfb.pointer(q[0], q[1], 0); };

    public VncView(Context c) { super(c); setFocusable(true); setFocusableInTouchMode(true); setBackgroundColor(Color.BLACK);
        sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float nz = Math.max(1f, Math.min(8f, zoom * d.getScaleFactor())), f = nz / zoom;
                panX = d.getFocusX() - (d.getFocusX() - panX) * f; panY = d.getFocusY() - (d.getFocusY() - panY) * f; zoom = nz; invalidate(); return true; } });
    }
    void resetZoom() { zoom = 1; panX = panY = 0; invalidate(); }

    void attach(RfbClient r) { rfb = r; }
    void resized() { post(() -> { bmp = null; invalidate(); }); }
    void updated() { postInvalidate(); }

    @Override protected void onDraw(Canvas c) {
        if (rfb == null || rfb.fb == null) return;
        int w = rfb.width, h = rfb.height;
        if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] f = rfb.fb; if (f.length < w * h) return;
        bmp.setPixels(f, 0, w, 0, 0, w, h);
        float s = Math.min(getWidth() / (float) w, getHeight() / (float) h);
        m.reset(); m.postScale(s, s); m.postTranslate((getWidth() - w * s) / 2, (getHeight() - h * s) / 2); m.postScale(zoom, zoom); m.postTranslate(panX, panY); m.invert(inv);
        c.drawBitmap(bmp, m, p);
    }

    int[] fbPt(float x, float y) { float[] a = {x, y}; inv.mapPoints(a); return new int[]{(int) a[0], (int) a[1]}; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (rfb == null) return false;
        sg.onTouchEvent(e);
        if (e.getPointerCount() > 1 || multi) {  // два пальца: масштаб и сдвиг, мышь не трогаем
            float fx = 0, fy = 0; int n = e.getPointerCount(); for (int i = 0; i < n; i++) { fx += e.getX(i); fy += e.getY(i); } fx /= n; fy /= n;
            int a = e.getActionMasked();
            if (!multi || a == MotionEvent.ACTION_POINTER_DOWN || a == MotionEvent.ACTION_POINTER_UP) { if (!multi) { removeCallbacks(longPress); if (dragging) { int[] d = fbPt(downX, downY); rfb.pointer(d[0], d[1], 0); } } multi = true; lastFx = fx; lastFy = fy; }
            else if (a == MotionEvent.ACTION_MOVE && n > 1) { panX += fx - lastFx; panY += fy - lastFy; lastFx = fx; lastFy = fy; if (zoom <= 1f) { panX = panY = 0; } invalidate(); }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) multi = false;
            return true;
        }
        int[] q = fbPt(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); downT = e.getEventTime(); dragging = false; longFired = false;
                rfb.pointer(q[0], q[1], 0); postDelayed(longPress, 600); break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.hypot(e.getX() - downX, e.getY() - downY) > 20) { dragging = true; removeCallbacks(longPress); int[] d = fbPt(downX, downY); rfb.pointer(d[0], d[1], 1); }
                if (dragging) rfb.pointer(q[0], q[1], 1); break;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (dragging) rfb.pointer(q[0], q[1], 0);
                else if (!longFired) { rfb.pointer(q[0], q[1], 1); rfb.pointer(q[0], q[1], 0); }
                break;
            case MotionEvent.ACTION_CANCEL: removeCallbacks(longPress); rfb.pointer(q[0], q[1], 0); break;
        }
        return true;
    }

    void showKeyboard() { requestFocus(); ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(this, InputMethodManager.SHOW_FORCED); }

    void tap(int keysym) { if (rfb != null) { rfb.key(keysym, true); rfb.key(keysym, false); } }

    static int keysym(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return 0xff08; case KeyEvent.KEYCODE_ENTER: return 0xff0d; case KeyEvent.KEYCODE_TAB: return 0xff09;
            case KeyEvent.KEYCODE_ESCAPE: return 0xff1b; case KeyEvent.KEYCODE_DPAD_LEFT: return 0xff51; case KeyEvent.KEYCODE_DPAD_UP: return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xff53; case KeyEvent.KEYCODE_DPAD_DOWN: return 0xff54; case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;
            case KeyEvent.KEYCODE_SPACE: return 0x20;
        }
        return 0;
    }
    static int charSym(int cp) { return cp < 0x100 ? cp : 0x01000000 | cp; }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (rfb == null || k == KeyEvent.KEYCODE_BACK) return super.onKeyDown(k, e);
        int s = keysym(k); if (s == 0) { int u = e.getUnicodeChar(); if (u > 0) s = charSym(u); }
        if (s != 0) { tap(s); return true; }
        return super.onKeyDown(k, e);
    }

    @Override public boolean onCheckIsTextEditor() { return true; }
    @Override public InputConnection onCreateInputConnection(EditorInfo o) {
        o.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        o.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override public boolean commitText(CharSequence t, int pos) {
                t.codePoints().forEach(cp -> tap(cp == '\n' ? 0xff0d : charSym(cp))); return true; }
            @Override public boolean deleteSurroundingText(int before, int after) { for (int i = 0; i < Math.max(1, before); i++) tap(0xff08); return true; }
            @Override public boolean sendKeyEvent(KeyEvent e) { if (e.getAction() == KeyEvent.ACTION_DOWN) VncView.this.onKeyDown(e.getKeyCode(), e); return true; }
        };
    }
}
