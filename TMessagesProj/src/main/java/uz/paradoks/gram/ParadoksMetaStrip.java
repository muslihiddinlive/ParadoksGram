package uz.paradoks.gram;

import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Removes EXIF/XMP/IPTC (JPEG) and text/time/exif chunks (PNG) from pictures before they are sent.
 * The picture is not re-encoded; JPEG orientation is kept. Works on a copy in the cache.
 */
public final class ParadoksMetaStrip {

    private static final long MAX_SIZE = 64L * 1024 * 1024;

    private ParadoksMetaStrip() {
    }

    /** Returns a cleaned copy of the file (same name) or the original path if nothing to remove. */
    public static String clean(String path) {
        try {
            if (path == null || !ParadoksConfig.stripMetadata()) {
                return path;
            }
            File file = new File(path);
            long length = file.length();
            if (length < 12 || length > MAX_SIZE) {
                return path;
            }
            byte[] data = new byte[(int) length];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) {
                        return path;
                    }
                    off += n;
                }
            }
            byte[] result = strip(data);
            if (result == null) {
                return path;
            }
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "meta_clean/" + UUID.randomUUID());
            if (!dir.mkdirs()) {
                return path;
            }
            File out = new File(dir, file.getName());
            try (FileOutputStream os = new FileOutputStream(out)) {
                os.write(result);
            }
            return out.getAbsolutePath();
        } catch (Throwable ignore) {
            return path;
        }
    }

    /** Returns cleaned bytes or null if unsupported / nothing to remove. */
    public static byte[] strip(byte[] d) {
        if (d.length >= 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return stripJpeg(d);
        }
        if (d.length >= 20 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return stripPng(d);
        }
        return null;
    }

    private static int orientation(byte[] d, int start, int len) {
        // start points to "Exif\0\0".
        if (len < 20 || d[start] != 'E' || d[start + 1] != 'x' || d[start + 2] != 'i' || d[start + 3] != 'f') {
            return 1;
        }
        int t = start + 6;
        int n = len - 6;
        boolean big = d[t] == 'M';
        if (!big && d[t] != 'I') {
            return 1;
        }
        int ifd = r32(d, t + 4, big);
        if (ifd < 8 || ifd + 2 > n) {
            return 1;
        }
        int count = r16(d, t + ifd, big);
        for (int i = 0; i < count; i++) {
            int e = ifd + 2 + i * 12;
            if (e + 12 > n) {
                break;
            }
            if (r16(d, t + e, big) == 0x0112) {
                int v = r16(d, t + e + 8, big);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int r16(byte[] d, int o, boolean big) {
        int a = d[o] & 0xFF, b = d[o + 1] & 0xFF;
        return big ? (a << 8) | b : (b << 8) | a;
    }

    private static int r32(byte[] d, int o, boolean big) {
        if (o + 4 > d.length) {
            return Integer.MAX_VALUE;
        }
        int a = d[o] & 0xFF, b = d[o + 1] & 0xFF, c = d[o + 2] & 0xFF, e = d[o + 3] & 0xFF;
        return big ? (a << 24) | (b << 16) | (c << 8) | e : (e << 24) | (c << 16) | (b << 8) | a;
    }

    private static byte[] stripJpeg(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, 2);
        boolean changed = false;
        int pos = 2;
        while (pos + 4 <= d.length) {
            if ((d[pos] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = d[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                out.write(d, pos, 2);
                pos += 2;
                continue;
            }
            int length = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            if (length < 2 || pos + 2 + length > d.length) {
                return null;
            }
            int total = 2 + length;
            boolean drop = false;
            if (marker == 0xE1) {
                drop = true;
                changed = true;
                int o = orientation(d, pos + 4, length - 2);
                boolean isExif = length >= 8 && d[pos + 4] == 'E' && d[pos + 5] == 'x' && d[pos + 6] == 'i' && d[pos + 7] == 'f';
                if (o != 1 && isExif) {
                    byte[] body = {
                            'E', 'x', 'i', 'f', 0, 0,
                            'M', 'M', 0, 42, 0, 0, 0, 8,
                            0, 1,
                            0x01, 0x12, 0, 3, 0, 0, 0, 1,
                            0, (byte) o, 0, 0,
                            0, 0, 0, 0};
                    int len = body.length + 2;
                    out.write(0xFF);
                    out.write(0xE1);
                    out.write(len >> 8);
                    out.write(len & 0xFF);
                    out.write(body, 0, body.length);
                }
            } else if (marker == 0xED || marker == 0xFE || marker == 0xEB || marker == 0xEC) {
                drop = true;
                changed = true;
            } else if (marker == 0xE2 && length > 14 && d[pos + 4] == 'M' && d[pos + 5] == 'P' && d[pos + 6] == 'F') {
                drop = true;
                changed = true;
            }
            if (!drop) {
                out.write(d, pos, total);
            }
            pos += total;
        }
        if (!changed) {
            return null;
        }
        out.write(d, pos, d.length - pos);
        return out.toByteArray();
    }

    private static byte[] stripPng(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, 8);
        boolean changed = false;
        int pos = 8;
        while (pos + 12 <= d.length) {
            long length = ((long) (d[pos] & 0xFF) << 24) | ((d[pos + 1] & 0xFF) << 16) | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            long total = length + 12;
            if (pos + total > d.length) {
                return null;
            }
            String type = new String(d, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            boolean drop = type.equals("tEXt") || type.equals("zTXt") || type.equals("iTXt") || type.equals("eXIf") || type.equals("tIME");
            if (drop) {
                changed = true;
            } else {
                out.write(d, pos, (int) total);
            }
            pos += (int) total;
            if (type.equals("IEND")) {
                break;
            }
        }
        return changed ? out.toByteArray() : null;
    }
}
