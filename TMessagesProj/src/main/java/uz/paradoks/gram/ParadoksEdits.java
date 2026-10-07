package uz.paradoks.gram;

import android.database.Cursor;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
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
        if (before.equals(after)) {
            return;
        }
        final long ts = old.edit_date != 0 ? old.edit_date : old.date;
        final long dialogId = updated.dialog_id;
        final int messageId = updated.id;
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
            ParadoksDb.get().insertEdit(account, dialogId, messageId, ts, before);
        });
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
        if (fragment == null || fragment.getParentActivity() == null || message == null) {
            return;
        }
        long dialogId = MessageObject.getDialogId(message);
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        Cursor cursor = null;
        int count = 0;
        try {
            cursor = ParadoksDb.get().getReadableDatabase().rawQuery(
                    "SELECT ts, txt FROM edits WHERE acc = ? AND dlg = ? AND mid = ? ORDER BY id ASC",
                    new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(message.id)});
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
        String current = message.message == null || message.message.isEmpty() ? "(matnsiz)" : message.message;
        sb.append("Hozirgi variant:\n").append(current);

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle("Tahrirlar tarixi (" + count + ")");
        builder.setMessage(sb.toString());
        builder.setPositiveButton("OK", null);
        fragment.showDialog(builder.create());
    }

    private ParadoksEdits() {
    }
}
