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

    private static final String RELEASE_API = "https://api.github.com/repos/muslihiddinlive/ParadoksGram/releases/latest";
    private static final String DOWNLOAD_PREFIX = "https://github.com/muslihiddinlive/ParadoksGram/releases/download/";
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
                return "Yuklanmoqda " + (int) (progress * 100) + "%";
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
                HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "ParadoksGram-Updater");
                if (connection.getResponseCode() != 200) {
                    throw new Exception("GitHub javob bermadi (" + connection.getResponseCode() + ")");
                }
                JSONObject release = new JSONObject(readAll(connection.getInputStream()));
                String tag = release.optString("tag_name", "");
                int build = Integer.parseInt(tag.substring(tag.lastIndexOf('-') + 1));
                String url = null;
                long size = 0;
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
                lastCheck = System.currentTimeMillis();
                if (build > ParadoksBuild.NUMBER && url != null) {
                    if (build != latestBuild || state == IDLE || state == FAILED) {
                        latestBuild = build;
                        notes = release.optString("body", "");
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

    public static void download(Activity activity) {
        if (state == DOWNLOADING || apkUrl == null || !apkUrl.startsWith(DOWNLOAD_PREFIX)) {
            return;
        }
        installActivity = new WeakReference<>(activity);
        cancelled = false;
        error = null;
        progress = 0;
        state = DOWNLOADING;
        notifyLayouts();
        final String url = apkUrl;
        final long expected = apkSize;
        final File target = apkFile;
        Utilities.globalQueue.postRunnable(() -> {
            File part = new File(target.getParentFile(), target.getName() + ".part");
            boolean ok = false;
            String failure = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "ParadoksGram-Updater");
                if (connection.getResponseCode() != 200) {
                    throw new Exception("server javobi " + connection.getResponseCode());
                }
                long total = expected > 0 ? expected : connection.getContentLengthLong();
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    long lastNotify = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancelled) {
                            throw new Exception("bekor qilindi");
                        }
                        out.write(buffer, 0, read);
                        done += read;
                        progress = total > 0 ? Math.min(1f, done / (float) total) : 0;
                        long now = System.currentTimeMillis();
                        if (now - lastNotify > 250) {
                            lastNotify = now;
                            notifyLayouts();
                        }
                    }
                }
                if (expected > 0 && part.length() != expected) {
                    throw new Exception("fayl to'liq yuklanmadi");
                }
                if (!verifyApk(ApplicationLoader.applicationContext, part)) {
                    throw new Exception("APK imzosi mos kelmadi, o'rnatilmaydi");
                }
                if (target.exists()) {
                    target.delete();
                }
                ok = part.renameTo(target);
                if (!ok) {
                    throw new Exception("faylni saqlab bo'lmadi");
                }
            } catch (Throwable t) {
                failure = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                part.delete();
            }
            final boolean success = ok;
            final String finalFailure = failure;
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
        });
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
