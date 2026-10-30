package uz.paradoks.gram;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationsController;
import org.telegram.ui.LaunchActivity;

/**
 * Keeps a chat clone running when the app goes to the background: a foreground service stops Android from
 * freezing the process, the wake/Wi-Fi locks keep the CPU and network awake. It shows the progress in a
 * notification and stops itself when the clone ends (a final notification tells how it went).
 */
public class ParadoksCloneService extends Service {

    private static final int NOTIFICATION_ID = 7431;
    private static final int RESULT_NOTIFICATION_ID = 7432;
    private static final long TICK_MS = 1000;
    private static final long WAKE_LOCK_MS = 10 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationCompat.Builder builder;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context context) {
        try {
            context.getApplicationContext().startForegroundService(new Intent(context.getApplicationContext(), ParadoksCloneService.class));
        } catch (Throwable e) {
            // Android may refuse to start a foreground service from the background; the clone still runs in the app.
            FileLog.e(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (builder == null) {
            NotificationsController.checkOtherNotificationsChannel();
            builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
            builder.setSmallIcon(android.R.drawable.stat_sys_upload);
            builder.setContentTitle("Chat klonlanmoqda");
            builder.setOngoing(true);
            builder.setOnlyAlertOnce(true);
            builder.setPriority(NotificationCompat.PRIORITY_LOW);
            builder.setContentText("Boshlanmoqda...");
            builder.setProgress(100, 0, true);
            builder.setContentIntent(openAppIntent());
        }
        try {
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Throwable e) {
            FileLog.e(e);
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireLocks();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ApplicationLoader.applicationContext, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ParadoksClone.isRunning()) {
                showResultNotification();
                stopForeground(true);
                stopSelf();
                return;
            }
            float fraction = ParadoksClone.progressFraction();
            builder.setContentText(ParadoksClone.progressText());
            builder.setProgress(1000, fraction < 0 ? 0 : (int) (fraction * 1000), fraction < 0);
            try {
                NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(NOTIFICATION_ID, builder.build());
            } catch (Throwable ignore) {
            }
            renewWakeLock();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void showResultNotification() {
        String text = ParadoksClone.lastResultText();
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            Notification notification = new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle("Chat klonlash")
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .build();
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(RESULT_NOTIFICATION_ID, notification);
        } catch (Throwable ignore) {
        }
    }

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "paradoksgram:clone");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(WAKE_LOCK_MS);
            }
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) ApplicationLoader.applicationContext.getSystemService(Context.WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "paradoksgram:clone");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void renewWakeLock() {
        try {
            if (wakeLock != null) {
                wakeLock.acquire(WAKE_LOCK_MS);
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }
}
