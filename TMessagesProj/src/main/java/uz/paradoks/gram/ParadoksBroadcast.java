package uz.paradoks.gram;

import android.content.SharedPreferences;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Broadcast panel logic: one message (text and/or one media file) sent to many chats at once.
 *
 * - Recipients are chosen by hand or taken from a Telegram folder. Channels are only used when the user
 *   may post there (creator / admin with the post right); chats where writing is not allowed are skipped.
 * - Every send is remembered (text, media file, recipients) so it can be loaded and sent again later
 *   or deleted from the cache. The media file is kept in the app's own folder while an entry uses it.
 * - Messages are queued through Telegram's normal sending code, one chat at a time with a short pause.
 */
final class ParadoksBroadcast {

    static final int MAX_HISTORY = 20;
    static final int CAPTION_LIMIT = 1024;
    private static final long SEND_PAUSE_MS = 700;
    private static final String PREFS = "paradoks_broadcast";
    private static final String KEY_HISTORY = "history";

    // ------------------------------------------------------------------ model

    static final class Recipient {
        final long dialogId;
        final String title;

        Recipient(long dialogId, String title) {
            this.dialogId = dialogId;
            this.title = title;
        }
    }

    static final class Draft {
        String text = "";
        String mediaPath;
        String mediaName;
        String mime;
        final LinkedHashMap<Long, Recipient> recipients = new LinkedHashMap<>();

        Draft copy() {
            Draft d = new Draft();
            d.text = text;
            d.mediaPath = mediaPath;
            d.mediaName = mediaName;
            d.mime = mime;
            d.recipients.putAll(recipients);
            return d;
        }

        boolean hasMedia() {
            return mediaPath != null && new File(mediaPath).exists();
        }
    }

    static final class Entry {
        long time;
        int sent;
        Draft draft = new Draft();
    }

    static final class AddResult {
        int added;
        final ArrayList<String> skipped = new ArrayList<>();
    }

    // ------------------------------------------------------------------ recipients

    static String titleOf(int account, long dialogId) {
        MessagesController mc = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.User user = mc.getUser(dialogId);
            if (user != null) {
                String name = UserObject.getUserName(user);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } else {
            TLRPC.Chat chat = mc.getChat(-dialogId);
            if (chat != null && chat.title != null && !chat.title.isEmpty()) {
                return chat.title;
            }
        }
        return "ID " + Math.abs(dialogId);
    }

