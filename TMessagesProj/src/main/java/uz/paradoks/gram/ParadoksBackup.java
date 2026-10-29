package uz.paradoks.gram;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Exports the ParadoksGram settings to a small JSON file and restores them (for example after a reinstall).
 *
 * What is NOT in the file, on purpose: the PIN (hash and salt), the list of locked and hidden chats, the device pairing token and
 * the "AI commands" switch, server addresses (AI, ads, proxy list), chat ids of the media database, caches and counters.
 * A file received from someone else therefore cannot turn on AI commands, point the app to another server or read a PIN.
 */
public final class ParadoksBackup {

    public static final int REQUEST_IMPORT = 7781;
    private static final String FORMAT = "ParadoksGram-settings";
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 256 * 1024;

    private ParadoksBackup() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("paradoks", Context.MODE_PRIVATE);
    }

    /** Keys (or key prefixes) that never leave the phone and are never restored. */
    private static boolean isExcluded(String key) {
        if (key == null) {
            return true;
        }
        return key.startsWith("lock_") || key.startsWith("hidden_") || key.startsWith("deleted_db_target_")
                || key.startsWith("adseen_") || key.startsWith("ai_bot_") || key.startsWith("device_")
                || key.equals("ai_server") || key.equals("ad_server") || key.equals("proxy_feed_url")
                || key.equals("ad_cache") || key.equals("install_id") || key.equals("update_announced")
                || key.equals("ai_started") || key.startsWith("proxy_feed_last") || key.equals("proxy_feed_status")
                || key.equals("proxy_feed");
    }

    // ------------------------------------------------------------------ export

    public static JSONObject buildJson() throws Exception {
        JSONObject values = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isExcluded(key) || value == null) {
                continue;
            }
            if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof String) {
                values.put(key, value);
            }
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("build", ParadoksBuild.NUMBER);
        root.put("settings", values);
        return root;
    }

    /** Writes the file into the app cache and opens the system share sheet. */
    public static void export(Activity activity) {
        try {
            File dir = new File(activity.getCacheDir(), "paradoks_backup");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("papka ochilmadi");
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
            File file = new File(dir, "paradoksgram-sozlamalar-" + stamp + ".json");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(buildJson().toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/json");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send, "Sozlamalarni saqlash"));
        } catch (Throwable t) {
            Toast.makeText(activity, "Eksport qilib bo'lmadi: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ import

    /** Opens the system file picker; the result comes to {@link #onPicked}. */
    public static Intent pickIntent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        return Intent.createChooser(intent, "Sozlamalar faylini tanlang");
    }

    /** Reads the picked file and restores it. Returns the number of restored settings, or -1 on error (the toast says why). */
    public static int onPicked(Activity activity, Uri uri) {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new Exception("fayl ochilmadi");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BYTES) {
                    throw new Exception("fayl juda katta");
                }
            }
            int count = restore(new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)));
            Toast.makeText(activity, count + " ta sozlama tiklandi. Ilovani qayta oching.", Toast.LENGTH_LONG).show();
            return count;
        } catch (Throwable t) {
            Toast.makeText(activity, "Tiklab bo'lmadi: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return -1;
        }
    }

    static int restore(JSONObject root) throws Exception {
        if (!FORMAT.equals(root.optString("format"))) {
            throw new Exception("bu ParadoksGram sozlamalari fayli emas");
        }
        if (root.optInt("version", 0) > VERSION) {
            throw new Exception("fayl yangiroq versiyadan, avval ilovani yangilang");
        }
        JSONObject values = root.getJSONObject("settings");
        SharedPreferences current = prefs();
        SharedPreferences.Editor editor = current.edit();
        int count = 0;
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isExcluded(key)) {
                continue;
            }
            Object value = values.get(key);
            Object existing = current.getAll().get(key);
            // a value only replaces a setting of the same type, so a damaged file cannot change a switch into text
            if (value instanceof Boolean && (existing == null || existing instanceof Boolean)) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer && existing instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Integer && existing == null) {
                editor.putInt(key, (Integer) value);
            } else if ((value instanceof Long || value instanceof Integer) && existing instanceof Long) {
                editor.putLong(key, ((Number) value).longValue());
            } else if (value instanceof String && (existing == null || existing instanceof String) && ((String) value).length() < 4096) {
                editor.putString(key, (String) value);
            } else {
                continue;
            }
            count++;
        }
        editor.apply();
        return count;
    }
}
