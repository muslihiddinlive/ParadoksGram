package uz.paradoks.gram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/** Local notification "X deleted a message" (off by default, muted chats are skipped). */
final class ParadoksNotify {

    private static final String CHANNEL = "paradoks_deleted";
    private static final int MAX_PER_BATCH = 5;

    private ParadoksNotify() {
    }

    static void deleted(int account, long dialogKey, ArrayList<Integer> ids) {
        if (!ParadoksConfig.notifyDeleted() || ids == null || ids.isEmpty()) {
            return;
        }
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            int shown = 0;
            for (int mid : copy) {
                if (shown >= MAX_PER_BATCH) {
                    break;
                }
                try {
                    ParadoksDeletedActivity.Item item = ParadoksDeletedActivity.lookup(account, dialogKey, mid, 0);
                    if (item == null || MessagesController.getInstance(account).isDialogMuted(item.dialogId, 0)) {
                        continue;
                    }
                    post(account, item);
                    shown++;
                } catch (Throwable ignore) {
                }
            }
        });
    }

    private static void post(int account, ParadoksDeletedActivity.Item item) {
        Context context = ApplicationLoader.applicationContext;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "O'chirilgan xabarlar", NotificationManager.IMPORTANCE_DEFAULT));
        }
        int requestCode = (int) (System.nanoTime() & 0x7fffffff);
        Intent intent = new Intent(context, LaunchActivity.class);
        intent.setAction("com.tmessages.openchat" + requestCode);
        intent.putExtra("currentAccount", account);
        if (item.dialogId > 0) {
            intent.putExtra("userId", item.dialogId);
        } else {
            intent.putExtra("chatId", -item.dialogId);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = item.sender.equals(item.chatName) ? item.sender : (item.sender + " (" + item.chatName + ")");
        String body = item.text.length() > 120 ? item.text.substring(0, 120) + "…" : item.text;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.notification)
                .setContentTitle(title + " xabarni o'chirdi")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        manager.notify(requestCode, builder.build());
    }
}
