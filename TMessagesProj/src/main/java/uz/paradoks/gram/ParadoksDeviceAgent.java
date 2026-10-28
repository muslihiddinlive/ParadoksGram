package uz.paradoks.gram;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Paradoks AI buyruqlari": lets the Paradoks AI bot perform real actions in this app on the user's behalf
 * (send a message, list unread chats without marking them read, post a story, inspect a received APK, delete
 * a chat). The server cannot reach the phone directly, so this agent polls the bot's server for jobs, runs
 * them locally with the user's own account, and reports the result back. See core/device_bridge.py.
 *
 * Safety: only runs while the "AI buyruqlari" toggle is on; delete_chat never deletes without confirm=true,
 * which the bot only sends after the user explicitly agreed in the conversation. The APK job never judges
 * safety — it only reports facts (package, permissions, signer) for the user to read.
 */
public final class ParadoksDeviceAgent {

    private static final long POLL_INTERVAL_MS = 4000;
    private static final long ERROR_BACKOFF_MS = 15000;
    private static final long UI_WAIT_MS = 12000;
    private static final long FILE_WAIT_MS = 45000;

    private static volatile boolean running;
    private static volatile boolean pairSent;

    private ParadoksDeviceAgent() {
    }

    static void start() {
        if (running) {
            return;
        }
        running = true;
        AndroidUtilities.runOnUIThread(ParadoksDeviceAgent::tick, 3000);
    }

    private static void tick() {
        boolean active = ParadoksConfig.deviceCommandsEnabled() && !ParadoksConfig.aiServerUrl().isEmpty()
                && UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated();
        if (!active) {
            pairSent = false;
            AndroidUtilities.runOnUIThread(ParadoksDeviceAgent::tick, POLL_INTERVAL_MS);
            return;
        }
        if (!pairSent) {
            sendPairMessage();
        }
        new Thread(ParadoksDeviceAgent::pollOnce, "ParadoksAiDevicePoll").start();
    }

    // ------------------------------------------------------------------ pairing

    private static void sendPairMessage() {
        pairSent = true;
        long botId = ParadoksConfig.aiBotId();
        if (botId == 0) {
            pairSent = false; // retry once the bot id is known (opened the AI window at least once)
            return;
        }
        String text = "\u27e6device-pair:" + ParadoksConfig.deviceToken() + "\u27e7";
        try {
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(
                    SendMessagesHelper.SendMessageParams.of(text, botId));
            ParadoksConfig.setDevicePaired(true);
            // the message is only a handshake: remove it a few seconds later so it never shows in the chat
            AndroidUtilities.runOnUIThread(() -> deleteLastOwnMessage(botId, text), 4000);
        } catch (Throwable ignore) {
        }
    }

