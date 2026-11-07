package uz.paradoks.gram;

import android.app.Activity;

import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

/**
 * "Tasodifiy bosishdan himoya": asks before an outgoing call. Each confirmation is valid for 20 seconds, so the
 * second pass of the call code (after "Qo'ng'iroq qilish") goes straight through.
 */
public final class ParadoksSafety {

    private static long allowedUntil;

    private ParadoksSafety() {
    }

    /**
     * @return true when a confirmation dialog was shown (the caller must stop and wait for {@code proceed}),
     *         false when the call may continue right away.
     */
    public static boolean confirmCall(Activity activity, TLRPC.User user, boolean video, Runnable proceed) {
        if (!ParadoksConfig.confirmCalls() || activity == null || activity.isFinishing()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < allowedUntil) {
            return false;
        }
        String name = user == null ? "" : UserObject.getUserName(user);
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(video ? "Video qo'ng'iroq" : "Qo'ng'iroq")
                    .setMessage(name.isEmpty() ? "Qo'ng'iroq qilinsinmi?" : name + " ga qo'ng'iroq qilinsinmi?")
                    .setPositiveButton("Qo'ng'iroq qilish", (d, w) -> {
                        allowedUntil = System.currentTimeMillis() + 20000;
                        proceed.run();
                    })
                    .setNegativeButton("Bekor qilish", null)
                    .show();
            return true;
        } catch (Throwable t) {
            return false; // never block a call because the dialog failed
        }
    }
}
