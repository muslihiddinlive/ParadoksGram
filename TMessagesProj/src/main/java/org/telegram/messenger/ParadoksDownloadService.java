package org.telegram.messenger;

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

import java.util.Locale;

/**
 * ParadoksGram: keeps big file downloads running when the app goes to the background.
 * A foreground service stops Android from freezing the process, and the wake/Wi-Fi locks keep
 * the CPU and Wi-Fi awake while the screen is off. It stops itself when no big download is left.
 */
public class ParadoksDownloadService extends Service {

    public static final long MIN_SIZE = 2L * 1024 * 1024;
    private static final int NOTIFICATION_ID = 7421;
    private static final long TICK_MS = 1000;
    private static final long WAKE_LOCK_MS = 10 * 60 * 1000L;

    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationCompat.Builder builder;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private int idleTicks;
    private int account;

    /** Called from FileLoader when a download operation is registered. */
    public static void maybeStart(int account, long size) {
        if (size < MIN_SIZE || running) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (running) {
                return;
            }
            try {
                Intent intent = new Intent(ApplicationLoader.applicationContext, ParadoksDownloadService.class);
                intent.putExtra("account", account);
                ApplicationLoader.applicationContext.startForegroundService(intent);
                running = true;
            } catch (Throwable e) {
                // Android may refuse to start a foreground service from the background; just download as before.
                FileLog.e(e);
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        account = intent != null ? intent.getIntExtra("account", UserConfig.selectedAccount) : UserConfig.selectedAccount;
        if (builder == null) {
            NotificationsController.checkOtherNotificationsChannel();
            builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
            builder.setSmallIcon(android.R.drawable.stat_sys_download);
            builder.setContentTitle(LocaleController.getString(R.string.AppName));
            builder.setOngoing(true);
            builder.setOnlyAlertOnce(true);
            builder.setPriority(NotificationCompat.PRIORITY_LOW);
            builder.setContentText("Yuklab olinmoqda…");
            builder.setProgress(100, 0, true);
        }
        try {
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Throwable e) {
            FileLog.e(e);
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireLocks();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long[] stats = FileLoader.getInstance(account).paradoksBigDownloads();
            if (stats[0] == 0) {
                idleTicks++;
                if (idleTicks >= 3) {
                    running = false;
                    stopSelf();
                    return;
                }
            } else {
                idleTicks = 0;
                updateNotification(stats[0], stats[1], stats[2]);
                renewWakeLock();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void updateNotification(long count, long done, long total) {
        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        String text = String.format(Locale.US, "%d ta fayl yuklanmoqda • %d%% (%.1f / %.1f MB)", count, percent, done / 1048576f, total / 1048576f);
        builder.setContentText(text);
        builder.setProgress(100, percent, total <= 0);
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable ignore) {
        }
    }

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "paradoksgram:download");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(WAKE_LOCK_MS);
            }
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) ApplicationLoader.applicationContext.getSystemService(Context.WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "paradoksgram:download");
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
        super.onDestroy();
        running = false;
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
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID);
    }
}