    private static void deleteLastOwnMessage(long botId, String text) {
        try {
            TLRPC.TL_messages_search search = new TLRPC.TL_messages_search();
            search.peer = MessagesController.getInstance(UserConfig.selectedAccount).getInputPeer(botId);
            search.q = text;
            search.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            search.limit = 3;
            ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(search, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Messages) {
                    ArrayList<Integer> toDelete = new ArrayList<>();
                    for (TLRPC.Message message : ((TLRPC.messages_Messages) response).messages) {
                        if (message.out && text.equals(message.message)) {
                            toDelete.add(message.id);
                        }
                    }
                    if (!toDelete.isEmpty()) {
                        MessagesController.getInstance(UserConfig.selectedAccount)
                                .deleteMessages(toDelete, null, null, botId, true, 0, false, 0, null, 0, false, 0);
                    }
                }
            }));
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ polling loop

    private static void pollOnce() {
        try {
            HttpURLConnection connection = open("GET", "/device/poll");
            int code = connection.getResponseCode();
            if (code == 204) {
                connection.disconnect();
                scheduleNext(POLL_INTERVAL_MS);
                return;
            }
            if (code != 200) {
                connection.disconnect();
                scheduleNext(ERROR_BACKOFF_MS);
                return;
            }
            org.json.JSONObject job = new org.json.JSONObject(readAll(connection.getInputStream()));
            connection.disconnect();
            String jobId = job.getString("job_id");
            String action = job.getString("action");
            org.json.JSONObject params = job.optJSONObject("params");
            if (params == null) {
                params = new org.json.JSONObject();
            }
            org.json.JSONObject result = execute(action, params);
            postResult(jobId, result);
            scheduleNext(400); // another job may already be waiting
        } catch (Throwable t) {
            scheduleNext(ERROR_BACKOFF_MS);
        }
    }

    private static void scheduleNext(long delay) {
        AndroidUtilities.runOnUIThread(ParadoksDeviceAgent::tick, delay);
    }

    private static void postResult(String jobId, org.json.JSONObject result) {
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("job_id", jobId);
            body.put("result", result);
            HttpURLConnection connection = open("POST", "/device/result");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
            connection.disconnect();
        } catch (Throwable ignore) {
        }
    }

    private static HttpURLConnection open(String method, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(ParadoksConfig.aiServerUrl() + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("X-Device-Token", ParadoksConfig.deviceToken());
        return connection;
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    // ------------------------------------------------------------------ actions

    private static org.json.JSONObject execute(String action, org.json.JSONObject params) {
        try {
            switch (action) {
                case "send_message":
                    return doSendMessage(params.optString("target", ""), params.optString("text", ""));
                case "list_unread":
                    return doListUnread();
                case "post_last_video_story":
                    return doPostStory();
                case "find_apk_from_contact":
                    return doFindApk(params.optString("contact_hint", ""));
                case "delete_chat":
                    return doDeleteChat(params.optString("chat_ref", ""), params.optBoolean("confirm", false));
                default:
                    return error("Noma'lum amal: " + action);
            }
        } catch (Throwable t) {
            return error("Ichki xato: " + t.getMessage());
        }
    }

    private static org.json.JSONObject error(String message) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("error", message);
        } catch (Exception ignore) {
        }
        return o;
    }

    /** Runs {@code task} on the UI thread and waits for {@code complete.run()} to be called inside it. */
    private static void runAndWait(java.util.function.Consumer<Runnable> task, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                task.accept(latch::countDown);
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
        }
    }

    private static String nameOf(long dialogId) {
        MessagesController controller = MessagesController.getInstance(UserConfig.selectedAccount);
        if (dialogId > 0) {
            TLRPC.User user = controller.getUser(dialogId);
            return user == null ? ("ID " + dialogId) : UserObject.getUserName(user);
        }
        TLRPC.Chat chat = controller.getChat(-dialogId);
        return chat != null && chat.title != null ? chat.title : ("ID " + dialogId);
    }

    // ---- 1) send_message ----

    private static org.json.JSONObject doSendMessage(String target, String text) throws Exception {
        if (target.trim().isEmpty() || text.trim().isEmpty()) {
            return error("Kimga va nima yozish kerakligi aniq emas.");
        }
        long dialogId = resolveDialog(target, false);
        if (dialogId == 0) {
            return error("\"" + target + "\" nomli chat yoki odam topilmadi. Aniqroq nom yoki @username bilan qayta urining.");
        }
        AtomicReference<Boolean> sent = new AtomicReference<>(false);
        runAndWait(done -> {
            try {
                SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId));
                sent.set(true);
            } catch (Throwable ignore) {
            }
            done.run();
        }, UI_WAIT_MS);
        org.json.JSONObject result = new org.json.JSONObject();
        result.put("ok", sent.get());
        result.put("chat_title", nameOf(dialogId));
        if (!sent.get()) {
            result.put("error", "Xabarni yuborib bo'lmadi.");
        }
        return result;
    }

    /** Local-only (no network) match against open dialogs and contacts: by title, @username or phone. */
    private static long resolveDialog(String query, boolean usersOnly) {
        final String needle = query.trim().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
        final AtomicReference<Long> found = new AtomicReference<>(0L);
        runAndWait(done -> {
            MessagesController controller = MessagesController.getInstance(UserConfig.selectedAccount);
            long best = 0;
            int bestScore = -1;
            for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
                long id = dialog.id;
                if (org.telegram.messenger.DialogObject.isEncryptedDialog(id)) {
                    continue;
                }
                String title;
                String username = null;
                if (id > 0) {
                    TLRPC.User user = controller.getUser(id);
                    if (user == null || (usersOnly && user.bot)) {
                        continue;
                    }
                    title = UserObject.getUserName(user);
                    username = user.username;
                } else {
                    if (usersOnly) {
                        continue;
                    }
                    TLRPC.Chat chat = controller.getChat(-id);
                    if (chat == null) {
                        continue;
                    }
                    title = chat.title;
                    username = ChatObject.getPublicUsername(chat);
                }
                if (title == null) {
                    continue;
                }
                String low = title.toLowerCase(Locale.ROOT);
                int score = -1;
                if (username != null && username.equalsIgnoreCase(needle)) {
                    score = 100;
                } else if (low.equals(needle)) {
                    score = 90;
                } else if (low.startsWith(needle)) {
                    score = 70;
                } else if (low.contains(needle)) {
                    score = 50;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = id;
                }
            }
            found.set(best);
            done.run();
        }, UI_WAIT_MS);
        return found.get();
    }

    // ---- 2) list_unread ----

    private static org.json.JSONObject doListUnread() throws Exception {
        org.json.JSONArray chats = new org.json.JSONArray();
        runAndWait(done -> {
            MessagesController controller = MessagesController.getInstance(UserConfig.selectedAccount);
            for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
                if (org.telegram.messenger.DialogObject.isEncryptedDialog(dialog.id) || dialog.unread_count <= 0) {
                    continue;
                }
                try {
                    org.json.JSONObject row = new org.json.JSONObject();
                    row.put("title", nameOf(dialog.id));
                    row.put("unread", dialog.unread_count);
                    row.put("type", dialog.id > 0 ? "shaxsiy" : (ChatObject.isChannelAndNotMegaGroup(controller.getChat(-dialog.id)) ? "kanal" : "guruh"));
                    chats.put(row);
                } catch (Throwable ignore) {
                }
            }
            done.run();
        }, UI_WAIT_MS);
        org.json.JSONObject result = new org.json.JSONObject();
        result.put("chats", chats);
        result.put("note", "Bu ro'yxatga qarash xabarlarni o'qilgan deb belgilamaydi.");
        return result;
    }

    // ---- 3) post_last_video_story ----

    private static org.json.JSONObject doPostStory() throws Exception {
        long botId = ParadoksConfig.aiBotId();
        if (botId == 0) {
            return error("AI botining o'zi hali topilmadi.");
        }
        AtomicReference<TLRPC.Message> videoMsg = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            TLRPC.TL_messages_search search = new TLRPC.TL_messages_search();
            search.peer = MessagesController.getInstance(UserConfig.selectedAccount).getInputPeer(botId);
            search.q = "";
            search.filter = new TLRPC.TL_inputMessagesFilterVideo();
            search.limit = 10;
            ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(search, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (response instanceof TLRPC.messages_Messages) {
                        for (TLRPC.Message message : ((TLRPC.messages_Messages) response).messages) {
                            if (message.out) {
                                videoMsg.set(message);
                                break;
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }));
        });
        latch.await(UI_WAIT_MS, TimeUnit.MILLISECONDS);
        TLRPC.Message message = videoMsg.get();
        if (message == null) {
            return error("Shu suhbatda hali video topilmadi. Avval video yuborib, keyin qayta so'rang.");
        }
        File file = waitForFile(message);
        if (file == null) {
            return error("Video faylini yuklab bo'lmadi.");
        }
        long duration = 0;
        if (message.media != null && message.media.document != null && message.media.document.attributes != null) {
            for (TLRPC.DocumentAttribute attribute : message.media.document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    duration = (long) (((TLRPC.TL_documentAttributeVideo) attribute).duration * 1000);
                }
            }
        }
        AtomicReference<Boolean> uploaded = new AtomicReference<>(false);
        final long finalDuration = duration;
        runAndWait(done -> {
            try {
                org.telegram.ui.Stories.recorder.StoryEntry entry = org.telegram.ui.Stories.recorder.StoryEntry.fromVideoShoot(file, null, finalDuration);
                MessagesController.getInstance(UserConfig.selectedAccount).getStoriesController().uploadStory(entry, true);
                uploaded.set(true);
            } catch (Throwable ignore) {
            }
            done.run();
        }, UI_WAIT_MS);
        org.json.JSONObject result = new org.json.JSONObject();
        result.put("ok", uploaded.get());
        result.put("privacy", "Hammaga ko'rinadi (ParadoksGram'dagi standart sozlama)");
        if (!uploaded.get()) {
            result.put("error", "Story joylab bo'lmadi.");
        }
        return result;
    }

    private static File waitForFile(TLRPC.Message message) throws InterruptedException {
        AtomicReference<File> result = new AtomicReference<>();
        for (int i = 0; i < FILE_WAIT_MS / 500; i++) {
            runAndWait(done -> {
                File file = FileLoader.getInstance(UserConfig.selectedAccount).getPathToMessage(message);
                if (file != null && file.exists()) {
                    result.set(file);
                } else if (i == 0 && message.media != null && message.media.document != null) {
                    FileLoader.getInstance(UserConfig.selectedAccount).loadFile(message.media.document, message, FileLoader.PRIORITY_HIGH, 0);
                }
                done.run();
            }, 1000);
            if (result.get() != null) {
                break;
            }
            Thread.sleep(500);
        }
        return result.get();
    }

    // ---- 4) find_apk_from_contact ----

    private static org.json.JSONObject doFindApk(String hint) throws Exception {
        final String needle = hint.trim().toLowerCase(Locale.ROOT);
        AtomicReference<Long> candidate = new AtomicReference<>(0L);
        runAndWait(done -> {
            MessagesController controller = MessagesController.getInstance(UserConfig.selectedAccount);
            for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
                if (dialog.id <= 0 || org.telegram.messenger.DialogObject.isEncryptedDialog(dialog.id)) {
                    continue;
                }
                TLRPC.User user = controller.getUser(dialog.id);
                if (user == null) {
                    continue;
                }
                boolean deletedMatch = user.deleted && (needle.isEmpty() || needle.contains("o'chir") || needle.contains("ochir") || needle.contains("delete"));
                String name = UserObject.getUserName(user).toLowerCase(Locale.ROOT);
                boolean nameMatch = !needle.isEmpty() && name.contains(needle);
                if (deletedMatch || nameMatch) {
                    candidate.set(dialog.id);
                    if (nameMatch) {
                        break; // a name match is more specific than the generic "deleted account" guess
                    }
                }
            }
            done.run();
        }, UI_WAIT_MS);
        long dialogId = candidate.get();
        if (dialogId == 0) {
            return error("\"" + hint + "\" ga mos kontakt topilmadi (o'chirilgan hisob yoki shunday nomli odam yo'q).");
        }
        AtomicReference<TLRPC.Message> apkMsg = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            TLRPC.TL_messages_search search = new TLRPC.TL_messages_search();
            search.peer = MessagesController.getInstance(UserConfig.selectedAccount).getInputPeer(dialogId);
            search.q = "";
            search.filter = new TLRPC.TL_inputMessagesFilterDocument();
            search.limit = 20;
            ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(search, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (response instanceof TLRPC.messages_Messages) {
                        for (TLRPC.Message message : ((TLRPC.messages_Messages) response).messages) {
                            if (message.out || message.media == null || message.media.document == null) {
                                continue;
                            }
                            String name = org.telegram.messenger.FileLoader.getDocumentFileName(message.media.document);
                            boolean apk = "application/vnd.android.package-archive".equals(message.media.document.mime_type)
                                    || (name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk"));
                            if (apk) {
                                apkMsg.set(message);
                                break;
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }));
        });
        latch.await(UI_WAIT_MS, TimeUnit.MILLISECONDS);
        TLRPC.Message message = apkMsg.get();
        if (message == null) {
            org.json.JSONObject result = error("\"" + nameOf(dialogId) + "\" dan .apk fayl topilmadi.");
            result.put("chat_ref", String.valueOf(dialogId));
            result.put("chat_title", nameOf(dialogId));
            return result;
        }
        File file = waitForFile(message);
        org.json.JSONObject result = new org.json.JSONObject();
        result.put("chat_ref", String.valueOf(dialogId));
        result.put("chat_title", nameOf(dialogId));
        result.put("file_name", org.telegram.messenger.FileLoader.getDocumentFileName(message.media.document));
        result.put("size_bytes", message.media.document.size);
        if (file == null) {
            result.put("error", "APK fayli hali yuklanmagan (ulanish sekin bo'lishi mumkin). Birozdan keyin qayta so'rang.");
            return result;
        }
        try {
            android.content.Context context = ApplicationLoader.applicationContext;
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.GET_PERMISSIONS | (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            if (info == null) {
                result.put("parse_error", "Fayl haqiqiy APK ko'rinishida emas yoki buzilgan.");
                return result;
            }
            result.put("package_name", info.packageName);
            result.put("version_name", info.versionName);
            result.put("app_label", info.applicationInfo != null ? pm.getApplicationLabel(info.applicationInfo).toString() : null);
            org.json.JSONArray permissions = new org.json.JSONArray();
            if (info.requestedPermissions != null) {
                for (String permission : info.requestedPermissions) {
                    permissions.put(permission);
                }
            }
            result.put("requested_permissions", permissions);
            result.put("permission_count", permissions.length());
            Set<String> hashes = new HashSet<>();
            android.content.pm.Signature[] signatures = Build.VERSION.SDK_INT >= 28
                    ? (info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : null)
                    : info.signatures;
            if (signatures != null) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (android.content.pm.Signature signature : signatures) {
                    byte[] hash = digest.digest(signature.toByteArray());
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hash) {
                        sb.append(String.format(Locale.US, "%02x", b));
                    }
                    hashes.add(sb.toString());
                }
            }
            result.put("signer_sha256", new org.json.JSONArray(hashes));
            boolean sameAsParadoksGram = hashes.contains(appSignatureHash(context));
            result.put("signed_same_as_this_app", sameAsParadoksGram);
        } catch (Throwable t) {
            result.put("parse_error", "Tahlil qilib bo'lmadi: " + t.getMessage());
        }
        return result;
    }

    private static String appSignatureHash(android.content.Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), flags);
            android.content.pm.Signature[] signatures = Build.VERSION.SDK_INT >= 28
                    ? (info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : null)
                    : info.signatures;
            if (signatures == null || signatures.length == 0) {
                return "";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---- 5) delete_chat ----

    private static org.json.JSONObject doDeleteChat(String chatRef, boolean confirm) throws Exception {
        long dialogId;
        try {
            dialogId = Long.parseLong(chatRef);
        } catch (NumberFormatException e) {
            return error("Chat identifikatori noto'g'ri.");
        }
        String title = nameOf(dialogId);
        org.json.JSONObject result = new org.json.JSONObject();
        if (!confirm) {
            result.put("needs_confirmation", true);
            result.put("chat_title", title);
            result.put("chat_ref", chatRef);
            return result;
        }
        AtomicReference<Boolean> deleted = new AtomicReference<>(false);
        runAndWait(done -> {
            try {
                MessagesController.getInstance(UserConfig.selectedAccount).deleteDialog(dialogId, 0, true);
                deleted.set(true);
            } catch (Throwable ignore) {
            }
            done.run();
        }, UI_WAIT_MS);
        result.put("deleted", deleted.get());
        result.put("chat_title", title);
        return result;
    }
}
