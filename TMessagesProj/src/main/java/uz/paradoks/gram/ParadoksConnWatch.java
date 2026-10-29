package uz.paradoks.gram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/**
 * Notices when the app stays on "Connecting..." for too long and offers to send an error report.
 * It never sends anything by itself: the user has to press "Yuborish".
 */
public final class ParadoksConnWatch implements NotificationCenter.NotificationCenterDelegate {

    private static final long STUCK_MS = 45_000;
    private static final long COOLDOWN_MS = 10 * 60_000;

    private static ParadoksConnWatch instance;

    private long badSince;
    private long lastShown;
    private final Runnable check = this::checkStuck;

    public static void start() {
        if (instance != null || !ParadoksErrorReport.isConfigured()) {
            return;
        }
        instance = new ParadoksConnWatch();
        NotificationCenter.getGlobalInstance().addObserver(instance, NotificationCenter.didUpdateConnectionState);
    }

    private static boolean isBad(int state) {
        return state == ConnectionsManager.ConnectionStateConnecting
                || state == ConnectionsManager.ConnectionStateWaitingForNetwork
                || state == ConnectionsManager.ConnectionStateConnectingToProxy;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didUpdateConnectionState) {
            return;
        }
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (isBad(state)) {
            if (badSince == 0) {
                badSince = System.currentTimeMillis();
                AndroidUtilities.runOnUIThread(check, STUCK_MS);
            }
        } else {
            badSince = 0;
            AndroidUtilities.cancelRunOnUIThread(check);
        }
    }

    private void checkStuck() {
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        long now = System.currentTimeMillis();
        if (!isBad(state) || badSince == 0 || now - badSince < STUCK_MS - 1000 || now - lastShown < COOLDOWN_MS) {
            return;
        }
        lastShown = now;
        final String details = "Ulanish holati: " + state + " (1=ulanmoqda, 2=tarmoq kutilmoqda, 4=proxyga ulanmoqda)\n"
                + "Shu holatda turgan vaqt: " + ((now - badSince) / 1000) + " soniya";
        final Activity activity = LaunchActivity.instance;
        // Avtomatik yuboriladi, dialog yo'q. Activity bo'lmasa ham (fon rejimi) ApplicationLoader konteksti bilan yuboriladi.
        ParadoksErrorReport.sendAsync(activity, "ulanish muammosi", details);
    }

    private ParadoksConnWatch() {
    }
}
