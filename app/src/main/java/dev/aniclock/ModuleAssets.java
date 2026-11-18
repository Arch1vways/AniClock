package dev.aniclock;

import android.content.res.AssetManager;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The digit images shipped inside the module APK (assets/digits/{animated,static}/N.gif).
 * System UI reads them straight from the APK file, so no provider, IPC or app process is involved.
 */
final class ModuleAssets {
    static final long MAX_FILE = 4L * 1024 * 1024;
    private static final String DIR = "digits/";

    private ModuleAssets() { }

    /** Settings app: its own assets. */
    static DigitSet.Source of(AssetManager assets) {
        return (dir, name) -> {
            if (!valid(dir, name)) return null;
            try (InputStream in = assets.open(DIR + dir + "/" + name)) {
                return readAll(in);
            } catch (IOException e) {
                return null;
            }
        };
    }

    /** System UI: the module APK path given by LSPosed. */
    static DigitSet.Source ofApk(String apkPath) {
        return (dir, name) -> {
            if (apkPath == null || !valid(dir, name)) return null;
            try (ZipFile zip = new ZipFile(apkPath)) {
                ZipEntry e = zip.getEntry("assets/" + DIR + dir + "/" + name);
                if (e == null) return null;
                try (InputStream in = zip.getInputStream(e)) {
                    return readAll(in);
                }
            } catch (IOException | RuntimeException e) {
                return null;
            }
        };
    }

    private static boolean valid(String dir, String name) {
        return Digits.isValidRelativePath(dir + "/" + name);
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_FILE) throw new IOException("file too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
