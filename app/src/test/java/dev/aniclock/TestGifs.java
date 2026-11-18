package dev.aniclock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small generated GIFs for tests. */
final class TestGifs {
    private TestGifs() { }

    /**
     * Single-colour GIF89a (unit tests have no image encoder). LZW with a clear code every two pixels keeps the
     * code width at 3 bits, which every decoder accepts.
     */
    static byte[] gif(int w, int h, int rgb) {
        return gif(w, h, rgb, 0, h);
    }

    static byte[] gif(int w, int h, int rgb, int visibleTop, int visibleBottom) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "GIF89a");
        le16(out, w);
        le16(out, h);
        out.write(0x80);                       // global colour table, 2 entries
        out.write(0);
        out.write(0);
        out.write(0); out.write(0); out.write(0);
        out.write((rgb >> 16) & 0xFF); out.write((rgb >> 8) & 0xFF); out.write(rgb & 0xFF);
        out.write(0x21); out.write(0xF9); out.write(4); out.write(1); // transparent palette entry 0
        le16(out, 0); out.write(0); out.write(0);
        out.write(0x2C);                       // image descriptor
        le16(out, 0); le16(out, 0); le16(out, w); le16(out, h);
        out.write(0);
        out.write(2);                          // LZW minimum code size
        BitWriter bits = new BitWriter();
        int pixels = w * h;
        for (int i = 0; i < pixels; i++) {
            if (i % 2 == 0) bits.write(4, 3);  // clear
            int y = i / w;
            bits.write(y >= visibleTop && y < visibleBottom ? 1 : 0, 3);
        }
        bits.write(5, 3);                      // end of information
        byte[] data = bits.toByteArray();
        for (int off = 0; off < data.length; off += 255) {
            int n = Math.min(255, data.length - off);
            out.write(n);
            out.write(data, off, n);
        }
        out.write(0);
        out.write(0x3B);
        return out.toByteArray();
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int acc, count;

        void write(int code, int width) {
            acc |= code << count;
            count += width;
            while (count >= 8) {
                out.write(acc & 0xFF);
                acc >>>= 8;
                count -= 8;
            }
        }

        byte[] toByteArray() {
            if (count > 0) {
                out.write(acc & 0xFF);
                count = 0;
                acc = 0;
            }
            return out.toByteArray();
        }
    }

    private static void le16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        for (int i = 0; i < s.length(); i++) out.write(s.charAt(i));
    }

    /** A stand-in module APK: a ZIP with assets/digits/animated/*.gif, like the real APK. */
    static java.io.File fakeApk(java.io.File dir) throws IOException {
        return fakeApk(dir, false);
    }

    static java.io.File fakeApk(java.io.File dir, boolean padded) throws IOException {
        java.io.File f = new java.io.File(dir, "module.apk");
        try (ZipOutputStream zip = new ZipOutputStream(new java.io.FileOutputStream(f))) {
            put(zip, "classes.dex", new byte[]{0});
            for (String n : Digits.NAMES) {
                put(zip, "assets/digits/animated/" + n + ".gif",
                        gif(n.equals("colon") ? 46 : 90, 200, 0x3366CC, padded ? 60 : 0, padded ? 180 : 200));
            }
        }
        return f;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
