package uz.paradoks.gram;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Mini-app developer tools: saves the public files of the open mini-app page (HTML, CSS, scripts, images) into a ZIP
 * with the links rewritten, so index.html opens offline. Plain requests: no cookies, no initData (the fragment is dropped).
 */
public final class ParadoksWebCopy {

    public interface Callback {
        void log(String line);

        void done(File zip);
    }

    private static final int MAX_FILES = 2000;
    private static final int MAX_FILE = 50 * 1024 * 1024; // one file is held in memory
    private static final long MAX_TOTAL = 1536L * 1024 * 1024; // 1.5 GB
    private static final Pattern TAG = Pattern.compile("<(script|link|img|source|video|audio)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("\\b(src|href|poster)\\s*=\\s*([\"'])(.*?)\\2", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REL = Pattern.compile("\\brel\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*([\"']?)([^\"')]+)\\1\\s*\\)|@import\\s+([\"'])([^\"']+)\\3");

    private static class Item {
        URL url;
        String local;
        boolean css;
    }

    private final Callback callback;
    private final URL root;
    private final Map<String, String> known = new LinkedHashMap<>();
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private int fileCount;
    private final ArrayList<String> failed = new ArrayList<>();
    private long total;

    private final boolean single;
    private final ArrayList<String> placeholderLocals = new ArrayList<>();
    private final Map<String, Integer> placeholderIndex = new LinkedHashMap<>();
    private final Map<String, File> savedFiles = new LinkedHashMap<>();
    private final Map<String, Boolean> savedCss = new LinkedHashMap<>();
    private final Map<String, String> originalUrls = new LinkedHashMap<>();
    private static final int INLINE_IN_CSS_MAX = 8 * 1024 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\uE000(\\d+)\uE001");

    private ParadoksWebCopy(URL root, Callback callback, boolean single) {
        this.root = root;
        this.callback = callback;
        this.single = single;
    }

    public static void start(String pageUrl, Callback callback) {
        start(pageUrl, false, callback);
    }

    /** single = one self-contained .html file (everything inlined as data: URIs), otherwise a ZIP. */
    public static void start(String pageUrl, boolean single, Callback callback) {
        final URL root;
        try {
            String clean = pageUrl;
            int hash = clean.indexOf('#');
            if (hash >= 0) {
                clean = clean.substring(0, hash);
            }
            root = new URL(clean);
            if (!root.getProtocol().equals("http") && !root.getProtocol().equals("https")) {
                throw new Exception("not http");
            }
        } catch (Exception e) {
            callback.log("Manzil noto'g'ri yoki mini-app ochilmagan.");
            AndroidUtilities.runOnUIThread(() -> callback.done(null));
            return;
        }
        new Thread(() -> new ParadoksWebCopy(root, callback, single).run(), "ParadoksWebCopy").start();
    }

    private void log(String line) {
        AndroidUtilities.runOnUIThread(() -> callback.log(line));
    }

    private void run() {
        if (single) {
            runSingle();
            return;
        }
        File zip = null;
        ZipOutputStream out = null;
        try {
            File dir = new File(ApplicationLoader.applicationContext.getExternalCacheDir(), "MiniAppCopies");
            dir.mkdirs();
            zip = new File(dir, root.getHost() + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".zip");
            out = new ZipOutputStream(new FileOutputStream(zip));
            log("Sahifa yuklanmoqda...");
            byte[] html = fetch(root);
            put(out, "index.html", rewriteHtml(new String(html, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            while (!queue.isEmpty() && total <= MAX_TOTAL) {
                Item item = queue.poll();
                log("Yuklanmoqda: " + item.url);
                try {
                    byte[] data = fetch(item.url);
                    if (item.css) {
                        data = rewriteCss(item.url, item.local, new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                    }
                    put(out, item.local, data);
                } catch (Exception e) {
                    failed.add(item.url + " (" + e.getMessage() + ")");
                }
            }
            StringBuilder info = new StringBuilder();
            info.append("ParadoksGram: mini-app sahifa nusxasi\n");
            info.append("Manzil: ").append(root.getProtocol()).append("://").append(root.getHost()).append(root.getPath()).append('\n');
            info.append("Fayllar: ").append(fileCount).append('\n');
            info.append("Yuklanmagan: ").append(failed.size()).append('\n');
            for (String line : failed) {
                info.append(line).append('\n');
            }
            if (!queue.isEmpty()) {
                info.append("1.5 GB limitga yetildi, qolgan fayllar saqlanmadi.\n");
            }
            info.append("\nEslatma: skriptlar ichidan keyin yuklanadigan fayllar va serverdagi (backend) kod saqlanmaydi.\n");
            put(out, "_info.txt", info.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            out = null;
            log("Tayyor: " + fileCount + " ta fayl saqlandi.");
        } catch (Exception e) {
            log("Sahifani yuklab bo'lmadi: " + e.getMessage());
            zip = null;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignore) {
                }
            }
        }
        final File result = zip;
        AndroidUtilities.runOnUIThread(() -> callback.done(result));
    }

    private void put(ZipOutputStream out, String name, byte[] data) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
        total += data.length;
        fileCount++;
    }

    private byte[] fetch(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) ParadoksGram-Copy");
        int code = connection.getResponseCode();
        if (code != 200) {
            throw new Exception("server javobi " + code);
        }
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_FILE) {
                    throw new Exception("juda katta");
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private void runSingle() {
        File result = null;
        File tmp = null;
        try {
            File dir = new File(ApplicationLoader.applicationContext.getExternalCacheDir(), "MiniAppCopies");
            dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            tmp = new File(dir, "tmp_" + stamp);
            tmp.mkdirs();
            log("Sahifa yuklanmoqda...");
            byte[] html = fetch(root);
            String page = rewriteHtml(new String(html, StandardCharsets.UTF_8));
            int n = 0;
            while (!queue.isEmpty() && total <= MAX_TOTAL) {
                Item item = queue.poll();
                log("Yuklanmoqda: " + item.url);
                try {
                    File file = new File(tmp, String.valueOf(n++));
                    long size = fetchToFile(item.url, file, MAX_TOTAL - total);
                    boolean css = item.css && size <= 20 * 1024 * 1024;
                    if (css) {
                        String text = new String(readAll(file), StandardCharsets.UTF_8);
                        text = rewriteCss(item.url, item.local, text);
                        try (FileOutputStream os = new FileOutputStream(file)) {
                            os.write(text.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    total += size;
                    fileCount++;
                    savedFiles.put(item.local, file);
                    savedCss.put(item.local, css);
                } catch (Exception e) {
                    failed.add(item.url + " (" + e.getMessage() + ")");
                }
            }
            log("Bitta HTML fayl yig'ilmoqda...");
            result = new File(dir, root.getHost() + "_" + stamp + ".html");
            try (java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(result), 1 << 16)) {
                Matcher m = PLACEHOLDER.matcher(page);
                int last = 0;
                while (m.find()) {
                    out.write(page.substring(last, m.start()).getBytes(StandardCharsets.UTF_8));
                    last = m.end();
                    writeAsset(out, placeholderLocals.get(Integer.parseInt(m.group(1))));
                }
                out.write(page.substring(last).getBytes(StandardCharsets.UTF_8));
                StringBuilder info = new StringBuilder("\n<!-- ParadoksGram: mini-app nusxasi. Fayllar: " + fileCount + ", yuklanmagan: " + failed.size() + "\n");
                for (String line : failed) {
                    info.append(line.replace("--", "- -")).append('\n');
                }
                if (!queue.isEmpty()) {
                    info.append("1.5 GB limitga yetildi, qolgan fayllar saqlanmadi.\n");
                }
                info.append("Skriptlar ichidan keyin yuklanadigan fayllar va backend kod saqlanmaydi. -->\n");
                out.write(info.toString().getBytes(StandardCharsets.UTF_8));
            }
            log("Tayyor: " + fileCount + " ta fayl bitta HTML ichiga joylandi.");
        } catch (Throwable e) {
            log("Sahifani yuklab bo'lmadi: " + e.getMessage());
            result = null;
        } finally {
            deleteRecursive(tmp);
        }
        final File done = result;
        AndroidUtilities.runOnUIThread(() -> callback.done(done));
    }

    private static void deleteRecursive(File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    private static byte[] readAll(File file) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(16, file.length()));
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String mimeFor(String local) {
        String lower = local.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        int tilde = ext.indexOf('~');
        if (tilde >= 0) {
            ext = ext.substring(0, tilde);
        }
        switch (ext) {
            case "css": return "text/css";
            case "js": case "mjs": return "text/javascript";
            case "json": return "application/json";
            case "svg": return "image/svg+xml";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "html": case "htm": return "text/html";
        }
        String guessed = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return guessed != null ? guessed : "application/octet-stream";
    }

    /** Writes a data: URI of the saved asset; CSS is expanded first. Missing files fall back to the original address. */
    private void writeAsset(java.io.OutputStream out, String local) throws Exception {
        File file = savedFiles.get(local);
        if (file == null) {
            String original = originalUrls.get(local);
            out.write((original == null ? "" : original).getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (Boolean.TRUE.equals(savedCss.get(local))) {
            String css = expandCss(local, 0);
            out.write("data:text/css;base64,".getBytes(StandardCharsets.US_ASCII));
            out.write(android.util.Base64.encode(css.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
            return;
        }
        out.write(("data:" + mimeFor(local) + ";base64,").getBytes(StandardCharsets.US_ASCII));
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[3 * 16384];
            while (true) {
                int filled = 0;
                while (filled < buffer.length) {
                    int read = in.read(buffer, filled, buffer.length - filled);
                    if (read <= 0) {
                        break;
                    }
                    filled += read;
                }
                if (filled == 0) {
                    break;
                }
                out.write(android.util.Base64.encode(buffer, 0, filled, android.util.Base64.NO_WRAP));
                if (filled < buffer.length) {
                    break;
                }
            }
        }
    }

    private String expandCss(String local, int depth) throws Exception {
        String css = new String(readAll(savedFiles.get(local)), StandardCharsets.UTF_8);
        Matcher m = PLACEHOLDER.matcher(css);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (m.find()) {
            result.append(css, last, m.start());
            last = m.end();
            String inner = placeholderLocals.get(Integer.parseInt(m.group(1)));
            File file = savedFiles.get(inner);
            if (file == null || file.length() > INLINE_IN_CSS_MAX && !Boolean.TRUE.equals(savedCss.get(inner))) {
                String original = originalUrls.get(inner);
                result.append(original == null ? "" : original);
            } else if (Boolean.TRUE.equals(savedCss.get(inner))) {
                if (depth >= 4) {
                    String original = originalUrls.get(inner);
                    result.append(original == null ? "" : original);
                } else {
                    result.append("data:text/css;base64,").append(android.util.Base64.encodeToString(
                            expandCss(inner, depth + 1).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
                }
            } else {
                result.append("data:").append(mimeFor(inner)).append(";base64,")
                        .append(android.util.Base64.encodeToString(readAll(file), android.util.Base64.NO_WRAP));
            }
        }
        result.append(css.substring(last));
        return result.toString();
    }

    /** Streams a response into a file, with no in-memory cap. Returns its size. */
    private long fetchToFile(URL url, File file, long limit) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) ParadoksGram-Copy");
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("server javobi " + code);
            }
            long size = 0;
            try (InputStream in = connection.getInputStream(); FileOutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    size += read;
                    if (size > limit) {
                        throw new Exception("limitdan oshdi");
                    }
                    os.write(buffer, 0, read);
                }
            }
            return size;
        } finally {
            connection.disconnect();
        }
    }

    private String localPath(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            path += "index";
        }
        ArrayList<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty() && !part.equals(".") && !part.equals("..")) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            parts.add("file");
        }
        if (url.getQuery() != null) {
            String name = parts.get(parts.size() - 1);
            String tag = "~" + Integer.toHexString(url.getQuery().hashCode());
            int dot = name.lastIndexOf('.');
            parts.set(parts.size() - 1, dot > 0 ? name.substring(0, dot) + tag + name.substring(dot) : name + tag);
        }
        StringBuilder result = new StringBuilder();
        if (!url.getHost().equals(root.getHost())) {
            result.append("_ext/").append(url.getHost()).append('/');
        }
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                result.append('/');
            }
            result.append(parts.get(i));
        }
        return result.toString().replaceAll("[<>:\"|?*\\\\]", "_");
    }

    private static String relative(String from, String to) {
        String[] a = from.split("/");
        String[] b = to.split("/");
        int common = 0;
        while (common < a.length - 1 && common < b.length - 1 && a[common].equals(b[common])) {
            common++;
        }
        StringBuilder result = new StringBuilder();
        for (int i = common; i < a.length - 1; i++) {
            result.append("../");
        }
        for (int i = common; i < b.length; i++) {
            result.append(b[i]);
            if (i < b.length - 1) {
                result.append('/');
            }
        }
        return result.toString();
    }

    /** Queues a link; returns the local replacement or null to leave it untouched. */
    private String register(URL base, String fromLocal, String raw, boolean css) {
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.startsWith("#") || lower.startsWith("data:") || lower.startsWith("javascript:")
                || lower.startsWith("mailto:") || lower.startsWith("blob:")) {
            return null;
        }
        URL url;
        try {
            url = new URL(base, text);
            int hash = url.toString().indexOf('#');
            if (hash >= 0) {
                url = new URL(url.toString().substring(0, hash));
            }
        } catch (Exception e) {
            return null;
        }
        if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https")) {
            return null;
        }
        String key = url.toString();
        String local = known.get(key);
        if (local == null) {
            if (known.size() >= MAX_FILES) {
                return null;
            }
            local = localPath(url);
            known.put(key, local);
            Item item = new Item();
            item.url = url;
            item.local = local;
            item.css = css || url.getPath().toLowerCase(Locale.ROOT).endsWith(".css");
            queue.add(item);
        }
        if (single) {
            originalUrls.put(local, key);
            Integer index = placeholderIndex.get(local);
            if (index == null) {
                index = placeholderLocals.size();
                placeholderLocals.add(local);
                placeholderIndex.put(local, index);
            }
            return "\uE000" + index + "\uE001";
        }
        return relative(fromLocal, local);
    }

