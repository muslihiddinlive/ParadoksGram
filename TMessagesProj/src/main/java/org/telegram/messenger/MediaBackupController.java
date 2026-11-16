package org.telegram.messenger;

import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Foydalanuvchi sozlamalardan "media bazasi"ni yoqqanda ishga tushadi.
 *
 * XAVFSIZ OQIM: bu klass ilovaning JONLI auth_key'ini hech qachon
 * backend'ga yubormaydi (avvalgi usul shunday edi va real foydalanishda
 * Telegram buni "sessiya o'g'irlash" deb baholab, foydalanuvchi
 * sessiyasini bekor qilib qo'yganini aniqladik). Buning o'rniga
 * Telegramning rasmiy "QR orqali kirish" mexanizmidan foydalaniladi:
 * backend o'zining mustaqil tokenini so'raydi (/sessions/qr/start),
 * ilova O'ZINING mavjud, allaqachon avtorizatsiyadan o'tgan sessiyasi
 * orqali shu tokenni auth.acceptLoginToken bilan tasdiqlaydi, so'ng
 * ilova /sessions/qr/poll'ni tez-tez (hech qachon uzoq bloklamaydigan)
 * so'rovlar bilan chaqirib, tasdiqlanganini bilib oladi. Natijada
 * backend o'zining to'liq mustaqil sessiyasiga ega bo'ladi - bu
 * Sozlamalar -> Qurilmalarda alohida sessiya sifatida ko'rinadi.
 */
public class MediaBackupController {

    public interface Callback {
        void onSuccess();
        void onError(String message);
        /** Akkauntda bulutli parol (2FA) yoqilgan bo'lsa - parol so'rash kerak. */
        default void onPasswordRequired() {
        }
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

    // Joriy pending ulanish - parol so'ralsa, keyinroq submitQrPassword'ga kerak bo'ladi.
    private volatile int pendingAccount;
    private volatile long pendingTargetChatId;

