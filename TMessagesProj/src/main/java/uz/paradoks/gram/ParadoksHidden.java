package uz.paradoks.gram;

import android.widget.Toast;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Set;

/**
 * Hidden chats (app-level, nothing is sent to the server).
 *
 * A hidden chat disappears from the normal chat list, from share/forward pickers, from
 * global search (unless enabled in settings) and its messages never create notifications.
 * Long-press the search icon, enter the PIN and only hidden chats are shown; long-press
 * again to leave. Opening a hidden chat any other way (a link, for example) asks for the PIN.
 *
 * Same idea as Partisan Telegram's fake-passcode hiding: filter inside the client instead of
 * muting on the server, so other devices and the server settings stay untouched.
 */
public final class ParadoksHidden {

    /** True while the hidden list is on screen. Never persisted: a restart hides it again. */
    private static volatile boolean showing;
    private static volatile Set<String> cache;

    private static String key(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    private static Set<String> hiddenSet() {
        Set<String> local = cache;
        if (local == null) {
            local = ParadoksConfig.hiddenChats();
            cache = local;
        }
        return local;
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
        return dialogId != 0 && !hiddenSet().isEmpty() && hiddenSet().contains(key(account, dialogId));
    }

    /** Notifications: hidden chats never notify. */
    public static boolean hideNotification(int account, long dialogId) {
        return isHidden(account, dialogId);
    }

    /** Global search: hidden chats are left out unless the user turned that on in settings. */
    public static boolean hideInSearch(int account, long dialogId) {
        return !showing && !ParadoksConfig.showHiddenInSearch() && isHidden(account, dialogId);
    }

    public static boolean hideObjectInSearch(int account, Object object) {
        long id;
        if (object instanceof TLRPC.User) {
            id = ((TLRPC.User) object).id;
        } else if (object instanceof TLRPC.Chat) {
            id = -((TLRPC.Chat) object).id;
        } else if (object instanceof TLRPC.EncryptedChat) {
            id = DialogObject.makeEncryptedDialogId(((TLRPC.EncryptedChat) object).id);
        } else {
            return false;
        }
        return hideInSearch(account, id);
    }

    /** Forward / share pickers: hidden chats are never offered (except inside hidden mode). */
    public static boolean hideInPicker(int account, long dialogId) {
        return !showing && isHidden(account, dialogId);
    }

    public static ArrayList<TLRPC.Dialog> filterPicker(int account, ArrayList<TLRPC.Dialog> source) {
        if (source == null || showing || hiddenSet().isEmpty()) {
            return source;
        }
        ArrayList<TLRPC.Dialog> result = new ArrayList<>(source.size());
        for (int i = 0, n = source.size(); i < n; i++) {
            TLRPC.Dialog dialog = source.get(i);
            if (dialog == null || !isHidden(account, dialog.id)) {
                result.add(dialog);
            }
        }
        return result;
    }

    /**
     * Used by DialogsAdapter.updateItemList: the normal list loses hidden chats; in hidden
     * mode the list contains only hidden chats. Returns the same list when nothing applies.
     */
    public static ArrayList<TLRPC.Dialog> filter(int account, ArrayList<TLRPC.Dialog> source) {
        if (source == null) {
            return null;
        }
        if (hiddenSet().isEmpty() && !showing) {
            return source;
        }
        ArrayList<TLRPC.Dialog> result = new ArrayList<>(source.size());
        for (int i = 0, n = source.size(); i < n; i++) {
            TLRPC.Dialog dialog = source.get(i);
            boolean isHidden = dialog != null && isHidden(account, dialog.id);
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
            if (hidden.contains(key)) {
                hidden.remove(key);
                ParadoksConfig.setHiddenChats(hidden);
                cache = null;
                // Chats hidden by an earlier build were also muted on the server. Undo that,
                // unless they were already muted before.
                if (ParadoksConfig.hasHiddenWasMuted(key)) {
                    if (!ParadoksConfig.hiddenWasMuted(key)) {
                        NotificationsController.getInstance(account).muteDialog(dialogId, 0, false);
                    }
                    ParadoksConfig.clearHiddenWasMuted(key);
                }
                toast(fragment, "Chat yashirin ro'yxatdan chiqarildi");
            } else {
                hidden.add(key);
                ParadoksConfig.setHiddenChats(hidden);
                cache = null;
                toast(fragment, "Chat yashirildi. Uning xabarlari bildirishnoma chiqarmaydi");
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
