package uz.paradoks.gram;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Chat export: the whole history of one chat is loaded from the server page by page and saved as
 * JSON and/or HTML together with its media files.
 *
 * Output folder (app's own external files directory):
 *   paradoks_export/&lt;chat&gt;_&lt;date&gt;/messages.json, messages.html (messages2.html, ...), media/
 *
 * Chats with restricted saving/forwarding (content protection) and secret chats are not exported,
 * and self-destructing media is never copied.
 */
public final class ParadoksExport {

    private static final long MAX_MEDIA_SIZE = 200L * 1024 * 1024;
    private static final int PAGE_LIMIT = 100;
    private static final int HTML_PAGE_SIZE = 1000;

    private static volatile boolean running;

    // ------------------------------------------------------------------ entry point

    public static void start(BaseFragment fragment, int account, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        if (running) {
            toast(context, "Eksport allaqachon davom etmoqda");
            return;
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            toast(context, "Maxfiy chatlarni eksport qilib bo'lmaydi");
            return;
        }
        if (isProtected(account, dialogId)) {
            toast(context, "Bu chatda saqlash cheklangan, eksport qilib bo'lmaydi");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Chatni eksport qilish");
        builder.setItems(new CharSequence[]{"JSON + HTML (tavsiya)", "Faqat JSON", "Faqat HTML"}, (d, which) -> {
            begin(fragment, account, dialogId, which != 2, which != 1);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static boolean isProtected(int account, long dialogId) {
        if (dialogId >= 0) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        return chat != null && chat.noforwards;
    }

    private static void begin(BaseFragment fragment, int account, long dialogId, boolean json, boolean html) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        running = true;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        final TextView status = new TextView(context);
        status.setTextSize(15);
        status.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        status.setText("Boshlanmoqda...");
        layout.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = AndroidUtilities.dp(16);
        layout.addView(bar, barParams);

        final Job job = new Job(account, dialogId, json, html);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Chat eksporti");
        builder.setView(layout);
        builder.setNegativeButton("Bekor qilish", (d, w) -> job.cancelled.set(true));
        final AlertDialog dialog = builder.create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        job.listener = (text, fraction) -> AndroidUtilities.runOnUIThread(() -> {
            status.setText(text);
            if (fraction < 0) {
                bar.setIndeterminate(true);
            } else {
                bar.setIndeterminate(false);
                bar.setProgress((int) (fraction * 1000));
            }
        });
        job.finish = result -> AndroidUtilities.runOnUIThread(() -> {
            running = false;
            try {
                dialog.dismiss();
            } catch (Throwable ignore) {
            }
            showResult(result);
        });

        fragment.showDialog(dialog);
        Thread thread = new Thread(job, "ParadoksExport");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    private static void showResult(Result result) {
        BaseFragment last = LaunchActivity.getLastFragment();
        Context context = last != null ? last.getParentActivity() : null;
        if (context == null) {
            toast(ApplicationLoader.applicationContext, result.ok ? "Eksport tayyor: " + result.dir.getAbsolutePath() : "Eksport tugamadi");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        if (result.ok) {
            builder.setTitle("Eksport tayyor");
            builder.setMessage(result.messages + " ta xabar, " + result.mediaSaved + " ta media saqlandi"
                    + (result.mediaProblems > 0 ? " (" + result.mediaProblems + " tasi saqlanmadi)" : "")
                    + ".\n\n" + result.dir.getAbsolutePath());
            final Context ctx = context;
            final File dir = result.dir;
            builder.setPositiveButton("Ulashish (ZIP)", (d, w) -> shareZip(ctx, dir));
            builder.setNegativeButton("Yopish", null);
        } else if (result.cancelled) {
            builder.setTitle("Bekor qilindi");
            builder.setMessage("Eksport to'xtatildi, yarim fayllar o'chirildi.");
            builder.setNegativeButton("Yopish", null);
        } else {
            builder.setTitle("Eksport xatosi");
            builder.setMessage(result.error != null ? result.error : "Noma'lum xato");
            builder.setNegativeButton("Yopish", null);
        }
        last.showDialog(builder.create());
    }

    private static void shareZip(Context context, File dir) {
        toast(context, "ZIP tayyorlanmoqda...");
        new Thread(() -> {
            try {
                File zip = new File(dir.getParentFile(), dir.getName() + ".zip");
                zipDirectory(dir, zip);
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", zip);
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("application/zip");
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        context.startActivity(Intent.createChooser(intent, "Eksportni ulashish"));
                    } catch (Throwable e) {
                        toast(context, "Ulashib bo'lmadi: " + zip.getAbsolutePath());
                    }
                });
            } catch (Throwable e) {
                AndroidUtilities.runOnUIThread(() -> toast(context, "ZIP yaratib bo'lmadi"));
            }
        }, "ParadoksExportZip").start();
    }

    private static void zipDirectory(File dir, File zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zip.setLevel(Deflater.BEST_SPEED);
            addToZip(zip, dir, dir.getName());
        }
    }

    private static void addToZip(ZipOutputStream zip, File file, String name) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(zip, child, name + "/" + child.getName());
                }
            }
            return;
        }
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    private static void toast(Context context, String text) {
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ plumbing

    interface ProgressListener {
        void onProgress(String text, float fraction);
    }

    interface FinishListener {
        void onFinish(Result result);
    }

    static final class Result {
        boolean ok;
        boolean cancelled;
        String error;
        File dir;
        int messages;
        int mediaSaved;
        int mediaProblems;
    }

    /** Everything known about the media of one message. */
    static final class MediaInfo {
        String kind = "other";
        String file;       // relative path inside the export folder
        String status;     // null = saved (or nothing to save), otherwise the reason it is missing
        String fileName;
        String mime;
        long size;
        int width;
        int height;
        double duration;
        String title;
        String performer;
        JSONObject extra = new JSONObject();
        ArrayList<String> details = new ArrayList<>();
        boolean hasFile;   // true when a file was expected for this media
    }

    // ------------------------------------------------------------------ the export job

    private static final class Job implements Runnable {
        final int account;
        final long dialogId;
        final boolean wantJson;
        final boolean wantHtml;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        ProgressListener listener;
        FinishListener finish;

        File outDir;
        File mediaDir;
        File partsDir;
        String chatTitle;

        final HashMap<Long, String> names = new HashMap<>();
        final HashMap<Long, TLRPC.User> users = new HashMap<>();
        final HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
        final SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        final SimpleDateFormat shortFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        int mediaSaved;
        int mediaProblems;
        int done;
        int total = -1;

        Job(int account, long dialogId, boolean wantJson, boolean wantHtml) {
            this.account = account;
            this.dialogId = dialogId;
            this.wantJson = wantJson;
            this.wantHtml = wantHtml;
        }

        void progress(String text, float fraction) {
            if (listener != null) {
                listener.onProgress(text, fraction);
            }
        }

        @Override
        public void run() {
            Result result = new Result();
            try {
                int chunks = fetchAll();
                if (!cancelled.get()) {
                    progress("Fayllar tayyorlanmoqda...", -1);
                    assemble(chunks);
                }
                result.cancelled = cancelled.get();
                result.ok = !result.cancelled;
            } catch (Throwable e) {
                result.ok = false;
                result.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            result.dir = outDir;
            result.messages = done;
            result.mediaSaved = mediaSaved;
            result.mediaProblems = mediaProblems;
            if (partsDir != null) {
                deleteRecursive(partsDir);
            }
            if (!result.ok && outDir != null) {
                deleteRecursive(outDir);
            }
            if (finish != null) {
                finish.onFinish(result);
            }
        }

        // -------------------------------------------------------------- fetching

        private int fetchAll() throws Exception {
            MessagesController mc = MessagesController.getInstance(account);
            chatTitle = resolveChatTitle();
            File base = ApplicationLoader.applicationContext.getExternalFilesDir(null);
            if (base == null) {
                base = ApplicationLoader.applicationContext.getFilesDir();
            }
            File root = new File(base, "paradoks_export");
            outDir = new File(root, safeName(chatTitle) + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));
            mediaDir = new File(outDir, "media");
            partsDir = new File(outDir, ".parts");
            if (!mediaDir.mkdirs() || !partsDir.mkdirs()) {
                throw new IOException("Papka yaratib bo'lmadi: " + outDir.getAbsolutePath());
            }
            TLRPC.InputPeer peer = mc.getInputPeer(dialogId);
            if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
                throw new IOException("Chat topilmadi");
            }

            int offsetId = 0;
            int chunk = 0;
            while (!cancelled.get()) {
                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = peer;
                req.offset_id = offsetId;
                req.limit = PAGE_LIMIT;
                TLRPC.messages_Messages res = loadPage(req);
                if (res == null) {
                    break; // cancelled
                }
                collectNames(res);
                if (res.messages.isEmpty()) {
                    break;
                }
                if (total < 0) {
                    total = Math.max(res.count, res.messages.size());
                }
                int minId = Integer.MAX_VALUE;
                for (TLRPC.Message m : res.messages) {
                    if (m != null && m.id < minId) {
                        minId = m.id;
                    }
                }
                if (minId == Integer.MAX_VALUE || (offsetId != 0 && minId >= offsetId)) {
                    break;
                }
                offsetId = minId;

                // pages come newest-first; the chunk itself is written oldest-first
                ArrayList<TLRPC.Message> page = new ArrayList<>(res.messages);
                Collections.sort(page, (a, b) -> a.id - b.id);

                StringBuilder jsonPart = new StringBuilder();
                StringBuilder htmlPart = new StringBuilder();
                for (TLRPC.Message msg : page) {
                    if (cancelled.get()) {
                        break;
                    }
                    if (msg == null || msg instanceof TLRPC.TL_messageEmpty) {
                        continue;
                    }
                    processMessage(msg, jsonPart, htmlPart);
                    done++;
                }
                if (cancelled.get()) {
                    break;
                }
                if (wantJson) {
                    writeFile(new File(partsDir, "json_" + chunk + ".part"), jsonPart.toString());
                }
                if (wantHtml) {
                    writeFile(new File(partsDir, "html_" + chunk + ".part"), htmlPart.toString());
                }
                chunk++;
                progress("Xabarlar: " + done + (total > 0 ? " / " + Math.max(total, done) : ""),
                        total > 0 ? Math.min(1f, done / (float) Math.max(total, done)) : -1);
                Thread.sleep(120);
            }
            return chunk;
        }

        private TLRPC.messages_Messages loadPage(TLRPC.TL_messages_getHistory req) throws Exception {
            for (int attempt = 0; attempt < 8 && !cancelled.get(); attempt++) {
                final Object[] result = new Object[2];
                final CountDownLatch latch = new CountDownLatch(1);
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                    result[0] = response;
                    result[1] = error;
                    latch.countDown();
                });
                while (!latch.await(500, TimeUnit.MILLISECONDS)) {
                    if (cancelled.get()) {
                        return null;
                    }
                }
                TLRPC.TL_error error = (TLRPC.TL_error) result[1];
                if (error == null && result[0] instanceof TLRPC.messages_Messages) {
                    return (TLRPC.messages_Messages) result[0];
                }
                int waitSeconds = 2;
                if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                    try {
                        waitSeconds = Integer.parseInt(error.text.substring("FLOOD_WAIT_".length())) + 1;
                    } catch (Throwable ignore) {
                        waitSeconds = 30;
                    }
                    waitSeconds = Math.min(waitSeconds, 300);
                }
                for (int s = waitSeconds; s > 0 && !cancelled.get(); s--) {
                    progress("Telegram kutishni so'radi: " + s + " s", -1);
                    Thread.sleep(1000);
                }
            }
            if (cancelled.get()) {
                return null;
            }
            throw new IOException("Xabarlarni yuklab bo'lmadi (internetni tekshiring)");
        }

        private void collectNames(TLRPC.messages_Messages res) {
            for (TLRPC.User u : res.users) {
                if (u != null) {
                    users.put(u.id, u);
                    names.put(u.id, UserObject.getUserName(u));
                }
            }
            for (TLRPC.Chat c : res.chats) {
                if (c != null) {
                    chats.put(c.id, c);
                    names.put(-c.id, c.title != null ? c.title : "");
                }
            }
        }

        private String resolveChatTitle() {
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
            return "chat_" + Math.abs(dialogId);
        }

        private String chatType() {
            if (dialogId > 0) {
                TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
                return user != null && user.bot ? "bot" : "private";
            }
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            if (chat != null && chat.broadcast) {
                return "channel";
            }
            return "group";
        }

        // -------------------------------------------------------------- one message

        private static long peerKey(TLRPC.Peer p) {
            if (p == null) {
                return 0;
            }
            if (p.user_id != 0) {
                return p.user_id;
            }
            if (p.chat_id != 0) {
                return -p.chat_id;
            }
            if (p.channel_id != 0) {
                return -p.channel_id;
            }
            return 0;
        }

        private static String peerRef(TLRPC.Peer p) {
            if (p == null) {
                return null;
            }
            if (p.user_id != 0) {
                return "user" + p.user_id;
            }
            if (p.chat_id != 0) {
                return "chat" + p.chat_id;
            }
            if (p.channel_id != 0) {
                return "channel" + p.channel_id;
            }
            return null;
        }

        private String nameOf(TLRPC.Peer p) {
            if (p == null) {
                return null;
            }
            long key = peerKey(p);
            String name = names.get(key);
            if (name != null) {
                return name;
            }
            MessagesController mc = MessagesController.getInstance(account);
            if (p.user_id != 0) {
                TLRPC.User user = mc.getUser(p.user_id);
                if (user != null) {
                    name = UserObject.getUserName(user);
                }
            } else if (p.chat_id != 0) {
                TLRPC.Chat chat = mc.getChat(p.chat_id);
                if (chat != null) {
                    name = chat.title;
                }
            } else if (p.channel_id != 0) {
                TLRPC.Chat chat = mc.getChat(p.channel_id);
                if (chat != null) {
                    name = chat.title;
                }
            }
            if (name == null || name.isEmpty()) {
                name = "ID " + Math.abs(key);
            }
            names.put(key, name);
            return name;
        }

        private String serviceText(TLRPC.Message msg) {
            try {
                MessageObject object = new MessageObject(account, msg, users, chats, false, false);
                if (object.messageText != null && object.messageText.length() > 0) {
                    return object.messageText.toString();
                }
            } catch (Throwable ignore) {
            }
            return "[xizmat xabari]";
        }

        private void processMessage(TLRPC.Message msg, StringBuilder json, StringBuilder html) {
            boolean service = msg instanceof TLRPC.TL_messageService;
            String from = nameOf(msg.from_id);
            String fromRef = peerRef(msg.from_id);
            if (from == null) {
                from = msg.post_author != null && !msg.post_author.isEmpty() ? msg.post_author : chatTitle;
                fromRef = peerRef(msg.peer_id);
            }
            String text = service ? serviceText(msg) : (msg.message != null ? msg.message : "");

            MediaInfo media = null;
            if (!service && msg.media != null) {
                media = describeMedia(msg);
                if (media != null && media.hasFile) {
                    if (media.file != null) {
                        mediaSaved++;
                    } else {
                        mediaProblems++;
                    }
                }
            }

            String fwdName = null;
            int fwdDate = 0;
            if (msg.fwd_from != null) {
                fwdName = nameOf(msg.fwd_from.from_id);
                if (fwdName == null) {
                    fwdName = msg.fwd_from.from_name;
                }
                if (fwdName == null) {
                    fwdName = "?";
                }
                fwdDate = msg.fwd_from.date;
            }
            int replyId = msg.reply_to != null ? msg.reply_to.reply_to_msg_id : 0;
            ArrayList<String[]> reactions = new ArrayList<>();
            if (msg.reactions != null && msg.reactions.results != null) {
                for (TLRPC.ReactionCount rc : msg.reactions.results) {
                    if (rc == null) {
                        continue;
                    }
                    String emoji = rc.reaction instanceof TLRPC.TL_reactionEmoji ? ((TLRPC.TL_reactionEmoji) rc.reaction).emoticon : "custom";
                    reactions.add(new String[]{emoji, String.valueOf(rc.count)});
                }
            }

            if (wantJson) {
                json.append(buildJson(msg, service, from, fromRef, text, media, fwdName, fwdDate, replyId, reactions)).append('\n');
            }
            if (wantHtml) {
                html.append(buildHtml(msg, service, from, text, media, fwdName, replyId, reactions)).append('\n');
            }
        }

        // -------------------------------------------------------------- media

        private MediaInfo describeMedia(TLRPC.Message msg) {
            TLRPC.MessageMedia m = msg.media;
            if (m == null || m instanceof TLRPC.TL_messageMediaEmpty) {
                return null;
            }
            MediaInfo mi = new MediaInfo();
            if (m instanceof TLRPC.TL_messageMediaPhoto) {
                mi.kind = "photo";
                mi.hasFile = true;
                if (!(m.photo instanceof TLRPC.TL_photo)) {
                    mi.status = "unavailable";
                    return mi;
                }
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, AndroidUtilities.getPhotoSize());
                if (size == null) {
                    mi.status = "unavailable";
                    return mi;
                }
                mi.size = size.size;
                mi.width = size.w;
                mi.height = size.h;
                mi.mime = "image/jpeg";
                if (msg.noforwards) {
                    mi.status = "protected";
                } else if (m.ttl_seconds != 0) {
                    mi.status = "self_destructing";
                } else {
                    obtain(msg, null, m.photo, size, size.size, "photo.jpg", mi);
                }
                return mi;
            }
            if (m instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Document doc = m.document;
                mi.hasFile = true;
                if (doc == null || doc instanceof TLRPC.TL_documentEmpty) {
                    mi.status = "unavailable";
                    return mi;
                }
                mi.mime = doc.mime_type;
                mi.size = doc.size;
                String attrName = null;
                for (TLRPC.DocumentAttribute a : doc.attributes) {
                    if (a instanceof TLRPC.TL_documentAttributeFilename) {
                        attrName = a.file_name;
                    } else if (a instanceof TLRPC.TL_documentAttributeVideo) {
                        mi.width = a.w;
                        mi.height = a.h;
                        mi.duration = a.duration;
                    } else if (a instanceof TLRPC.TL_documentAttributeAudio) {
                        mi.duration = a.duration;
                        mi.title = a.title;
                        mi.performer = a.performer;
                    } else if (a instanceof TLRPC.TL_documentAttributeImageSize) {
                        mi.width = a.w;
                        mi.height = a.h;
                    }
                }
                if (MessageObject.isVoiceDocument(doc)) {
                    mi.kind = "voice";
                } else if (MessageObject.isRoundVideoDocument(doc)) {
                    mi.kind = "video_message";
                } else if (MessageObject.isStickerDocument(doc)) {
                    mi.kind = "sticker";
                } else if (MessageObject.isGifDocument(doc)) {
                    mi.kind = "animation";
                } else if (MessageObject.isVideoDocument(doc)) {
                    mi.kind = "video";
                } else if (MessageObject.isMusicDocument(doc)) {
                    mi.kind = "audio";
                } else {
                    mi.kind = "file";
                }
                mi.fileName = attrName;
                if (msg.noforwards) {
                    mi.status = "protected";
                } else if (m.ttl_seconds != 0) {
                    mi.status = "self_destructing";
                } else if (doc.size > MAX_MEDIA_SIZE) {
                    mi.status = "too_large";
                } else {
                    obtain(msg, doc, null, null, doc.size, attrName, mi);
                }
                return mi;
            }
            if (m instanceof TLRPC.TL_messageMediaGeo || m instanceof TLRPC.TL_messageMediaGeoLive || m instanceof TLRPC.TL_messageMediaVenue) {
                mi.kind = m instanceof TLRPC.TL_messageMediaVenue ? "venue" : (m instanceof TLRPC.TL_messageMediaGeoLive ? "live_location" : "location");
                if (m.geo != null) {
                    put(mi.extra, "latitude", m.geo.lat);
                    put(mi.extra, "longitude", m.geo._long);
                    mi.details.add("https://maps.google.com/?q=" + m.geo.lat + "," + m.geo._long);
                }
                if (m instanceof TLRPC.TL_messageMediaVenue) {
                    put(mi.extra, "venue_title", m.title);
                    put(mi.extra, "venue_address", m.address);
                    mi.details.add(0, (m.title != null ? m.title : "") + (m.address != null ? ", " + m.address : ""));
                }
                return mi;
            }
            if (m instanceof TLRPC.TL_messageMediaContact) {
                mi.kind = "contact";
                String name = ((m.first_name != null ? m.first_name : "") + " " + (m.last_name != null ? m.last_name : "")).trim();
                put(mi.extra, "contact_name", name);
                put(mi.extra, "phone_number", m.phone_number);
                mi.details.add(name + (m.phone_number != null ? " " + m.phone_number : ""));
                return mi;
            }
            if (m instanceof TLRPC.TL_messageMediaPoll) {
                mi.kind = "poll";
                TLRPC.TL_messageMediaPoll pm = (TLRPC.TL_messageMediaPoll) m;
                if (pm.poll != null) {
                    String question = pm.poll.question != null ? pm.poll.question.text : "";
                    put(mi.extra, "question", question);
                    JSONArray answers = new JSONArray();
                    mi.details.add(question);
                    for (TLRPC.PollAnswer answer : pm.poll.answers) {
                        String t = answer.text != null ? answer.text.text : "";
                        answers.put(t);
                        mi.details.add("• " + t);
                    }
                    put(mi.extra, "answers", answers);
                }
                return mi;
            }
            if (m instanceof TLRPC.TL_messageMediaWebPage) {
                mi.kind = "webpage";
                TLRPC.WebPage page = m.webpage;
                if (page != null) {
                    put(mi.extra, "url", page.url);
                    put(mi.extra, "site_name", page.site_name);
                    put(mi.extra, "title", page.title);
                    put(mi.extra, "description", page.description);
                    StringBuilder line = new StringBuilder();
                    if (page.site_name != null) {
                        line.append(page.site_name).append(": ");
                    }
                    if (page.title != null) {
                        line.append(page.title);
                    }
                    if (line.length() > 0) {
                        mi.details.add(line.toString());
                    }
                    if (page.url != null) {
                        mi.details.add(page.url);
                    }
                }
                return mi;
            }
            if (m instanceof TLRPC.TL_messageMediaDice) {
                mi.kind = "dice";
                mi.details.add("🎲");
                return mi;
            }
            mi.kind = "unsupported";
            return mi;
        }

        /**
         * Makes sure the media file is on the device (downloads it when needed) and copies it into
         * the export folder. Sets mi.file on success or mi.status on failure.
         */
        private void obtain(TLRPC.Message msg, TLRPC.Document doc, TLRPC.Photo photo, TLRPC.PhotoSize size, long expectedSize, String fallbackName, MediaInfo mi) {
            try {
                FileLoader loader = FileLoader.getInstance(account);
                File source = loader.getPathToMessage(msg);
                if (!isReady(source)) {
                    if (doc != null) {
                        loader.loadFile(doc, msg, FileLoader.PRIORITY_NORMAL, 0);
                    } else {
                        loader.loadFile(ImageLocation.getForPhoto(size, photo), msg, null, FileLoader.PRIORITY_NORMAL, 1);
                    }
                    long timeout = 45000L + Math.min(900000L, expectedSize / 100);
                    long start = System.currentTimeMillis();
                    while (!isReady(source)) {
                        if (cancelled.get()) {
                            return;
                        }
                        if (System.currentTimeMillis() - start > timeout) {
                            if (doc != null) {
                                loader.cancelLoadFile(doc);
                            } else {
                                loader.cancelLoadFile(size);
                            }
                            mi.status = "download_failed";
                            return;
                        }
                        progress("Media yuklanmoqda (" + done + " / " + Math.max(total, done) + ")", total > 0 ? Math.min(1f, done / (float) Math.max(total, done)) : -1);
                        Thread.sleep(400);
                    }
                }
                String name = fallbackName != null && !fallbackName.isEmpty() ? fallbackName : source.getName();
                String target = msg.id + "_" + safeName(name);
                File destination = new File(mediaDir, target);
                copyFile(source, destination);
                mi.file = "media/" + target;
                if (mi.fileName == null) {
                    mi.fileName = name;
                }
            } catch (InterruptedException e) {
                cancelled.set(true);
            } catch (Throwable e) {
                mi.status = "copy_failed";
            }
        }

        private static boolean isReady(File file) {
            return file != null && file.exists() && file.length() > 0;
        }

        // -------------------------------------------------------------- JSON

        private String buildJson(TLRPC.Message msg, boolean service, String from, String fromRef, String text, MediaInfo media,
                                     String fwdName, int fwdDate, int replyId, ArrayList<String[]> reactions) {
            JSONObject o = new JSONObject();
            put(o, "id", msg.id);
            put(o, "type", service ? "service" : "message");
            put(o, "date", msg.date);
            put(o, "date_iso", isoFormat.format(new Date(msg.date * 1000L)));
            put(o, "from", from);
            put(o, "from_id", fromRef);
            put(o, "text", text);
            if (msg.entities != null && !msg.entities.isEmpty() && !service) {
                JSONArray entities = new JSONArray();
                for (TLRPC.MessageEntity e : msg.entities) {
                    if (e == null) {
                        continue;
                    }
                    JSONObject eo = new JSONObject();
                    put(eo, "type", entityType(e));
                    put(eo, "offset", e.offset);
                    put(eo, "length", e.length);
                    if (e.url != null && !e.url.isEmpty()) {
                        put(eo, "url", e.url);
                    }
                    if (e.language != null && !e.language.isEmpty()) {
                        put(eo, "language", e.language);
                    }
                    entities.put(eo);
                }
                put(o, "entities", entities);
            }
            if (replyId != 0) {
                put(o, "reply_to_id", replyId);
            }
            if (fwdName != null) {
                put(o, "forwarded_from", fwdName);
                if (fwdDate != 0) {
                    put(o, "forwarded_date", fwdDate);
                }
            }
            if (msg.edit_date != 0) {
                put(o, "edited", msg.edit_date);
            }
            if (msg.views != 0) {
                put(o, "views", msg.views);
            }
            if (msg.post_author != null && !msg.post_author.isEmpty()) {
                put(o, "post_author", msg.post_author);
            }
            if (msg.grouped_id != 0) {
                put(o, "grouped_id", String.valueOf(msg.grouped_id));
            }
            if (!reactions.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String[] r : reactions) {
                    JSONObject ro = new JSONObject();
                    put(ro, "emoji", r[0]);
                    put(ro, "count", Integer.parseInt(r[1]));
                    arr.put(ro);
                }
                put(o, "reactions", arr);
            }
            if (media != null) {
                JSONObject mo = new JSONObject();
                put(mo, "kind", media.kind);
                if (media.file != null) {
                    put(mo, "file", media.file);
                }
                if (media.hasFile && media.file == null && media.status != null) {
                    put(mo, "not_saved_reason", media.status);
                }
                if (media.fileName != null) {
                    put(mo, "file_name", media.fileName);
                }
                if (media.mime != null) {
                    put(mo, "mime_type", media.mime);
                }
                if (media.size > 0) {
                    put(mo, "size", media.size);
                }
                if (media.width > 0) {
                    put(mo, "width", media.width);
                }
                if (media.height > 0) {
                    put(mo, "height", media.height);
                }
                if (media.duration > 0) {
                    put(mo, "duration", media.duration);
                }
                if (media.title != null) {
                    put(mo, "title", media.title);
                }
                if (media.performer != null) {
                    put(mo, "performer", media.performer);
                }
                java.util.Iterator<String> keys = media.extra.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    put(mo, key, media.extra.opt(key));
                }
                put(o, "media", mo);
            }
            return o.toString();
        }

        // -------------------------------------------------------------- HTML

        private String buildHtml(TLRPC.Message msg, boolean service, String from, String text, MediaInfo media,
                                 String fwdName, int replyId, ArrayList<String[]> reactions) {
            StringBuilder sb = new StringBuilder();
            String time = shortFormat.format(new Date(msg.date * 1000L));
            if (service) {
                sb.append("<div class=\"msg service\" id=\"m").append(msg.id).append("\">")
                        .append(escape(text)).append(" <span class=\"date\">").append(time).append("</span></div>");
                return sb.toString();
            }
            sb.append("<div class=\"msg\" id=\"m").append(msg.id).append("\">");
            sb.append("<div class=\"meta\"><span class=\"from\">").append(escape(from)).append("</span> <span class=\"date\">")
                    .append(time);
            if (msg.edit_date != 0) {
                sb.append(" (tahrirlangan)");
            }
            sb.append("</span></div>");
            if (fwdName != null) {
                sb.append("<div class=\"fwd\">Yo'naltirilgan: ").append(escape(fwdName)).append("</div>");
            }
            if (replyId != 0) {
                sb.append("<div class=\"reply\"><a href=\"#m").append(replyId).append("\">↩ #").append(replyId).append("</a></div>");
            }
            if (media != null) {
                sb.append(mediaHtml(media));
            }
            if (text != null && !text.isEmpty()) {
                sb.append("<div class=\"text\">").append(formatHtml(text, msg.entities)).append("</div>");
            }
            if (!reactions.isEmpty()) {
                sb.append("<div class=\"reacts\">");
                for (String[] r : reactions) {
                    sb.append("<span>").append(escape(r[0])).append(' ').append(escape(r[1])).append("</span>");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
            return sb.toString();
        }

        private String mediaHtml(MediaInfo mi) {
            StringBuilder sb = new StringBuilder();
            String f = mi.file != null ? escape(mi.file) : null;
            String label = mi.fileName != null ? mi.fileName : mi.kind;
            if (f != null) {
                switch (mi.kind) {
                    case "photo":
                        sb.append("<a href=\"").append(f).append("\"><img class=\"photo\" loading=\"lazy\" src=\"").append(f).append("\"></a>");
                        break;
                    case "sticker":
                        if ("image/webp".equals(mi.mime)) {
                            sb.append("<img class=\"sticker\" loading=\"lazy\" src=\"").append(f).append("\">");
                        } else {
                            sb.append("<a class=\"file\" href=\"").append(f).append("\">Stiker: ").append(escape(label)).append("</a>");
                        }
                        break;
                    case "animation":
                        sb.append("<video class=\"video\" src=\"").append(f).append("\" autoplay loop muted playsinline></video>");
                        break;
                    case "video":
                    case "video_message":
                        sb.append("<video class=\"video\" controls preload=\"none\" src=\"").append(f).append("\"></video>");
                        break;
                    case "voice":
                    case "audio":
                        if (mi.title != null || mi.performer != null) {
                            sb.append("<div class=\"note\">").append(escape((mi.performer != null ? mi.performer + " - " : "") + (mi.title != null ? mi.title : ""))).append("</div>");
                        }
                        sb.append("<audio controls preload=\"none\" src=\"").append(f).append("\"></audio>");
                        break;
                    default:
                        sb.append("<a class=\"file\" href=\"").append(f).append("\">").append(escape(label)).append("</a>");
                        if (mi.size > 0) {
                            sb.append(" <span class=\"date\">").append(formatSize(mi.size)).append("</span>");
                        }
                        break;
                }
            } else if (mi.hasFile) {
                sb.append("<div class=\"note\">[").append(escape(mi.kind)).append(" saqlanmadi: ").append(escape(reasonText(mi.status))).append("]</div>");
            } else {
                for (String line : mi.details) {
                    if (line.startsWith("http://") || line.startsWith("https://")) {
                        sb.append("<div class=\"note\"><a href=\"").append(escape(line)).append("\">").append(escape(line)).append("</a></div>");
                    } else {
                        sb.append("<div class=\"note\">").append(escape(line)).append("</div>");
                    }
                }
            }
            return sb.toString();
        }

        private static String reasonText(String status) {
            if (status == null) {
                return "noma'lum";
            }
            switch (status) {
                case "too_large":
                    return "fayl juda katta";
                case "protected":
                    return "himoyalangan xabar";
                case "self_destructing":
                    return "o'zini o'chiruvchi media";
                case "download_failed":
                    return "yuklab bo'lmadi";
                case "unavailable":
                    return "mavjud emas";
                default:
                    return status;
            }
        }

        // -------------------------------------------------------------- assembling the final files

        private void assemble(int chunks) throws IOException {
            if (wantJson) {
                File file = new File(outDir, "messages.json");
                try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
                    JSONObject chat = new JSONObject();
                    put(chat, "id", String.valueOf(dialogId));
                    put(chat, "name", chatTitle);
                    put(chat, "type", chatType());
                    put(chat, "exported_at", isoFormat.format(new Date()));
                    put(chat, "message_count", done);
                    w.write("{\"chat\":");
                    w.write(chat.toString());
                    w.write(",\"messages\":[\n");
                    boolean first = true;
                    for (int i = chunks - 1; i >= 0; i--) {
                        File part = new File(partsDir, "json_" + i + ".part");
                        if (!part.exists()) {
                            continue;
                        }
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(part), "UTF-8"))) {
                            String line;
                            while ((line = r.readLine()) != null) {
                                if (line.isEmpty()) {
                                    continue;
                                }
                                if (!first) {
                                    w.write(",\n");
                                }
                                w.write(line);
                                first = false;
                            }
                        }
                    }
                    w.write("\n]}\n");
                }
            }
            if (wantHtml) {
                int pages = Math.max(1, (done + HTML_PAGE_SIZE - 1) / HTML_PAGE_SIZE);
                int page = 1;
                int inPage = 0;
                Writer w = openHtmlPage(page, pages);
                try {
                    for (int i = chunks - 1; i >= 0; i--) {
                        File part = new File(partsDir, "html_" + i + ".part");
                        if (!part.exists()) {
                            continue;
                        }
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(part), "UTF-8"))) {
                            String line;
                            while ((line = r.readLine()) != null) {
                                if (line.isEmpty()) {
                                    continue;
                                }
                                if (inPage == HTML_PAGE_SIZE) {
                                    closeHtmlPage(w);
                                    page++;
                                    w = openHtmlPage(page, pages);
                                    inPage = 0;
                                }
                                w.write(line);
                                w.write("\n");
                                inPage++;
                            }
                        }
                    }
                } finally {
                    closeHtmlPage(w);
                }
            }
        }

        private static String pageName(int page) {
            return page <= 1 ? "messages.html" : "messages" + page + ".html";
        }

        private Writer openHtmlPage(int page, int pages) throws IOException {
            Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(outDir, pageName(page))), "UTF-8"));
            w.write("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
            w.write("<title>" + escape(chatTitle) + "</title>\n<style>\n");
            w.write(":root{color-scheme:light dark;--bg:#f2f4f7;--card:#fff;--fg:#1c1e21;--muted:#7a828c;--accent:#2481cc}\n");
            w.write("@media(prefers-color-scheme:dark){:root{--bg:#10151b;--card:#1b232c;--fg:#e8edf2;--muted:#8a96a3;--accent:#5eb1f0}}\n");
            w.write("body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 -apple-system,Roboto,Segoe UI,sans-serif}\n");
            w.write(".wrap{max-width:720px;margin:0 auto;padding:12px}\n");
            w.write("h1{font-size:20px;margin:12px 0}.nav{margin:8px 0;color:var(--muted)}a{color:var(--accent);text-decoration:none}\n");
            w.write(".msg{background:var(--card);border-radius:12px;padding:8px 12px;margin:8px 0;word-wrap:break-word}\n");
            w.write(".msg.service{background:none;text-align:center;color:var(--muted);font-size:13px}\n");
            w.write(".from{font-weight:600;color:var(--accent)}.date{color:var(--muted);font-size:12px}\n");
            w.write(".fwd,.reply,.note{color:var(--muted);font-size:13px;margin:2px 0}\n");
            w.write(".photo,.video{max-width:100%;max-height:420px;border-radius:8px;display:block;margin:4px 0}\n");
            w.write(".sticker{max-width:180px;display:block}audio{width:100%;margin:4px 0}\n");
            w.write("pre,code{background:rgba(127,127,127,.15);border-radius:4px;padding:1px 4px;white-space:pre-wrap}\n");
            w.write("blockquote{margin:4px 0;padding-left:8px;border-left:3px solid var(--accent)}\n");
            w.write(".spoiler{background:var(--muted);color:transparent;border-radius:3px}.spoiler:hover{color:inherit;background:none}\n");
            w.write(".reacts span{display:inline-block;background:rgba(127,127,127,.15);border-radius:10px;padding:0 8px;margin:4px 4px 0 0;font-size:13px}\n");
            w.write("</style></head><body><div class=\"wrap\">\n");
            w.write("<h1>" + escape(chatTitle) + "</h1>\n<div class=\"nav\">");
            if (page > 1) {
                w.write("<a href=\"" + pageName(page - 1) + "\">← Oldingi</a> · ");
            }
            w.write("Sahifa " + page + " / " + pages);
            if (page < pages) {
                w.write(" · <a href=\"" + pageName(page + 1) + "\">Keyingi →</a>");
            }
            w.write("</div>\n");
            if (done == 0) {
                w.write("<div class=\"note\">Xabarlar yo'q</div>\n");
            }
            return w;
        }

        private void closeHtmlPage(Writer w) throws IOException {
            w.write("</div></body></html>\n");
            w.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Throwable ignore) {
        }
    }

    private static String entityType(TLRPC.MessageEntity e) {
        if (e instanceof TLRPC.TL_messageEntityBold) {
            return "bold";
        } else if (e instanceof TLRPC.TL_messageEntityItalic) {
            return "italic";
        } else if (e instanceof TLRPC.TL_messageEntityUnderline) {
            return "underline";
        } else if (e instanceof TLRPC.TL_messageEntityStrike) {
            return "strike";
        } else if (e instanceof TLRPC.TL_messageEntityCode) {
            return "code";
        } else if (e instanceof TLRPC.TL_messageEntityPre) {
            return "pre";
        } else if (e instanceof TLRPC.TL_messageEntityTextUrl) {
            return "text_url";
        } else if (e instanceof TLRPC.TL_messageEntityUrl) {
            return "url";
        } else if (e instanceof TLRPC.TL_messageEntityMentionName) {
            return "mention_name";
        } else if (e instanceof TLRPC.TL_messageEntityMention) {
            return "mention";
        } else if (e instanceof TLRPC.TL_messageEntitySpoiler) {
            return "spoiler";
        } else if (e instanceof TLRPC.TL_messageEntityBlockquote) {
            return "blockquote";
        }
        return "other";
    }

    private static String safeUrl(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.trim().toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("tg://") || lower.startsWith("mailto:")) {
            return url.trim();
        }
        return null;
    }

    /** HTML tag for an entity. For links the same decision is made when opening and closing. */
    private static String tag(TLRPC.MessageEntity e, String text, boolean open) {
        switch (entityType(e)) {
            case "bold":
                return open ? "<b>" : "</b>";
            case "italic":
                return open ? "<i>" : "</i>";
            case "underline":
                return open ? "<u>" : "</u>";
            case "strike":
                return open ? "<s>" : "</s>";
            case "code":
                return open ? "<code>" : "</code>";
            case "pre":
                return open ? "<pre>" : "</pre>";
            case "blockquote":
                return open ? "<blockquote>" : "</blockquote>";
            case "spoiler":
                return open ? "<span class=\"spoiler\">" : "</span>";
            case "mention":
            case "mention_name":
                return open ? "<span class=\"mention\">" : "</span>";
            case "text_url":
            case "url": {
                String raw = e.url;
                if (entityType(e).equals("url")) {
                    raw = text.substring(e.offset, e.offset + e.length);
                    if (!raw.contains("://")) {
                        raw = "http://" + raw;
                    }
                }
                String safe = safeUrl(raw);
                if (safe == null) {
                    return open ? "<span>" : "</span>";
                }
                return open ? "<a href=\"" + escape(safe) + "\">" : "</a>";
            }
            default:
                return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static String formatHtml(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int n = text.length();
        ArrayList<TLRPC.MessageEntity> valid = new ArrayList<>();
        if (entities != null) {
            for (TLRPC.MessageEntity e : entities) {
                if (e != null && e.offset >= 0 && e.length > 0 && e.offset + e.length <= n) {
                    valid.add(e);
                }
            }
        }
        if (valid.isEmpty()) {
            return escape(text);
        }
        Collections.sort(valid, (a, b) -> a.offset != b.offset ? a.offset - b.offset : b.length - a.length);
        ArrayList<TLRPC.MessageEntity>[] closes = new ArrayList[n + 1];
        StringBuilder sb = new StringBuilder();
        int next = 0;
        for (int i = 0; i <= n; i++) {
            if (closes[i] != null) {
                Collections.sort(closes[i], (a, b) -> b.offset - a.offset);
                for (TLRPC.MessageEntity e : closes[i]) {
                    sb.append(tag(e, text, false));
                }
            }
            while (next < valid.size() && valid.get(next).offset == i) {
                TLRPC.MessageEntity e = valid.get(next++);
                sb.append(tag(e, text, true));
                int end = e.offset + e.length;
                if (closes[end] == null) {
                    closes[end] = new ArrayList<>();
                }
                closes[end].add(e);
            }
            if (i < n) {
                appendEscaped(sb, text.charAt(i));
            }
        }
        return sb.toString();
    }

    private static void appendEscaped(StringBuilder sb, char c) {
        switch (c) {
            case '&':
                sb.append("&amp;");
                break;
            case '<':
                sb.append("&lt;");
                break;
            case '>':
                sb.append("&gt;");
                break;
            case '"':
                sb.append("&quot;");
                break;
            case '\n':
                sb.append("<br>");
                break;
            case '\r':
                break;
            default:
                if (c >= 0x20 || c == '\t') {
                    sb.append(c);
                }
                break;
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            appendEscaped(sb, s.charAt(i));
        }
        return sb.toString();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String safeName(String name) {
        if (name == null || name.isEmpty()) {
            return "file";
        }
        String cleaned = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (cleaned.length() > 80) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 && cleaned.length() - dot <= 8 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, 72) + ext;
        }
        return cleaned.isEmpty() ? "file" : cleaned;
    }

    private static void writeFile(File file, String content) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
            w.write(content);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        if (target.exists() && target.length() == source.length()) {
            return;
        }
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
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

    private ParadoksExport() {
    }
}