    private String rewriteHtml(String html) {
        StringBuilder result = new StringBuilder();
        Matcher tags = TAG.matcher(html);
        int last = 0;
        while (tags.find()) {
            result.append(html, last, tags.start());
            last = tags.end();
            String tag = tags.group();
            String name = tags.group(1).toLowerCase(Locale.ROOT);
            String rel = "";
            if (name.equals("link")) {
                Matcher relMatcher = REL.matcher(tag);
                rel = relMatcher.find() ? relMatcher.group(1).toLowerCase(Locale.ROOT) : "";
                if (!rel.contains("stylesheet") && !rel.contains("icon") && !rel.contains("preload") && !rel.contains("manifest")) {
                    result.append(tag);
                    continue;
                }
            }
            boolean style = rel.contains("stylesheet");
            StringBuilder out = new StringBuilder();
            Matcher attrs = ATTR.matcher(tag);
            int tagLast = 0;
            while (attrs.find()) {
                out.append(tag, tagLast, attrs.start(3));
                tagLast = attrs.end(3);
                String local = register(root, "index.html", attrs.group(3), style);
                out.append(local == null ? attrs.group(3) : local);
            }
            out.append(tag.substring(tagLast));
            result.append(out);
        }
        result.append(html.substring(last));
        return result.toString();
    }

    private String rewriteCss(URL base, String fromLocal, String css) {
        StringBuilder result = new StringBuilder();
        Matcher matcher = CSS_URL.matcher(css);
        int last = 0;
        while (matcher.find()) {
            int group = (matcher.group(2) == null || matcher.group(2).isEmpty()) ? 4 : 2;
            result.append(css, last, matcher.start(group));
            last = matcher.end(group);
            String local = register(base, fromLocal, matcher.group(group), true);
            result.append(local == null ? matcher.group(group) : local);
        }
        result.append(css.substring(last));
        return result.toString();
    }
}
