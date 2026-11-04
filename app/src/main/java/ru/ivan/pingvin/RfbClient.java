package ru.ivan.pingvin;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Minimal RFB 3.8 client (VncAuth, Raw + CopyRect + DesktopSize), no Android dependencies. */
public class RfbClient {
    public interface Listener { void onResize(int w, int h); void onUpdate(int x, int y, int w, int h); void onClosed(Exception e); }
    public volatile int width, height; public volatile int[] fb;
    Socket sock; DataInputStream in; DataOutputStream out; Listener l; volatile boolean running;

    public void connect(String host, int port, String password, Listener l) throws Exception {
        this.l = l;
        sock = new Socket(); sock.connect(new InetSocketAddress(host, port), 5000); sock.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 1 << 16));
        out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
        byte[] ver = new byte[12]; in.readFully(ver);
        if (!new String(ver, StandardCharsets.US_ASCII).startsWith("RFB ")) throw new IOException("не RFB-сервер");
        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
        int n = in.readUnsignedByte();
        if (n == 0) throw new IOException("сервер отказал: " + reason());
        boolean vnc = false, none = false;
        for (int i = 0; i < n; i++) { int t = in.readUnsignedByte(); if (t == 2) vnc = true; if (t == 1) none = true; }
        if (vnc && password != null) {
            out.writeByte(2); out.flush();
            byte[] ch = new byte[16]; in.readFully(ch);
            out.write(des(password, ch)); out.flush();
        } else if (none) { out.writeByte(1); out.flush(); }
        else throw new IOException("нет подходящего типа защиты");
        if (in.readInt() != 0) throw new IOException("ошибка авторизации VNC: " + reason());
        out.writeByte(1); out.flush();
        width = in.readUnsignedShort(); height = in.readUnsignedShort();
        in.skipBytes(16); int nl = in.readInt(); in.skipBytes(nl);
        fb = new int[width * height];
        l.onResize(width, height);
        out.writeByte(0); out.write(new byte[3]);
        out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1);
        out.writeShort(255); out.writeShort(255); out.writeShort(255);
        out.writeByte(16); out.writeByte(8); out.writeByte(0); out.write(new byte[3]);
        out.writeByte(2); out.writeByte(0); out.writeShort(3); out.writeInt(1); out.writeInt(0); out.writeInt(-223);
        request(false); out.flush();
        running = true;
    }

    String reason() { try { int len = in.readInt(); byte[] b = new byte[len]; in.readFully(b); return new String(b, StandardCharsets.UTF_8); } catch (Exception e) { return "?"; } }

    static byte[] des(String pass, byte[] ch) throws Exception {
        byte[] k = new byte[8]; byte[] p = pass.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 8 && i < p.length; i++) k[i] = (byte) (Integer.reverse(p[i] & 0xff) >>> 24);
        Cipher c = Cipher.getInstance("DES/ECB/NoPadding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "DES"));
        return c.doFinal(ch);
    }

    synchronized void request(boolean inc) throws IOException {
        out.writeByte(3); out.writeByte(inc ? 1 : 0); out.writeShort(0); out.writeShort(0); out.writeShort(width); out.writeShort(height);
    }

    /** Blocking message loop; call on a background thread. */
    public void loop() {
        byte[] row = new byte[0];
        try {
            while (running) {
                int t = in.readUnsignedByte();
                if (t == 0) {
                    in.readUnsignedByte(); int nr = in.readUnsignedShort();
                    int ux0 = Integer.MAX_VALUE, uy0 = Integer.MAX_VALUE, ux1 = 0, uy1 = 0;
                    for (int r = 0; r < nr; r++) {
                        int x = in.readUnsignedShort(), y = in.readUnsignedShort(), w = in.readUnsignedShort(), h = in.readUnsignedShort(), enc = in.readInt();
                        if (enc == 0) {
                            if (row.length < w * 4) row = new byte[w * 4];
                            int[] f = fb;
                            for (int j = 0; j < h; j++) {
                                in.readFully(row, 0, w * 4);
                                int o = (y + j) * width + x;
                                for (int i = 0, b = 0; i < w; i++, b += 4)
                                    f[o + i] = 0xff000000 | ((row[b + 2] & 0xff) << 16) | ((row[b + 1] & 0xff) << 8) | (row[b] & 0xff);
                            }
                        } else if (enc == 1) {
                            int sx = in.readUnsignedShort(), sy = in.readUnsignedShort();
                            int[] f = fb; int[] tmp = new int[w * h];
                            for (int j = 0; j < h; j++) System.arraycopy(f, (sy + j) * width + sx, tmp, j * w, w);
                            for (int j = 0; j < h; j++) System.arraycopy(tmp, j * w, f, (y + j) * width + x, w);
                        } else if (enc == -223) {
                            width = w; height = h; fb = new int[w * h]; l.onResize(w, h); x = 0; y = 0;
                        } else throw new IOException("неизвестная кодировка " + enc);
                        ux0 = Math.min(ux0, x); uy0 = Math.min(uy0, y); ux1 = Math.max(ux1, x + w); uy1 = Math.max(uy1, y + h);
                    }
                    if (nr > 0) l.onUpdate(ux0, uy0, ux1 - ux0, uy1 - uy0);
                    synchronized (this) { request(true); out.flush(); }
                } else if (t == 1) { in.readUnsignedByte(); in.readUnsignedShort(); int nc = in.readUnsignedShort(); in.skipBytes(nc * 6); }
                else if (t == 2) { }
                else if (t == 3) { in.skipBytes(3); int len = in.readInt(); in.skipBytes(len); }
                else throw new IOException("неизвестное сообщение " + t);
            }
            l.onClosed(null);
        } catch (Exception e) { running = false; l.onClosed(e); }
    }

    /** Все отправки ввода — в одном фоновом потоке (на Android запись в сокет из UI-потока = NetworkOnMainThreadException). */
    final java.util.concurrent.ExecutorService tx = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "rfb-tx"); t.setDaemon(true); return t; });
    void send(IoAction a) {
        if (!running) return;
        try { tx.execute(() -> { try { synchronized (this) { a.run(); out.flush(); } } catch (Throwable e) { if (running) { running = false; try { sock.close(); } catch (Exception ignored) {} } } }); }
        catch (Throwable ignored) {}
    }
    interface IoAction { void run() throws IOException; }
    static int clamp(int v, int max) { return Math.max(0, Math.min(Math.max(0, max - 1), v)); }
    public void pointer(int x, int y, int mask) {
        final int cx = clamp(x, width), cy = clamp(y, height), m = mask & 0xff;
        send(() -> { out.writeByte(5); out.writeByte(m); out.writeShort(cx); out.writeShort(cy); });
    }
    public void key(int keysym, boolean down) {
        send(() -> { out.writeByte(4); out.writeByte(down ? 1 : 0); out.writeShort(0); out.writeInt(keysym); });
    }
    public void close() { running = false; tx.shutdownNow(); try { sock.close(); } catch (Exception ignored) {} }
}