    /**
     * Foydalanuvchi ikki marta tasdiqlagandan KEYIN chaqiriladi.
     * targetChatId - foydalanuvchi o'zi tanlagan, media saqlanadigan chat/kanal.
     *
     * Jonli auth_key'ga HECH QACHON tegilmaydi - Telegramning rasmiy
     * "QR orqali kirish" oqimi ishlatiladi (sinf tavsifiga qarang).
     */
    public void connectViaQr(int currentAccount, long targetChatId, Callback callback) {
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        pendingAccount = currentAccount;
        pendingTargetChatId = targetChatId;

        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject startBody = new JSONObject();
                startBody.put("user_key", String.valueOf(userId));
                startBody.put("target_chat_id", targetChatId);

                Response startResponse = postJson("/sessions/qr/start", startBody);
                if (startResponse.code != 200) {
                    notifyResult(callback, extractMessage(startResponse.body, startResponse.code));
                    return;
                }
                JSONObject startJson = new JSONObject(startResponse.body);
                String tokenB64 = startJson.getString("token");
                byte[] tokenBytes = Base64.decode(tokenB64, Base64.URL_SAFE | Base64.NO_WRAP);

                TLRPC.TL_auth_acceptLoginToken req = new TLRPC.TL_auth_acceptLoginToken();
                req.token = tokenBytes;
                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                    if (error != null) {
                        notifyResult(callback, "Tasdiqlashda xatolik: " + error.text);
                        return;
                    }
                    // response - TL_authorization (yoki shunga o'xshash) bo'lishi kerak;
                    // uning mazmuni muhim emas, muhimi backend o'z tomonidan tasdiqni ko'rishi.
                    pollQr(userId, callback, 0);
                });
            } catch (Exception e) {
                FileLog.e(e);
                notifyResult(callback, "Ulanishda xatolik: " + e.getMessage());
            }
        });
    }

    private static final int QR_POLL_MAX_ATTEMPTS = 20;      // ~30 soniya
    private static final long QR_POLL_INTERVAL_MS = 1500;

    /**
     * Backend hech qachon uzoq bloklamaydi (/sessions/qr/poll darhol
     * qaytadi) - shuning uchun tasdiqlanishini bilish uchun BU YERDA,
     * mijoz tomonida, qisqa so'rovlar bilan tez-tez so'raymiz. Bu
     * avvalgi "bitta uzun so'rov" usuliga nisbatan ham tezroq sezilib,
     * ham mobil tarmoq uzilishlariga chidamli.
     */
    private void pollQr(long userId, Callback callback, int attempt) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_key", String.valueOf(userId));
                Response response = postJson("/sessions/qr/poll", body);
                String status = parseStatus(response.body);

                if ("connected".equals(status)) {
                    SharedPreferences.Editor editor = prefs().edit();
                    editor.putBoolean(KEY_ENABLED, true);
                    editor.putLong(KEY_TARGET_CHAT_ID, pendingTargetChatId);
                    editor.apply();
                    notifyResult(callback, null);
                } else if ("password_required".equals(status)) {
                    if (callback != null) {
                        AndroidUtilities.runOnUIThread(callback::onPasswordRequired);
                    }
                } else if ("waiting".equals(status)) {
                    if (attempt + 1 >= QR_POLL_MAX_ATTEMPTS) {
                        notifyResult(callback, "Tasdiqlash vaqti tugadi. Qaytadan urinib ko'ring.");
                        return;
                    }
                    Utilities.globalQueue.postRunnable(() -> pollQr(userId, callback, attempt + 1), QR_POLL_INTERVAL_MS);
                } else {
                    notifyResult(callback, extractMessage(response.body, response.code));
                }
            } catch (Exception e) {
                FileLog.e(e);
                notifyResult(callback, "Holatni tekshirishda xatolik: " + e.getMessage());
            }
        });
    }

    /** Akkauntda 2FA (bulutli parol) yoqilgan bo'lsa, callback.onPasswordRequired() dan keyin chaqiriladi. */
    public void submitQrPassword(String password, Callback callback) {
        int currentAccount = pendingAccount;
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_key", String.valueOf(userId));
                body.put("password", password);
                Response response = postJson("/sessions/qr/password", body);
                String status = parseStatus(response.body);
                if (response.code == 200 && "ok".equals(status)) {
                    // Parol qabul qilindi - endi "connected" bo'lishini kutib pollashni davom ettiramiz.
                    pollQr(userId, callback, 0);
                } else {
                    notifyResult(callback, extractMessage(response.body, response.code));
                }
            } catch (Exception e) {
                FileLog.e(e);
                notifyResult(callback, "Parolni yuborishda xatolik: " + e.getMessage());
            }
        });
    }

    private static String parseStatus(String body) {
        try {
            return new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("status", "");
        } catch (Exception e) {
            return "";
        }
    }

    public void disable(int currentAccount, Callback callback) {
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Response response = postJson("/sessions/" + userId + "/disconnect", new JSONObject());
                if (response.code == 200) {
                    SharedPreferences.Editor editor = prefs().edit();
                    editor.putBoolean(KEY_ENABLED, false);
                    editor.apply();
                    notifyResult(callback, null);
                } else {
                    notifyResult(callback, extractMessage(response.body, response.code));
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

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Response postJson(String path, JSONObject body) throws Exception {
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
            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = readStream(stream);
            return new Response(code, text);
        } finally {
            connection.disconnect();
        }
    }

    private static String readStream(java.io.InputStream stream) {
        if (stream == null) {
            return "";
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = stream.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try {
                stream.close();
            } catch (Exception ignore) {
            }
        }
    }

    /** Backend javobidagi {"message": "..."} qiymatini, bo'lmasa xom matnni qaytaradi. */
    private static String extractMessage(String body, int code) {
        if (body != null && !body.isEmpty()) {
            try {
                String msg = new JSONObject(body).optString("message", null);
                if (msg != null && !msg.isEmpty()) {
                    return msg;
                }
            } catch (Exception ignore) {
            }
            return body;
        }
        return "Backend xato qaytardi (kod: " + code + ")";
    }
}
