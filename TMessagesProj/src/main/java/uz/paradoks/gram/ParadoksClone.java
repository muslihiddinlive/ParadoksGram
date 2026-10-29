package uz.paradoks.gram;

import android.content.Context;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chat clone: creates a new private supergroup named "&lt;original&gt;_clon" on the user's own account and
 * forwards every message (media and files included) of the source chat into it, oldest first.
 *
 * Allowed sources: public groups, public channels and personal (one-to-one) chats.
 * Not allowed: private groups/channels, secret chats and chats with restricted saving/forwarding
 * (Telegram refuses those forwards, and the content protection is respected).
 *
 * Messages are forwarded with their original author shown ("Forwarded from"), up to 100 per request,
 * with a pause between requests and automatic waiting when Telegram asks for it (FLOOD_WAIT).
 */
public final class ParadoksClone {

    private static final int PAGE_LIMIT = 100;
    private static final int FORWARD_CHUNK = 100;
    private static final long CHUNK_PAUSE_MS = 1500;

    private static volatile boolean running;

    // ------------------------------------------------------------------ eligibility

    /** Used by the chat menu: true only for public groups/channels and personal chats. */
    public static boolean canClone(TLRPC.Chat chat, TLRPC.User user, TLRPC.EncryptedChat encrypted) {
        if (encrypted != null) {
            return false;
        }
        if (chat != null) {
            return ChatObject.isPublic(chat) && !chat.noforwards && !ChatObject.isMonoForum(chat);
        }
        return user != null;
    }

