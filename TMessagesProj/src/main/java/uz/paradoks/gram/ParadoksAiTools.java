package uz.paradoks.gram;

import android.app.Activity;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Shortcuts that hand chat text to Paradoks AI: summarize a chat, translate a message, explain a message.
 *
 * Nothing is sent by itself. The user first sees what will go to the bot ("N ta xabar"), agrees, and the AI window opens
 * with the request already typed into the input field, so the text can still be edited before pressing send.
 * Only the visible text of ordinary messages is used (no media, no phone numbers of the chat members).
 */
public final class ParadoksAiTools {

    private static final int MAX_MESSAGES = 60;
    private static final int MAX_CHARS = 6000;

    /** Request waiting to be put into the input field of the AI window (consumed once). */
    private static String pendingPrompt;

    private ParadoksAiTools() {
    }

    /** Called by the AI window when it is created; returns the prepared request or null. */
    public static String takePendingPrompt() {
        String prompt = pendingPrompt;
        pendingPrompt = null;
        return prompt;
    }

    // ------------------------------------------------------------------ chat summary

    /** Menu item of a chat: summarize the latest messages loaded in the chat. */
    public static void summarizeChat(BaseFragment fragment, String chatTitle, ArrayList<MessageObject> loaded) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final StringBuilder text = new StringBuilder();
        final int count = collect(loaded, text);
        if (count == 0) {
            Toast.makeText(activity, "Qisqartirish uchun matnli xabar topilmadi", Toast.LENGTH_SHORT).show();
            return;
        }
        final String prompt = "Quyidagi \"" + chatTitle + "\" chatidagi yozishmani o'zbek tilida qisqacha xulosa qil: asosiy mavzular, "
                + "qabul qilingan qarorlar va kimdir bajarishi kerak bo'lgan ishlar.\n\n" + text;
        confirm(fragment, "Chatni AI bilan qisqartirish", count + " ta xabar matni Paradoks AI botiga yuboriladi. "
                + "Avval so'rov AI oynasida ko'rinadi, yuborishni o'zingiz bosasiz.", prompt);
    }

    // ------------------------------------------------------------------ one message

    public static void translateMessage(BaseFragment fragment, MessageObject message) {
        String body = textOf(message);
        if (body.isEmpty()) {
            Toast.makeText(fragment.getParentActivity(), "Bu xabarda matn yo'q", Toast.LENGTH_SHORT).show();
            return;
        }
        String prompt = "Quyidagi matnni o'zbek tiliga (lotin) tarjima qil. Agar matn o'zbekcha bo'lsa, ingliz tiliga tarjima qil. "
                + "Faqat tarjimani yoz.\n\n" + clip(body);
        confirm(fragment, "AI bilan tarjima", "Shu xabarning matni Paradoks AI botiga yuboriladi. Avval so'rov AI oynasida ko'rinadi.", prompt);
    }

    public static void explainMessage(BaseFragment fragment, MessageObject message) {
        String body = textOf(message);
        if (body.isEmpty()) {
            Toast.makeText(fragment.getParentActivity(), "Bu xabarda matn yo'q", Toast.LENGTH_SHORT).show();
            return;
        }
        String prompt = "Quyidagi xabarni sodda o'zbek tilida tushuntirib ber (mazmuni, kerak bo'lsa atamalar izohi).\n\n" + clip(body);
        confirm(fragment, "AI bilan tushuntirish", "Shu xabarning matni Paradoks AI botiga yuboriladi. Avval so'rov AI oynasida ko'rinadi.", prompt);
    }

    // ------------------------------------------------------------------ helpers

    private static void confirm(BaseFragment fragment, String title, String message, String prompt) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton("AI oynasini ochish", (d, w) -> {
            pendingPrompt = prompt;
            fragment.presentFragment(new ParadoksAiActivity());
        });
        builder.setNegativeButton("Bekor qilish", null);
        fragment.showDialog(builder.create());
    }

    private static String textOf(MessageObject message) {
        if (message == null || message.messageOwner == null || message.messageOwner.message == null) {
            return "";
        }
        return message.messageOwner.message.trim();
    }

    private static String clip(String text) {
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "…";
    }

    /** Builds "[HH:mm] name: text" lines, oldest first, from the newest {@link #MAX_MESSAGES} text messages. Returns how many were used. */
    private static int collect(ArrayList<MessageObject> loaded, StringBuilder out) {
        if (loaded == null || loaded.isEmpty()) {
            return 0;
        }
        // ChatActivity keeps the newest message first
        ArrayList<String> lines = new ArrayList<>();
        SimpleDateFormat time = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        int total = 0;
        for (int i = 0; i < loaded.size() && lines.size() < MAX_MESSAGES; i++) {
            MessageObject message = loaded.get(i);
            String body = textOf(message);
            if (body.isEmpty() || message.messageOwner.action != null) {
                continue;
            }
            String who = message.isOut() ? "Men" : senderName(message);
            String line = "[" + time.format(new Date(message.messageOwner.date * 1000L)) + "] " + who + ": " + body.replace('\n', ' ');
            if (total + line.length() > MAX_CHARS) {
                break;
            }
            total += line.length();
            lines.add(line);
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            out.append(lines.get(i)).append('\n');
        }
        return lines.size();
    }

    private static String senderName(MessageObject message) {
        try {
            if (message.messageOwner.from_id != null && message.messageOwner.from_id.user_id != 0) {
                org.telegram.tgnet.TLRPC.User user = org.telegram.messenger.MessagesController.getInstance(message.currentAccount)
                        .getUser(message.messageOwner.from_id.user_id);
                if (user != null) {
                    String name = UserObject.getFirstName(user);
                    if (!TextUtils.isEmpty(name)) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return "Boshqa";
    }
}
