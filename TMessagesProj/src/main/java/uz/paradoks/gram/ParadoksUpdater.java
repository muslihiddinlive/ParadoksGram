package uz.paradoks.gram;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * In-app updates: looks at the newest GitHub Release (CI publishes every build as "build-N"), downloads the
 * APK, checks that it is for this app and signed with the same key, then hands it to the system installer.
 * Only the official release URL of this repository is accepted.
 */
public final class ParadoksUpdater {

    public static final int IDLE = 0;
    public static final int AVAILABLE = 1;
    public static final int DOWNLOADING = 2;
    public static final int READY = 3;
    public static final int FAILED = 4;

    private static final String RELEASE_API = "https://api.github.com/repos/muslihiddinlive/ParadoksGram/releases?per_page=5";
    private static final String DOWNLOAD_PREFIX = "https://github.com/muslihiddinlive/ParadoksGram/releases/download/";
    /**
     * Official site (gateway) base URL, e.g. "https://paradoksgram.uz". When set, updates come from the
     * site's /app/latest and /app/apk instead of GitHub, so users never see the release repository.
     */
    private static final String SITE_URL = "";
    private static final long CHECK_INTERVAL_MS = 30L * 60 * 1000;

    private static volatile int state = IDLE;
    private static volatile float progress;
    private static volatile boolean cancelled;
    private static volatile boolean checking;
    private static volatile String error;
    private static int latestBuild;
    private static String notes = "";
    private static String apkUrl;
    private static long apkSize;
    private static File apkFile;
    private static long lastCheck;
    private static WeakReference<Activity> installActivity;
    private static final ArrayList<WeakReference<ParadoksUpdateLayout>> layouts = new ArrayList<>();

    private ParadoksUpdater() {
    }

    public static int state() {
        return state;
    }

    public static float progress() {
        return progress;
    }

    public static int latestBuild() {
        return latestBuild;
    }

    public static long apkSize() {
        return apkSize;
    }

    public static String error() {
        return error;
    }

    static void register(ParadoksUpdateLayout layout) {
        layouts.add(new WeakReference<>(layout));
    }

