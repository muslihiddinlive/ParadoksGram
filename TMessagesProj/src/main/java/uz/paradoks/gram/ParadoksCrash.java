package uz.paradoks.gram;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Tiny crash reporter for ParadoksGram test builds. Stores the last Java crash (and, on
 * Android 11+, the last native crash reason) and shows it on the next start so it can be
 * copied and sent for debugging.
 */
public final class ParadoksCrash {

    private static final String FILE_NAME = "paradoks_crash.txt";
    private static final String PREFS = "paradoks_crash";
    private static final int MAX_CHARS = 7000;

    public static void install(final Context context) {
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    StringWriter sw = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(sw));
                    String text = "Thread: " + thread.getName()
                            + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                            + Build.MANUFACTURER + " " + Build.MODEL
                            + "\nABIs: " + String.join(",", Build.SUPPORTED_ABIS)
                            + "\n\n" + sw;
                    FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME));
                    out.write(text.getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignore) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /** Shows the stored crash report (if any) in a dialog. Safe to call from the UI thread. */
    public static void showIfAny(final Activity activity) {
        try {
            String text = readJavaCrash(activity);
            if (text == null) {
                text = readNativeCrash(activity);
            }
            if (text == null) {
                return;
            }
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS) + "\n...";
            }
            final String finalText = text;
            new AlertDialog.Builder(activity)
                    .setTitle("ParadoksGram: oxirgi xato")
                    .setMessage(finalText)
                    .setPositiveButton("Nusxa olish", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("crash", finalText));
                            Toast.makeText(activity, "Nusxa olindi", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Yopish", null)
                    .show();
            ParadoksErrorReport.sendAsync(activity, "xato (crash)", finalText); // avtomatik yuboriladi, dialog faqat ko'rsatish uchun
        } catch (Throwable ignore) {
        }
    }

    private static String readJavaCrash(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) {
                return null;
            }
            FileInputStream in = new FileInputStream(file);
            byte[] data = new byte[(int) Math.min(file.length(), 60000)];
            int read = in.read(data);
            in.close();
            file.delete();
            return read > 0 ? "JAVA XATO:\n" + new String(data, 0, read, "UTF-8") : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readNativeCrash(Context context) {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return null;
            }
            List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1);
            if (list == null || list.isEmpty()) {
                return null;
            }
            android.app.ApplicationExitInfo info = list.get(0);
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (info.getTimestamp() <= prefs.getLong("last_ts", 0)) {
                return null;
            }
            prefs.edit().putLong("last_ts", info.getTimestamp()).apply();
            int reason = info.getReason();
            if (reason != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                    && reason != android.app.ApplicationExitInfo.REASON_CRASH
                    && reason != android.app.ApplicationExitInfo.REASON_ANR
                    && reason != android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE) {
                return null;
            }
            return "OXIRGI YOPILISH SABABI (Android):\nreason=" + reason
                    + " status=" + info.getStatus()
                    + "\n" + info.getDescription()
                    + "\nAndroid " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL;
        } catch (Throwable t) {
            return null;
        }
    }

    private ParadoksCrash() {
    }
}
