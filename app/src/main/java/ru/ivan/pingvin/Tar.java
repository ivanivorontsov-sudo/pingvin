package ru.ivan.pingvin;

import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Минимальный распаковщик tar (ustar/GNU/pax) с симлинками и хардлинками. */
public class Tar {
    public static void extract(InputStream in, File dest, int strip, Linux.Log log) throws Exception {
        byte[] h = new byte[512];
        String longName = null, longLink = null;
        int count = 0;
        String destPath = dest.getCanonicalPath();
        while (true) {
            if (!readFully(in, h)) break;
            boolean zero = true; for (byte b : h) if (b != 0) { zero = false; break; }
            if (zero) break;
            String name = str(h, 0, 100);
            String prefix = str(h, 345, 155);
            if (!prefix.isEmpty() && h[257] == 'u') name = prefix + "/" + name;
            int mode = (int) oct(h, 100, 8);
            long size = oct(h, 124, 12);
            char type = (char) h[156];
            String link = str(h, 157, 100);
            if (type == 'L' || type == 'K' || type == 'x') {
                byte[] data = new byte[(int) size]; readFully(in, data); skip(in, pad(size));
                String s = new String(data, StandardCharsets.UTF_8);
                if (type == 'L') longName = s.replace("\0", "");
                else if (type == 'K') longLink = s.replace("\0", "");
                else for (String line : s.split("\n")) {
                    int sp = line.indexOf(' '), eq = line.indexOf('=');
                    if (sp < 0 || eq < 0) continue;
                    String k = line.substring(sp + 1, eq), v = line.substring(eq + 1);
                    if (k.equals("path")) longName = v; else if (k.equals("linkpath")) longLink = v;
                }
                continue;
            }
            if (longName != null) { name = longName; longName = null; }
            if (longLink != null) { link = longLink; longLink = null; }
            String rel = stripPath(name, strip);
            if (rel == null || rel.isEmpty()) { skip(in, size + pad(size)); continue; }
            File f = new File(dest, rel);
            if (!f.getAbsolutePath().startsWith(destPath)) throw new IOException("bad path " + name);
            File parent = f.getParentFile(); if (parent != null) parent.mkdirs();
            switch (type) {
                case '5': f.mkdirs(); f.setExecutable(true, false); f.setReadable(true, false); f.setWritable(true, true); break;
                case '2':
                    if (f.exists() || Linux.isLink(f)) f.delete();
                    try { Os.symlink(link, f.getAbsolutePath()); } catch (Exception e) { }
                    break;
                case '1': {
                    String lr = stripPath(link, strip);
                    File src = new File(dest, lr == null ? link : lr);
                    if (f.exists()) f.delete();
                    if (src.exists()) {
                        try { Os.link(src.getAbsolutePath(), f.getAbsolutePath()); }
                        catch (Exception e) { copy(src, f); }
                        f.setExecutable(src.canExecute(), false);
                    }
                    break;
                }
                case '0': case '\0': case '7': {
                    if (f.exists() || Linux.isLink(f)) f.delete();
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f), 65536)) {
                        byte[] b = new byte[65536]; long left = size;
                        while (left > 0) { int r = in.read(b, 0, (int) Math.min(b.length, left)); if (r < 0) throw new EOFException(); o.write(b, 0, r); left -= r; }
                    }
                    skip(in, pad(size));
                    try { Os.chmod(f.getAbsolutePath(), mode & 0777 | 0200); } catch (Exception e) { f.setExecutable((mode & 0111) != 0, false); }
                    if (++count % 2000 == 0 && log != null) log.line("  файлов: " + count);
                    continue;
                }
                default: break;
            }
            skip(in, size + pad(size));
        }
        if (log != null) log.line("  распаковано файлов: " + count);
    }
    static String stripPath(String n, int strip) {
        while (n.startsWith("./")) n = n.substring(2);
        for (int i = 0; i < strip; i++) { int s = n.indexOf('/'); if (s < 0) return null; n = n.substring(s + 1); }
        return n;
    }
    static long pad(long size) { return (512 - size % 512) % 512; }
    static String str(byte[] h, int o, int l) { int e = o; while (e < o + l && h[e] != 0) e++; return new String(h, o, e - o, StandardCharsets.UTF_8); }
    static long oct(byte[] h, int o, int l) {
        if ((h[o] & 0x80) != 0) { long v = 0; for (int i = o + 1; i < o + l; i++) v = v << 8 | (h[i] & 0xff); return v; }
        long v = 0; for (int i = o; i < o + l; i++) { byte b = h[i]; if (b >= '0' && b <= '7') v = v * 8 + (b - '0'); else if (v > 0 && (b == 0 || b == ' ')) break; }
        return v;
    }
    static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0; while (off < b.length) { int r = in.read(b, off, b.length - off); if (r < 0) return off > 0 && false; off += r; } return true;
    }
    static void skip(InputStream in, long n) throws IOException { byte[] b = new byte[8192]; while (n > 0) { int r = in.read(b, 0, (int) Math.min(b.length, n)); if (r < 0) return; n -= r; } }
    static void copy(File a, File b) throws IOException {
        try (InputStream i = new FileInputStream(a); OutputStream o = new FileOutputStream(b)) { byte[] x = new byte[65536]; int r; while ((r = i.read(x)) > 0) o.write(x, 0, r); }
    }
}