    private static void notifyLayouts() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = layouts.size() - 1; i >= 0; i--) {
                ParadoksUpdateLayout layout = layouts.get(i).get();
                if (layout == null) {
                    layouts.remove(i);
                } else {
                    layout.refresh(true);
                }
            }
        });
    }

    /** Short text for the settings row. */
    public static String statusText() {
        switch (state) {
            case AVAILABLE:
                return "Yangi versiya bor: build " + latestBuild;
            case DOWNLOADING:
                return "Yuklanmoqda " + (int) (progress * 100) + "%" + (speed > 0 ? " (" + AndroidUtilities.formatFileSize(speed) + "/s)" : "");
            case READY:
                return "Tayyor: build " + latestBuild + " (o'rnatish uchun bosing)";
            case FAILED:
                return "Xato: " + (error == null ? "yuklab bo'lmadi" : error);
            default:
                return "Build " + ParadoksBuild.NUMBER + (checking ? " (tekshirilmoqda...)" : " (eng so'nggi)");
        }
    }

    public static void checkIfDue() {
        if (System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS) {
            check(null);
        }
    }

    /** Asks GitHub for the newest release; {@code done} runs on the UI thread. */
    public static void check(Runnable done) {
        if (checking || state == DOWNLOADING) {
            return;
        }
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            String failure = null;
            try {
                int build;
                String url = null;
                long size = 0;
                String body = "";
                if (!SITE_URL.isEmpty()) {
                    // the official site's gateway (hides the release repository from users)
                    HttpURLConnection connection = (HttpURLConnection) new URL(SITE_URL + "/app/latest").openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(20000);
                    connection.setRequestProperty("User-Agent", "ParadoksGram-Updater");
                    if (connection.getResponseCode() != 200) {
                        throw new Exception("Server javob bermadi (" + connection.getResponseCode() + ")");
                    }
                    JSONObject info = new JSONObject(readAll(connection.getInputStream()));
                    build = info.getInt("build");
                    size = info.optLong("size", 0);
                    body = info.optString("notes", "");
                    url = SITE_URL + "/app/apk";
                } else {
                    HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(20000);
                    connection.setRequestProperty("Accept", "application/vnd.github+json");
                    connection.setRequestProperty("User-Agent", "ParadoksGram-Updater");
                    if (connection.getResponseCode() != 200) {
                        throw new Exception("GitHub javob bermadi (" + connection.getResponseCode() + ")");
                    }
                    JSONArray releases = new JSONArray(readAll(connection.getInputStream()));
                    JSONObject release = null; // newest published release (CI marks them as pre-releases, so /latest would not see them)
                    for (int i = 0; i < releases.length() && release == null; i++) {
                        JSONObject candidate = releases.getJSONObject(i);
                        if (!candidate.optBoolean("draft", false)) {
                            release = candidate;
                        }
                    }
                    if (release == null) {
                        throw new Exception("reliz topilmadi");
                    }
                    String tag = release.optString("tag_name", "");
                    build = Integer.parseInt(tag.substring(tag.lastIndexOf('-') + 1));
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String candidate = asset.optString("browser_download_url", "");
                            if (asset.optString("name", "").endsWith(".apk") && candidate.startsWith(DOWNLOAD_PREFIX)) {
                                url = candidate;
                                size = asset.optLong("size", 0);
                                break;
                            }
                        }
                    }
                    body = release.optString("body", "");
                }
                lastCheck = System.currentTimeMillis();
                if (build > ParadoksBuild.NUMBER && url != null) {
                    if (build != latestBuild || state == IDLE || state == FAILED) {
                        latestBuild = build;
                        notes = body;
                        apkUrl = url;
                        apkSize = size;
                        apkFile = new File(updatesDir(), "ParadoksGram-" + build + ".apk");
                        state = apkFile.exists() && (size == 0 || apkFile.length() == size) ? READY : AVAILABLE;
                        error = null;
                    }
                } else if (state != DOWNLOADING) {
                    state = IDLE;
                }
            } catch (Throwable t) {
                failure = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final String finalFailure = failure;
            checking = false;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalFailure != null && done != null) {
                    error = finalFailure;
                }
                notifyLayouts();
                if (done != null) {
                    done.run();
                }
            });
        });
    }

    /** Settings row / button: check now and tell the user the result. */
    public static void userCheck(Activity activity) {
        Toast.makeText(activity, "Yangilanish tekshirilmoqda...", Toast.LENGTH_SHORT).show();
        check(() -> {
            if (state == IDLE) {
                Toast.makeText(activity, error != null ? "Tekshirib bo'lmadi: " + error : "Eng so'nggi versiya o'rnatilgan (build " + ParadoksBuild.NUMBER + ")", Toast.LENGTH_LONG).show();
            } else {
                showDialog(activity);
            }
        });
    }

    /** Shows the "new version" dialog once per build. */
    static void announceOnce(Activity activity) {
        if (activity == null || state != AVAILABLE || ParadoksConfig.updateAnnounced() >= latestBuild) {
            return;
        }
        ParadoksConfig.setUpdateAnnounced(latestBuild);
        showDialog(activity);
    }

    public static void showDialog(Activity activity) {
        if (activity == null || state == IDLE) {
            return;
        }
        String text = "Yangi versiya: build " + latestBuild + (apkSize > 0 ? " (" + AndroidUtilities.formatFileSize(apkSize) + ")" : "")
                + "\nHozirgi: build " + ParadoksBuild.NUMBER;
        if (notes != null && !notes.trim().isEmpty()) {
            String shortNotes = notes.trim();
            text += "\n\nNima yangi:\n" + (shortNotes.length() > 700 ? shortNotes.substring(0, 700) + "…" : shortNotes);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("ParadoksGram ni yangilash");
        builder.setMessage(text);
        if (state == READY) {
            builder.setPositiveButton("O'rnatish", (d, w) -> install(activity));
        } else if (state == DOWNLOADING) {
            builder.setPositiveButton("Bekor qilish", (d, w) -> cancel());
        } else {
            builder.setPositiveButton("Yuklab olish va yangilash", (d, w) -> download(activity));
        }
        builder.setNegativeButton("Keyinroq", null);
        builder.show();
    }

    private static final int CHUNK = 1024 * 1024;
    private static final int WORKERS = 4;
    private static volatile long speed;

    /** Current download speed in bytes per second (smoothed). */
    public static long speed() {
        return speed;
    }

    public static void download(Activity activity) {
        if (state == DOWNLOADING || apkUrl == null || !apkUrl.startsWith(DOWNLOAD_PREFIX)) {
            return;
        }
        installActivity = new WeakReference<>(activity);
        cancelled = false;
        error = null;
        progress = 0;
        speed = 0;
        state = DOWNLOADING;
        notifyLayouts();
        final String url = apkUrl;
        final long expected = apkSize;
        final File target = apkFile;
        // its own thread: a long download must not block the app's shared background queue
        new Thread(() -> {
            File dir = target.getParentFile();
            File part = new File(dir, target.getName() + ".part");
            File chunkState = new File(dir, target.getName() + ".chunks");
            boolean ok = false;
            String failure = null;
            try {
                // 1) one-byte ranged request: finds the real (CDN) address and the exact size
                HttpURLConnection probe = open(url);
                probe.setRequestProperty("Range", "bytes=0-0");
                int code = probe.getResponseCode();
                long total = expected;
                boolean ranged = false;
                String finalUrl = url;
                if (code == 206) {
                    String range = probe.getHeaderField("Content-Range");
                    if (range != null && range.contains("/")) {
                        total = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
                    }
                    ranged = total > 0;
                    finalUrl = probe.getURL().toString();
                } else if (code != 200) {
                    throw new Exception("server javobi " + code);
                }
                probe.disconnect();
                String host = new URL(finalUrl).getHost();
                if (!finalUrl.startsWith("https://") || !(host.equals("github.com") || host.endsWith(".githubusercontent.com") || (!SITE_URL.isEmpty() && SITE_URL.endsWith("//" + host)))) {
                    throw new Exception("kutilmagan yuklash manzili");
                }
                if (expected > 0 && total > 0 && total != expected) {
                    throw new Exception("fayl hajmi relizdagi bilan mos kelmadi");
                }
                // 2) parallel, resumable download (or one stream if the server cannot do ranges)
                if (ranged) {
                    downloadParallel(finalUrl, total, part, chunkState);
                } else {
                    downloadSingle(url, expected, part);
                }
                if (cancelled) {
                    throw new Exception("bekor qilindi");
                }
                if (expected > 0 && part.length() != expected) {
                    throw new Exception("fayl to'liq yuklanmadi");
                }
                if (!verifyApk(ApplicationLoader.applicationContext, part)) {
                    part.delete();
                    chunkState.delete();
                    throw new Exception("APK imzosi mos kelmadi, o'rnatilmaydi");
                }
                if (target.exists()) {
                    target.delete();
                }
                ok = part.renameTo(target);
                chunkState.delete();
                if (!ok) {
                    throw new Exception("faylni saqlab bo'lmadi");
                }
            } catch (Throwable t) {
                // the partial file is kept, so the next try continues where this one stopped
                failure = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final boolean success = ok;
            final String finalFailure = failure;
            speed = 0;
            AndroidUtilities.runOnUIThread(() -> {
                if (success) {
                    state = READY;
                    notifyLayouts();
                    install(installActivity != null ? installActivity.get() : null);
                } else {
                    state = cancelled ? AVAILABLE : FAILED;
                    error = finalFailure;
                    notifyLayouts();
                }
            });
        }, "ParadoksUpdater").start();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "ParadoksGram-Updater");
        return connection;
    }

    private static void downloadSingle(String url, long expected, File part) throws Exception {
        HttpURLConnection connection = open(url);
        if (connection.getResponseCode() != 200) {
            throw new Exception("server javobi " + connection.getResponseCode());
        }
        long total = expected > 0 ? expected : connection.getContentLengthLong();
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            long lastNotify = System.currentTimeMillis();
            long lastBytes = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled) {
                    throw new Exception("bekor qilindi");
                }
                out.write(buffer, 0, read);
                done += read;
                progress = total > 0 ? Math.min(1f, done / (float) total) : 0;
                long now = System.currentTimeMillis();
                if (now - lastNotify >= 500) {
                    speed = (done - lastBytes) * 1000 / (now - lastNotify);
                    lastNotify = now;
                    lastBytes = done;
                    notifyLayouts();
                }
            }
        }
    }

    private static void downloadParallel(final String url, final long total, final File part, final File chunkState) throws Exception {
        final int chunks = (int) ((total + CHUNK - 1) / CHUNK);
        final boolean[] done = new boolean[chunks];
        boolean resumed = loadChunkState(chunkState, part, total, chunks, done);
        if (!resumed) {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(part, "rw")) {
                raf.setLength(total);
            }
            java.util.Arrays.fill(done, false);
        }
        final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        for (int i = 0; i < chunks; i++) {
            if (done[i]) {
                bytes.addAndGet(chunkLength(i, total));
            }
        }
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(WORKERS);
        for (int w = 0; w < WORKERS; w++) {
            new Thread(() -> {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(part, "rw")) {
                    int index;
                    while (failure.get() == null && !cancelled && (index = next.getAndIncrement()) < chunks) {
                        if (done[index]) {
                            continue;
                        }
                        fetchChunk(url, index, total, raf, bytes);
                        synchronized (done) {
                            done[index] = true;
                            saveChunkState(chunkState, total, chunks, done);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            }, "ParadoksUpdater-" + w).start();
        }
        long lastBytes = bytes.get();
        long lastTime = System.currentTimeMillis();
        while (!latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            long now = System.currentTimeMillis();
            long current = bytes.get();
            long instant = (current - lastBytes) * 1000 / Math.max(1, now - lastTime);
            speed = speed == 0 ? instant : (speed * 6 + instant * 4) / 10; // smoothed
            lastBytes = current;
            lastTime = now;
            progress = Math.min(1f, current / (float) total);
            notifyLayouts();
        }
        progress = Math.min(1f, bytes.get() / (float) total);
        if (failure.get() != null) {
            throw new Exception(failure.get().getMessage() != null ? failure.get().getMessage() : failure.get().getClass().getSimpleName());
        }
    }

    private static long chunkLength(int index, long total) {
        return Math.min(total, (long) (index + 1) * CHUNK) - (long) index * CHUNK;
    }

    private static void fetchChunk(String url, int index, long total, java.io.RandomAccessFile raf, java.util.concurrent.atomic.AtomicLong bytes) throws Exception {
        long start = (long) index * CHUNK;
        long length = chunkLength(index, total);
        Exception last = null;
        for (int attempt = 0; attempt < 3 && !cancelled; attempt++) {
            long counted = 0;
            try {
                HttpURLConnection connection = open(url);
                connection.setRequestProperty("Range", "bytes=" + start + "-" + (start + length - 1));
                if (connection.getResponseCode() != 206) {
                    throw new Exception("server javobi " + connection.getResponseCode());
                }
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[32 * 1024];
                    long written = 0;
                    int read;
                    while (written < length && (read = in.read(buffer, 0, (int) Math.min(buffer.length, length - written))) != -1) {
                        if (cancelled) {
                            throw new Exception("bekor qilindi");
                        }
                        raf.seek(start + written);
                        raf.write(buffer, 0, read);
                        written += read;
                        counted += read;
                        bytes.addAndGet(read);
                    }
                    if (written != length) {
                        throw new Exception("bo'lak to'liq kelmadi");
                    }
                }
                return;
            } catch (Exception e) {
                last = e;
                bytes.addAndGet(-counted); // this attempt is retried from its start
            }
        }
        throw last != null ? last : new Exception("bekor qilindi");
    }

    private static void saveChunkState(File file, long total, int chunks, boolean[] done) {
        try {
            StringBuilder sb = new StringBuilder().append(total).append(':').append(chunks).append(':');
            for (boolean d : done) {
                sb.append(d ? '1' : '0');
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(sb.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignore) {
        }
    }

    /** Reads which chunks are already on disk; false if there is nothing valid to resume. */
    private static boolean loadChunkState(File file, File part, long total, int chunks, boolean[] done) {
        try {
            if (!file.exists() || !part.exists() || part.length() != total) {
                return false;
            }
            String text = readAll(new java.io.FileInputStream(file));
            String[] pieces = text.split(":");
            if (pieces.length != 3 || Long.parseLong(pieces[0]) != total || Integer.parseInt(pieces[1]) != chunks || pieces[2].length() != chunks) {
                return false;
            }
            for (int i = 0; i < chunks; i++) {
                done[i] = pieces[2].charAt(i) == '1';
            }
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static void cancel() {
        cancelled = true;
    }

    public static void install(Activity activity) {
        if (activity == null || apkFile == null || !apkFile.exists()) {
            return;
        }
        AndroidUtilities.openForView(apkFile, "ParadoksGram.apk", "application/vnd.android.package-archive", activity, null, false);
    }

    private static File updatesDir() {
        File base = ApplicationLoader.applicationContext.getExternalFilesDir(null);
        if (base == null) {
            base = ApplicationLoader.applicationContext.getFilesDir();
        }
        File dir = new File(base, "updates");
        dir.mkdirs();
        // old downloads are no longer needed
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                try {
                    int build = Integer.parseInt(name.substring(name.indexOf('-') + 1, name.indexOf(".apk")));
                    if (build <= ParadoksBuild.NUMBER) {
                        file.delete();
                    }
                } catch (Throwable ignore) {
                }
            }
        }
        return dir;
    }

    /** The APK must be this app and signed with the same certificate as the installed one. */
    private static boolean verifyApk(Context context, File file) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) {
            return false;
        }
        Set<String> mine = signatures(pm.getPackageInfo(context.getPackageName(), flags));
        Set<String> theirs = signatures(archive);
        if (mine.isEmpty() || theirs.isEmpty()) {
            return true; // cannot read: the system installer still enforces the signature
        }
        return mine.equals(theirs);
    }

    private static Set<String> signatures(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            signatures = info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : null;
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<>();
        if (signatures != null) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                result.add(Utilities.bytesToHex(digest.digest(signature.toByteArray())));
            }
        }
        return result;
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toString("UTF-8");
    }
}
