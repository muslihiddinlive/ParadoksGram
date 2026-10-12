package uz.paradoks.gram;

import android.widget.Toast;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Set;

/**
 * Hidden chats. A hidden chat disappears from the normal chat list. Long-press the search
 * icon, enter the PIN and only the hidden chats are shown; long-press again to leave.
 *
 * Hiding also mutes the chat forever so no notification reveals it. Unhiding unmutes it,
 * unless it was already muted before it was hidden.
 */
public final class ParadoksHidden {

    /** True while the hidden list is on screen. Never persisted: a restart hides it again. */
    private static volatile boolean showing;

    private static String key(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    public static boolean isShowing() {
        return showing;
    }

    /** Leaves hidden mode (app went to background or the list was closed). */
    public static void leaveAndReload(int account) {
        if (showing) {
            showing = false;
            reload(account);
        }
    }

    public static boolean isHidden(int account, long dialogId) {
        return ParadoksConfig.hiddenChats().contains(key(account, dialogId));
    }

    /**
     * Used by DialogsAdapter.updateItemList: the normal list loses hidden chats; in hidden
     * mode the list contains only hidden chats. Returns the same list when nothing applies.
     */
    public static ArrayList<TLRPC.Dialog> filter(int account, ArrayList<TLRPC.Dialog> source) {
        if (source == null) {
            return null;
        }
        Set<String> hidden = ParadoksConfig.hiddenChats();
        if (hidden.isEmpty() && !showing) {
            return source;
        }
        ArrayList<TLRPC.Dialog> result = new ArrayList<>(source.size());
        for (int i = 0, n = source.size(); i < n; i++) {
            TLRPC.Dialog dialog = source.get(i);
            boolean isHidden = dialog != null && hidden.contains(key(account, dialog.id));
            if (showing == isHidden) {
                result.add(dialog);
            }
        }
        return result;
    }

    /** Long-press on the search icon. */
    public static void toggleMode(BaseFragment fragment, int account) {
        if (showing) {
            showing = false;
            reload(account);
            toast(fragment, "Yashirin chatlar yopildi");
            return;
        }
        ParadoksLock.verify(fragment, "Yashirin chatlar", () -> {
            showing = true;
            reload(account);
            toast(fragment, "Yashirin chatlar. Chiqish: lupani yana bosib turing");
        });
    }

    /** Chat menu: hide or unhide this chat. */
    public static void toggleChat(BaseFragment fragment, int account, long dialogId) {
        ParadoksLock.verify(fragment, "Yashirin chat", () -> {
            Set<String> hidden = ParadoksConfig.hiddenChats();
            String key = key(account, dialogId);
            NotificationsController notifications = NotificationsController.getInstance(account);
            if (hidden.contains(key)) {
                hidden.remove(key);
                ParadoksConfig.setHiddenChats(hidden);
                if (!ParadoksConfig.hiddenWasMuted(key)) {
                    notifications.muteDialog(dialogId, 0, false);
                }
                ParadoksConfig.clearHiddenWasMuted(key);
                toast(fragment, "Chat yashirin ro'yxatdan chiqarildi");
            } else {
                boolean wasMuted = MessagesController.getInstance(account).isDialogMuted(dialogId, 0);
                ParadoksConfig.setHiddenWasMuted(key, wasMuted);
                hidden.add(key);
                ParadoksConfig.setHiddenChats(hidden);
                if (!wasMuted) {
                    notifications.muteDialog(dialogId, 0, true);
                }
                toast(fragment, "Chat yashirildi va bildirishnomalari o'chirildi");
            }
            reload(account);
        });
    }

    private static void reload(int account) {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private static void toast(BaseFragment fragment, String text) {
        if (fragment.getParentActivity() != null) {
            Toast.makeText(fragment.getParentActivity(), text, Toast.LENGTH_SHORT).show();
        }
    }

    private ParadoksHidden() {
    }
}
