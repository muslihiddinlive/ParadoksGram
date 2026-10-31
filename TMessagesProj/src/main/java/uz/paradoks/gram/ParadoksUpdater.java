package uz.paradoks.gram;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * ParadoksGram: checks BuildNoticer /latest for a newer build and shows an in-app dialog.
 * Called once per session, on app launch, after a short delay.
 */
public final class ParadoksUpdater {

    private static final String ENDPOINT = ParadoksErrorReport.ENDPOINT
            .replace("/report", "/latest");
    private static final long CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(4);
    private static final String PREF_LAST_CHECK = "update_last_check";
    private static final String PREF_SKIP_BUILD = "update_skip_build";

    private static boolean checked;

    public static void checkOnce(Activity activity) {
        if (checked || activity == null || ENDPOINT.contains("/report")) return;
        long now = System.currentTimeMillis();
        long last = ApplicationLoader.applicationContext
                .getSharedPreferences("paradoks", android.content.Context.MODE_PRIVATE)
                .getLong(PREF_LAST_CHECK, 0);
        if (now - last < CHECK_INTERVAL_MS) return;
        checked = true;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code != 200) return;
                InputStream in = c.getInputStream();
                byte[] buf = new byte[4096];
                int n = 0, r;
                while ((r = in.read(buf, n, buf.length - n)) > 0) n += r;
                c.disconnect();
                JSONObject obj = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (!obj.optBoolean("ok")) return;
                int latestBuild = obj.optInt("build");
                String downloadUrl = obj.optString("url");
                String buildName = obj.optString("name", "build-" + latestBuild);
                ApplicationLoader.applicationContext
                        .getSharedPreferences("paradoks", android.content.Context.MODE_PRIVATE)
                        .edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                int skipBuild = ApplicationLoader.applicationContext
                        .getSharedPreferences("paradoks", android.content.Context.MODE_PRIVATE)
                        .getInt(PREF_SKIP_BUILD, 0);
                if (latestBuild <= ParadoksBuild.NUMBER || latestBuild <= skipBuild) return;
                final String url = downloadUrl;
                final String name = buildName;
                final int nb = latestBuild;
                AndroidUtilities.runOnUIThread(() -> showDialog(activity, nb, name, url));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, "ParadoksUpdater").start();
    }

    private static void showDialog(Activity activity, int build, String name, String url) {
        try {
            if (activity.isFinishing()) return;
            new AlertDialog.Builder(activity)
                    .setTitle("Yangi versiya mavjud")
                    .setMessage("ParadoksGram " + name + " chiqdi!\nHozirgi: build-" + ParadoksBuild.NUMBER)
                    .setPositiveButton("Yuklab olish", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Throwable ignore) {}
                    })
                    .setNeutralButton("Bu versiyani o'tkazib yuborish", (d, w) -> {
                        ApplicationLoader.applicationContext
                                .getSharedPreferences("paradoks", android.content.Context.MODE_PRIVATE)
                                .edit().putInt(PREF_SKIP_BUILD, build).apply();
                    })
                    .setNegativeButton("Keyinroq", null)
                    .show();
        } catch (Throwable ignore) {}
    }

    private ParadoksUpdater() {}
}