    public static void start(BaseFragment fragment, int account, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        if (running) {
            toast(context, "Klon qilish allaqachon davom etmoqda");
            return;
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            toast(context, "Maxfiy chatni klon qilib bo'lmaydi");
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        String title;
        if (dialogId < 0) {
            TLRPC.Chat chat = mc.getChat(-dialogId);
            if (chat == null) {
                toast(context, "Chat topilmadi");
                return;
            }
            if (!ChatObject.isPublic(chat)) {
                toast(context, "Yopiq guruh yoki kanalni klon qilib bo'lmaydi");
                return;
            }
            if (chat.noforwards) {
                toast(context, "Bu chatda saqlash cheklangan, klon qilib bo'lmaydi");
                return;
            }
            title = chat.title != null ? chat.title : "chat";
        } else {
            TLRPC.User user = mc.getUser(dialogId);
            if (user == null) {
                toast(context, "Chat topilmadi");
                return;
            }
            String name = UserObject.getUserName(user);
            title = name != null && !name.isEmpty() ? name : "chat";
        }
        final String name = title;
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Chatni klon qilish");
        builder.setMessage("\"" + name + "\" chatidagi barcha xabarlar (media va fayllar bilan) yangi maxfiy guruhga forward qilinadi. "
                + "Guruh nomi: " + cloneTitle(name) + ".\n\nKatta chatlar uchun uzoq vaqt ketishi mumkin, ilovani yopmang. Davom etamizmi?");
        builder.setPositiveButton("Boshlash", (d, w) -> begin(fragment, account, dialogId, name));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static String cloneTitle(String name) {
        String suffix = "_clon";
        if (name.length() + suffix.length() > 128) {
            name = name.substring(0, 128 - suffix.length());
        }
        return name + suffix;
    }

    // ------------------------------------------------------------------ UI

    private static void begin(BaseFragment fragment, int account, long dialogId, String title) {
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

        final Job job = new Job(account, dialogId, title);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Chat klonlanmoqda");
        builder.setView(layout);
        builder.setNegativeButton("To'xtatish", (d, w) -> job.cancelled.set(true));
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
        Thread thread = new Thread(job, "ParadoksClone");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    private static void showResult(Result result) {
        BaseFragment last = LaunchActivity.getLastFragment();
        Context context = last != null ? last.getParentActivity() : null;
        String summary;
        String dialogTitle;
        if (result.ok) {
            dialogTitle = "Klon tayyor";
            summary = result.forwarded + " ta xabar \"" + result.newTitle + "\" guruhiga forward qilindi"
                    + (result.failed > 0 ? " (" + result.failed + " tasi o'tmadi)" : "") + ".";
        } else if (result.cancelled) {
            dialogTitle = "To'xtatildi";
            summary = result.newChatId != 0
                    ? result.forwarded + " ta xabar \"" + result.newTitle + "\" guruhiga o'tgan edi."
                    : "Guruh yaratilmadi.";
        } else {
            dialogTitle = "Klon xatosi";
            summary = (result.error != null ? result.error : "Noma'lum xato")
                    + (result.newChatId != 0 ? "\n\n" + result.forwarded + " ta xabar \"" + result.newTitle + "\" guruhiga o'tgan." : "");
        }
        if (context == null) {
            toast(ApplicationLoader.applicationContext, summary);
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(dialogTitle);
        builder.setMessage(summary);
        if (result.newChatId != 0) {
            final long chatId = result.newChatId;
            builder.setPositiveButton("Ochish", (d, w) -> {
                Bundle args = new Bundle();
                args.putLong("chat_id", chatId);
                last.presentFragment(new ChatActivity(args));
            });
        }
        builder.setNegativeButton("Yopish", null);
        last.showDialog(builder.create());
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
        long newChatId;
        String newTitle;
        int forwarded;
        int failed;
    }

    /** A non-retryable error answered by Telegram. */
    private static final class ApiException extends IOException {
        final String text;

        ApiException(String text) {
            super(text);
            this.text = text != null ? text : "";
        }
    }

    private static final class Job implements Runnable {
        final int account;
        final long dialogId;
        final String sourceTitle;
        final String newTitle;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final SecureRandom random = new SecureRandom();
        ProgressListener listener;
        FinishListener finish;

        long newChatId;
        int forwarded;
        int failed;

        Job(int account, long dialogId, String sourceTitle) {
            this.account = account;
            this.dialogId = dialogId;
            this.sourceTitle = sourceTitle;
            this.newTitle = cloneTitle(sourceTitle);
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
                execute();
                result.cancelled = cancelled.get();
                result.ok = !result.cancelled;
            } catch (Throwable e) {
                result.ok = false;
                result.error = describe(e);
            }
            result.newChatId = newChatId;
            result.newTitle = newTitle;
            result.forwarded = forwarded;
            result.failed = failed;
            if (finish != null) {
                finish.onFinish(result);
            }
        }

        private static String describe(Throwable e) {
            if (e instanceof ApiException) {
                String t = ((ApiException) e).text;
                if (t.contains("CHAT_FORWARDS_RESTRICTED")) {
                    return "Bu chatda forward qilish cheklangan.";
                }
                if (t.contains("CHANNELS_TOO_MUCH")) {
                    return "Guruhlar/kanallar chegarasiga yetgansiz, yangi guruh ochib bo'lmaydi.";
                }
                return "Telegram xatosi: " + t;
            }
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        private void execute() throws Exception {
            TLRPC.InputPeer source = MessagesController.getInstance(account).getInputPeer(dialogId);
            if (source == null || source instanceof TLRPC.TL_inputPeerEmpty) {
                throw new IOException("Chat topilmadi");
            }

            // 1. every forwardable message id of the source chat
            ArrayList<long[]> items = collect(source); // {id, grouped_id}
            if (cancelled.get()) {
                return;
            }
            if (items.isEmpty()) {
                throw new IOException("Forward qilinadigan xabar topilmadi");
            }

            // 2. the new private supergroup
            progress("Yangi guruh yaratilmoqda...", -1);
            TLRPC.TL_channels_createChannel create = new TLRPC.TL_channels_createChannel();
            create.megagroup = true;
            create.title = newTitle;
            String about = "Klon: " + sourceTitle;
            create.about = about.length() > 255 ? about.substring(0, 255) : about;
            TLObject created = call(create);
            if (created == null) {
                return;
            }
            if (!(created instanceof TLRPC.Updates)) {
                throw new IOException("Guruh yaratilmadi");
            }
            TLRPC.Updates updates = (TLRPC.Updates) created;
            TLRPC.Chat newChat = null;
            for (TLRPC.Chat c : updates.chats) {
                if (c != null && c.access_hash != 0) {
                    newChat = c;
                    break;
                }
            }
            if (newChat == null) {
                throw new IOException("Yangi guruh ma'lumoti olinmadi");
            }
            newChatId = newChat.id;
            MessagesController.getInstance(account).processUpdates(updates, false);
            TLRPC.TL_inputPeerChannel target = new TLRPC.TL_inputPeerChannel();
            target.channel_id = newChat.id;
            target.access_hash = newChat.access_hash;

            // 3. forward in chunks of up to 100, never splitting an album
            int total = items.size();
            int index = 0;
            while (index < total && !cancelled.get()) {
                int end = Math.min(index + FORWARD_CHUNK, total);
                while (end < total && end - 1 > index && items.get(end - 1)[1] != 0 && items.get(end - 1)[1] == items.get(end)[1]) {
                    end--;
                }
                TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
                req.from_peer = source;
                req.to_peer = target;
                for (int i = index; i < end; i++) {
                    req.id.add((int) items.get(i)[0]);
                    req.random_id.add(random.nextLong());
                }
                int count = end - index;
                try {
                    TLObject response = call(req);
                    if (response == null) {
                        return; // cancelled
                    }
                    forwarded += count;
                } catch (ApiException e) {
                    if (e.text.contains("MESSAGE_ID_INVALID") || e.text.contains("MESSAGE_IDS_EMPTY")) {
                        failed += count; // deleted in the meantime: skip this chunk
                    } else {
                        throw e;
                    }
                }
                index = end;
                progress("Forward qilinmoqda: " + (forwarded + failed) + " / " + total, (forwarded + failed) / (float) total);
                Thread.sleep(CHUNK_PAUSE_MS);
            }
        }

        /** Reads the whole history page by page and returns {id, grouped_id} of forwardable messages, oldest first. */
        private ArrayList<long[]> collect(TLRPC.InputPeer source) throws Exception {
            ArrayList<long[]> items = new ArrayList<>();
            int offsetId = 0;
            int seen = 0;
            while (!cancelled.get()) {
                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = source;
                req.offset_id = offsetId;
                req.limit = PAGE_LIMIT;
                TLObject response = call(req);
                if (response == null) {
                    break;
                }
                if (!(response instanceof TLRPC.messages_Messages)) {
                    throw new IOException("Xabarlarni o'qib bo'lmadi");
                }
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                if (res.messages.isEmpty()) {
                    break;
                }
                int minId = Integer.MAX_VALUE;
                for (TLRPC.Message m : res.messages) {
                    if (m == null) {
                        continue;
                    }
                    if (m.id < minId) {
                        minId = m.id;
                    }
                    if (m instanceof TLRPC.TL_messageEmpty || m instanceof TLRPC.TL_messageService || m.noforwards) {
                        continue;
                    }
                    items.add(new long[]{m.id, m.grouped_id});
                }
                seen += res.messages.size();
                if (minId == Integer.MAX_VALUE || (offsetId != 0 && minId >= offsetId)) {
                    break;
                }
                offsetId = minId;
                progress("Xabarlar o'qilmoqda: " + seen + (res.count > seen ? " / " + res.count : ""),
                        res.count > 0 ? Math.min(1f, seen / (float) res.count) : -1);
                Thread.sleep(120);
            }
            Collections.sort(items, (a, b) -> Long.compare(a[0], b[0]));
            return items;
        }

        /**
         * Sends a request and waits for the answer. Waits out FLOOD_WAIT, retries network problems,
         * throws ApiException for errors that retrying cannot fix. Returns null when cancelled.
         */
        private TLObject call(TLObject req) throws Exception {
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
                if (error == null && result[0] instanceof TLObject) {
                    return (TLObject) result[0];
                }
                int waitSeconds = 2;
                if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                    try {
                        waitSeconds = Integer.parseInt(error.text.substring("FLOOD_WAIT_".length())) + 1;
                    } catch (Throwable ignore) {
                        waitSeconds = 30;
                    }
                    waitSeconds = Math.min(waitSeconds, 600);
                } else if (error != null && (error.code == 400 || error.code == 403 || error.code == 406)) {
                    throw new ApiException(error.text);
                }
                for (int s = waitSeconds; s > 0 && !cancelled.get(); s--) {
                    progress("Telegram kutishni so'radi: " + s + " s", -1);
                    Thread.sleep(1000);
                }
            }
            if (cancelled.get()) {
                return null;
            }
            throw new IOException("Telegram bilan aloqa yo'q (internetni tekshiring)");
        }
    }

    private ParadoksClone() {
    }
}
