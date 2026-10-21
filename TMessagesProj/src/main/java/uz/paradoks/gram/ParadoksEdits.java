package uz.paradoks.gram;

import android.database.Cursor;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import java.io.File;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Remembers previous versions of edited messages and shows them on request. */
public final class ParadoksEdits {

    /** Called when Telegram receives an edit; {@code old} is the stored pre-edit message. */
    public static void onEdited(int account, TLRPC.Message updated, TLRPC.Message old) {
        if (!ParadoksConfig.trackEdits() || updated == null || old == null) {
            return;
        }
        final String before = old.message == null ? "" : old.message;
        final String after = updated.message == null ? "" : updated.message;
        final boolean mediaChanged = !mediaKey(old).equals(mediaKey(updated));
        if (before.equals(after) && !mediaChanged && entitiesKey(old).equals(entitiesKey(updated))) {
            return; // nothing visible changed
        }
        final long ts = old.edit_date != 0 ? old.edit_date : old.date;
        final long dialogId = updated.dialog_id;
        final int messageId = updated.id;
        final byte[] blob = serialize(old);
        ParadoksDb.async(() -> {
            // avoid duplicates if the same update is delivered twice
            Cursor cursor = ParadoksDb.get().getReadableDatabase().rawQuery(
                    "SELECT ts, txt FROM edits WHERE acc = ? AND dlg = ? AND mid = ? ORDER BY id DESC LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)});
            try {
                if (cursor.moveToFirst() && cursor.getLong(0) == ts && before.equals(cursor.getString(1))) {
                    return;
                }
            } finally {
                cursor.close();
            }
            // the old photo/file is about to be replaced: keep a private copy of it
            String mediaPath = mediaChanged ? copyOldMedia(account, old, dialogId, messageId, ts) : null;
            ParadoksDb.get().insertEdit(account, dialogId, messageId, ts, before, blob, mediaPath);
        });
    }

    /** Identifies the attached photo / file so a replaced media can be noticed. */
    private static String mediaKey(TLRPC.Message message) {
        if (message == null || message.media == null) {
            return "";
        }
        if (message.media.photo != null) {
            return "p" + message.media.photo.id;
        }
        if (message.media.document != null) {
            return "d" + message.media.document.id;
        }
        return message.media.getClass().getSimpleName();
    }

    /** Identifies the formatting (bold, links, ...) of a message text. */
    private static String entitiesKey(TLRPC.Message message) {
        StringBuilder sb = new StringBuilder();
        if (message != null && message.entities != null) {
            for (TLRPC.MessageEntity entity : message.entities) {
                sb.append(entity.getClass().getSimpleName()).append(':').append(entity.offset).append(':').append(entity.length);
                if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                    sb.append(':').append(((TLRPC.TL_messageEntityTextUrl) entity).url);
                }
                sb.append(';');
            }
        }
        return sb.toString();
    }

    private static String copyOldMedia(int account, TLRPC.Message old, long dialogId, int messageId, long ts) {
        try {
            File source = FileLoader.getInstance(account).getPathToMessage(old);
            if (source == null || !source.exists() || source.length() > 100L * 1024 * 1024) {
                return null;
            }
            File dir = new File(ApplicationLoader.applicationContext.getExternalFilesDir(null), "paradoks_edits");
            dir.mkdirs();
            File target = new File(dir, account + "_" + (dialogId < 0 ? "c" + (-dialogId) : "p" + dialogId) + "_" + messageId + "_" + ts + "_" + source.getName().replaceAll("[^A-Za-z0-9._-]", "_"));
            if (!target.exists()) {
                java.io.FileInputStream in = new java.io.FileInputStream(source);
                java.io.FileOutputStream out = new java.io.FileOutputStream(target);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                in.close();
                out.close();
            }
            return target.getAbsolutePath();
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** Whole message to bytes (null on failure). */
    static byte[] serialize(TLRPC.Message message) {
        try {
            SerializedData data = new SerializedData();
            message.serializeToStream(data);
            return data.toByteArray();
        } catch (Throwable ignore) {
            return null;
        }
    }

    static TLRPC.Message deserialize(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(bytes);
            return TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** An independent copy that can be changed freely. */
    static TLRPC.Message copyOf(TLRPC.Message message) {
        return message == null ? null : deserialize(serialize(message));
    }

    public static boolean hasHistory(int account, TLRPC.Message message) {
        if (message == null || message.id <= 0) {
            return false;
        }
        long dialogId = MessageObject.getDialogId(message);
        Cursor cursor = null;
        try {
            cursor = ParadoksDb.get().getReadableDatabase().rawQuery(
                    "SELECT 1 FROM edits WHERE acc = ? AND dlg = ? AND mid = ? LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(message.id)});
            return cursor.moveToFirst();
        } catch (Throwable t) {
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public static void showHistory(BaseFragment fragment, int account, TLRPC.Message message) {
        if (message == null) {
            return;
        }
        if (fragment != null) {
            fragment.presentFragment(new ParadoksEditsActivity(account, MessageObject.getDialogId(message), message.id, message));
        }
    }

    /** Same list, opened from the radar (the current text is not at hand there). */
    public static void showHistoryById(BaseFragment fragment, int account, long dialogId, int messageId) {
        if (fragment != null) {
            fragment.presentFragment(new ParadoksEditsActivity(account, dialogId, messageId, null));
        }
    }

    private static void showHistoryFor(BaseFragment fragment, int account, long dialogId, int messageId, String currentText) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        Cursor cursor = null;
        int count = 0;
        try {
            cursor = ParadoksDb.get().getReadableDatabase().rawQuery(
                    "SELECT ts, txt FROM edits WHERE acc = ? AND dlg = ? AND mid = ? ORDER BY id ASC",
                    new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)});
            while (cursor.moveToNext()) {
                count++;
                String text = cursor.getString(1);
                sb.append(count).append(") ").append(format.format(new Date(cursor.getLong(0) * 1000))).append('\n');
                sb.append(text == null || text.isEmpty() ? "(matnsiz)" : text).append("\n\n");
            }
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (currentText != null) {
            sb.append("Hozirgi variant:\n").append(currentText);
        }

        if (count == 0) {
            int total = 0;
            Cursor totalCursor = null;
            try {
                totalCursor = ParadoksDb.get().getReadableDatabase().rawQuery("SELECT COUNT(*) FROM edits WHERE acc = ?", new String[]{String.valueOf(account)});
                if (totalCursor.moveToFirst()) {
                    total = totalCursor.getInt(0);
                }
            } catch (Throwable ignore) {
            } finally {
                if (totalCursor != null) {
                    totalCursor.close();
                }
            }
            sb.setLength(0);
            sb.append("Bu xabarning eski variantlari saqlanmagan.\n\n")
                    .append("Odatda sababi: xabar bu ilova o'rnatilishidan oldin yoki \"Tahrirlar tarixini saqlash\" yoqilishidan oldin tahrirlangan. ")
                    .append("Endi yangi tahrir qilinsa, eski matn saqlanadi.\n\n")
                    .append("Tahrirlarni saqlash: ").append(ParadoksConfig.trackEdits() ? "yoniq" : "O'CHIQ (sozlamalarda yoqing)")
                    .append("\nShu akkauntda jami yozilgan tahrirlar: ").append(total);
            if (currentText != null) {
                sb.append("\n\nHozirgi variant:\n").append(currentText);
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle("Tahrirlar tarixi (" + count + ")");
        builder.setMessage(sb.toString());
        builder.setPositiveButton("OK", null);
        fragment.showDialog(builder.create());
    }

    private ParadoksEdits() {
    }
}