    /** Returns null when the chat can receive the message, otherwise a short reason why it cannot. */
    static String checkSendable(int account, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return "maxfiy chat";
        }
        MessagesController mc = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.User user = mc.getUser(dialogId);
            if (user == null) {
                return "topilmadi";
            }
            if (UserObject.isDeleted(user)) {
                return "o'chirilgan akkaunt";
            }
            return null;
        }
        TLRPC.Chat chat = mc.getChat(-dialogId);
        if (chat == null) {
            return "topilmadi";
        }
        if (ChatObject.isNotInChat(chat)) {
            return "a'zo emassiz";
        }
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            return ChatObject.canWriteToChat(chat) ? null : "kanal, post huquqi yo'q";
        }
        if (!ChatObject.canWriteToChat(chat) || !ChatObject.canSendMessages(chat)) {
            return "yozish cheklangan";
        }
        return null;
    }

    static AddResult addDialogs(int account, Draft draft, Collection<Long> ids) {
        AddResult result = new AddResult();
        for (Long id : ids) {
            if (id == null || id == 0 || draft.recipients.containsKey(id)) {
                continue;
            }
            String reason = checkSendable(account, id);
            String title = titleOf(account, id);
            if (reason == null) {
                draft.recipients.put(id, new Recipient(id, title));
                result.added++;
            } else {
                result.skipped.add(title + " — " + reason);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ folders

    /** The user's folders, without the built-in "all chats" one (that would mean every chat). */
    static ArrayList<MessagesController.DialogFilter> folders(int account) {
        ArrayList<MessagesController.DialogFilter> result = new ArrayList<>();
        for (MessagesController.DialogFilter f : new ArrayList<>(MessagesController.getInstance(account).dialogFilters)) {
            if (f != null && !f.isDefault()) {
                result.add(f);
            }
        }
        return result;
    }

    static ArrayList<Long> folderDialogIds(MessagesController.DialogFilter filter) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        try {
            for (TLRPC.Dialog d : new ArrayList<>(filter.dialogs)) {
                if (d != null) {
                    ids.add(d.id);
                }
            }
            ids.addAll(filter.alwaysShow);
            ids.removeAll(filter.neverShow);
        } catch (Throwable ignore) {
        }
        return new ArrayList<>(ids);
    }

    // ------------------------------------------------------------------ history (cache)

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    static File mediaDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "paradoks_broadcast");
        dir.mkdirs();
        return dir;
    }

    static ArrayList<Entry> history() {
        ArrayList<Entry> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs().getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Entry e = new Entry();
                e.time = o.optLong("time");
                e.sent = o.optInt("sent");
                e.draft.text = o.optString("text", "");
                e.draft.mediaPath = o.has("media") ? o.optString("media") : null;
                e.draft.mediaName = o.has("media_name") ? o.optString("media_name") : null;
                e.draft.mime = o.has("mime") ? o.optString("mime") : null;
                JSONArray rec = o.optJSONArray("recipients");
                if (rec != null) {
                    for (int j = 0; j < rec.length(); j++) {
                        JSONObject r = rec.getJSONObject(j);
                        long id = r.optLong("id");
                        if (id != 0) {
                            e.draft.recipients.put(id, new Recipient(id, r.optString("title", "ID " + Math.abs(id))));
                        }
                    }
                }
                list.add(e);
            }
        } catch (Throwable ignore) {
        }
        return list;
    }

    private static void saveHistory(ArrayList<Entry> list) {
        JSONArray array = new JSONArray();
        try {
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("time", e.time);
                o.put("sent", e.sent);
                o.put("text", e.draft.text);
                if (e.draft.mediaPath != null) {
                    o.put("media", e.draft.mediaPath);
                    o.put("media_name", e.draft.mediaName);
                    o.put("mime", e.draft.mime);
                }
                JSONArray rec = new JSONArray();
                for (Recipient r : e.draft.recipients.values()) {
                    JSONObject ro = new JSONObject();
                    ro.put("id", r.dialogId);
                    ro.put("title", r.title);
                    rec.put(ro);
                }
                o.put("recipients", rec);
                array.put(o);
            }
        } catch (Throwable ignore) {
        }
        prefs().edit().putString(KEY_HISTORY, array.toString()).apply();
    }

    private static String signature(Draft d) {
        ArrayList<Long> ids = new ArrayList<>(d.recipients.keySet());
        Collections.sort(ids);
        return d.text + "|" + d.mediaPath + "|" + ids;
    }

    /** Remembers a finished send (newest first). An identical earlier entry is replaced. */
    static void remember(Draft draft, int sent) {
        ArrayList<Entry> list = history();
        String sig = signature(draft);
        ArrayList<String> dropped = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (signature(list.get(i).draft).equals(sig)) {
                list.remove(i); // same media path stays referenced by the new entry
            }
        }
        Entry entry = new Entry();
        entry.time = System.currentTimeMillis();
        entry.sent = sent;
        entry.draft = draft.copy();
        list.add(0, entry);
        while (list.size() > MAX_HISTORY) {
            Entry removed = list.remove(list.size() - 1);
            if (removed.draft.mediaPath != null) {
                dropped.add(removed.draft.mediaPath);
            }
        }
        saveHistory(list);
        for (String path : dropped) {
            deleteMediaIfUnused(path);
        }
    }

    static void deleteEntry(int index) {
        ArrayList<Entry> list = history();
        if (index < 0 || index >= list.size()) {
            return;
        }
        Entry removed = list.remove(index);
        saveHistory(list);
        deleteMediaIfUnused(removed.draft.mediaPath);
    }

    static void clearHistory() {
        ArrayList<Entry> list = history();
        saveHistory(new ArrayList<>());
        for (Entry e : list) {
            deleteMediaIfUnused(e.draft.mediaPath);
        }
    }

    static boolean isMediaReferenced(String path) {
        if (path == null) {
            return false;
        }
        for (Entry e : history()) {
            if (path.equals(e.draft.mediaPath)) {
                return true;
            }
        }
        return false;
    }

    /** Deletes a cached media file only when no history entry uses it any more. */
    static void deleteMediaIfUnused(String path) {
        if (path == null || isMediaReferenced(path)) {
            return;
        }
        File file = new File(path);
        // only ever touch files inside our own folder
        File dir = mediaDir();
        if (file.getParentFile() != null && file.getParentFile().equals(dir)) {
            file.delete();
        }
    }

    static String guessMime(String fileName) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(fileName != null ? fileName.replace(" ", "_") : "");
        if (ext != null && !ext.isEmpty()) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.US));
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ sending

    interface ProgressListener {
        void onProgress(int done, int total, String name);
    }

    interface DoneListener {
        void onDone(int queued, int skipped, boolean cancelled);
    }

    static final class SendJob implements Runnable {
        private final int account;
        private final Draft draft;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        ProgressListener progress;
        DoneListener done;

        SendJob(int account, Draft draft) {
            this.account = account;
            this.draft = draft;
        }

        @Override
        public void run() {
            ArrayList<Recipient> list = new ArrayList<>(draft.recipients.values());
            int queued = 0;
            int skipped = 0;
            try {
                for (int i = 0; i < list.size() && !cancelled.get(); i++) {
                    Recipient r = list.get(i);
                    if (checkSendable(account, r.dialogId) != null) {
                        skipped++;
                        continue;
                    }
                    if (progress != null) {
                        progress.onProgress(i, list.size(), r.title);
                    }
                    final boolean[] ok = new boolean[1];
                    final CountDownLatch latch = new CountDownLatch(1);
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            sendOne(account, draft, r.dialogId);
                            ok[0] = true;
                        } catch (Throwable ignore) {
                        }
                        latch.countDown();
                    });
                    latch.await(15, TimeUnit.SECONDS);
                    if (ok[0]) {
                        queued++;
                    } else {
                        skipped++;
                    }
                    Thread.sleep(SEND_PAUSE_MS);
                }
            } catch (InterruptedException e) {
                cancelled.set(true);
            }
            if (done != null) {
                done.onDone(queued, skipped, cancelled.get());
            }
        }
    }

    /** Must run on the UI thread, like the rest of Telegram's sending code. */
    static void sendOne(int account, Draft draft, long dialogId) {
        String text = draft.text != null ? draft.text.trim() : "";
        if (!draft.hasMedia()) {
            if (!text.isEmpty()) {
                sendText(account, dialogId, text);
            }
            return;
        }
        AccountInstance instance = AccountInstance.getInstance(account);
        String path = draft.mediaPath;
        String mime = draft.mime != null ? draft.mime : guessMime(draft.mediaName);
        boolean longText = text.length() > CAPTION_LIMIT;
        String caption = longText ? "" : text;
        boolean image = mime.equals("image/jpeg") || mime.equals("image/png");
        boolean video = mime.startsWith("video/") && !mime.equals("video/webm");
        if (image || video) {
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = path;
            info.caption = caption;
            info.isVideo = video;
            ArrayList<SendMessagesHelper.SendingMediaInfo> infos = new ArrayList<>();
            infos.add(info);
            SendMessagesHelper.prepareSendingMedia(instance, infos, dialogId, null, null, null, null, false, false, null, true, 0, 0, 0, false, null, null, 0L, false, 0L, 0L, null);
        } else {
            SendMessagesHelper.prepareSendingDocument(instance, path, path, null, caption, mime, dialogId, null, null, null, null, null, true, 0, null, null, false);
        }
        if (longText) {
            sendText(account, dialogId, text);
        }
    }

    private static void sendText(int account, long dialogId, String text) {
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId, null, null, null, false, null, null, null, true, 0, 0, null, false));
    }

    private ParadoksBroadcast() {
    }
}
