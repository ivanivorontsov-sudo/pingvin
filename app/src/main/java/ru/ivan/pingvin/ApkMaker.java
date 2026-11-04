package ru.ivan.pingvin;

import com.android.apksig.ApkSigner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/**
 * Перепаковка шаблонного APK-ярлыка в отдельное приложение для Linux-программы:
 * меняет имя пакета и подпись (в бинарном AndroidManifest), иконку, конфигурацию, затем подписывает (v1+v2+v3).
 * Чистая Java — работает и на телефоне, и на ПК (для тестов).
 */
public class ApkMaker {
    public static final String PKG_PLACEHOLDER = "ru.ivan.pingvin.app.zzzzzzzzzzzzzzzz";
    public static final String LABEL_PREFIX = "PINGVIN_LABEL_PLACEHOLDER";

    public static String packageFor(String id) {
        String h = Integer.toHexString(id.hashCode()) + Integer.toHexString(new StringBuilder(id).reverse().toString().hashCode());
        StringBuilder s = new StringBuilder();
        for (char c : h.toCharArray()) s.append(Character.isDigit(c) ? (char) ('g' + (c - '0')) : c); // только буквы
        while (s.length() < 16) s.append('a');
        return "ru.ivan.pingvin.app." + s.substring(0, 16);
    }

    public static byte[] patchManifest(byte[] axml, String pkg, String label) {
        if (pkg.length() != PKG_PLACEHOLDER.length()) throw new IllegalArgumentException("pkg length");
        byte[] d = axml.clone();
        int poolStart = 8;
        int strCount = le32(d, poolStart + 8);
        int flags = le32(d, poolStart + 16);
        int stringsStart = poolStart + le32(d, poolStart + 20);
        if ((flags & 0x100) != 0) throw new IllegalStateException("UTF-8 pool not supported");
        boolean p = false, l = false;
        for (int i = 0; i < strCount; i++) {
            int off = stringsStart + le32(d, poolStart + 28 + i * 4);
            int len = le16(d, off);
            String s = new String(d, off + 2, len * 2, StandardCharsets.UTF_16LE);
            String repl = null;
            if (s.equals(PKG_PLACEHOLDER)) { repl = pkg; p = true; }
            else if (s.startsWith(LABEL_PREFIX)) {
                repl = label.length() > len ? label.substring(0, len) : label; l = true;
            }
            if (repl == null) continue;
            Arrays.fill(d, off + 2, off + 2 + len * 2 + 2, (byte) 0);
            d[off] = (byte) repl.length(); d[off + 1] = (byte) (repl.length() >> 8);
            byte[] b = repl.getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(b, 0, d, off + 2, b.length);
        }
        if (!p || !l) throw new IllegalStateException("placeholders not found p=" + p + " l=" + l);
        return d;
    }

    /** @param iconPng PNG иконки (или null) */
    public static void build(InputStream template, File outUnsigned, String pkg, String label, byte[] iconPng, String config) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(template); ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outUnsigned))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (n.startsWith("META-INF/")) continue;
                byte[] data = readAll(zin);
                if (n.equals("AndroidManifest.xml")) data = patchManifest(data, pkg, label);
                else if (n.equals("assets/pingvin_app.properties")) data = config.getBytes(StandardCharsets.UTF_8);
                else if (n.startsWith("res/") && n.endsWith(".png") && iconPng != null) data = iconPng;
                ZipEntry o = new ZipEntry(n);
                if (n.equals("resources.arsc") || n.endsWith(".png")) {
                    o.setMethod(ZipEntry.STORED); o.setSize(data.length); o.setCompressedSize(data.length);
                    CRC32 c = new CRC32(); c.update(data); o.setCrc(c.getValue());
                }
                zout.putNextEntry(o); zout.write(data); zout.closeEntry();
            }
        }
    }

    public static void sign(File in, File out, PrivateKey key, X509Certificate cert) throws Exception {
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("pingvin", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc)).setInputApk(in).setOutputApk(out)
                .setMinSdkVersion(26).setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .build().sign();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int r;
        while ((r = in.read(b)) > 0) o.write(b, 0, r);
        return o.toByteArray();
    }
    static int le32(byte[] d, int o) { return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff) << 16 | (d[o + 3] & 0xff) << 24; }
    static int le16(byte[] d, int o) { return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8; }
}
