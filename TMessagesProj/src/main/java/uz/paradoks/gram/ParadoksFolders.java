package uz.paradoks.gram;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Set;

/**
 * Folder tab extras (app-level, nothing is sent to the server), same idea as Plus Messenger:
 *
 * - "Unread": a folder can be switched to show only its unread chats.
 * - "Set as default tab": the app opens on the chosen folder instead of the first tab.
 *
 * Folders are told apart by their server id; All chats has id 0.
 */
public final class ParadoksFolders {

    private static volatile Set<String> unreadCache;

    private static String key(int account, int filterId) {
        return account + ":" + filterId;
    }

    private static Set<String> unreadSet() {
        Set<String> local = unreadCache;
        if (local == null) {
            local = ParadoksConfig.unreadOnlyFolders();
            unreadCache = local;
        }
        return local;
    }

    // ---- unread only ----

    public static boolean isUnreadOnly(int account, int filterId) {
        return unreadSet().contains(key(account, filterId));
    }

    /** Flips the flag and asks the chat list to rebuild. Returns the new state. */
    public static boolean toggleUnreadOnly(int account, int filterId) {
        Set<String> copy = ParadoksConfig.unreadOnlyFolders();
        String k = key(account, filterId);
        boolean now;
        if (copy.contains(k)) {
            copy.remove(k);
            now = false;
        } else {
            copy.add(k);
            now = true;
        }
        ParadoksConfig.setUnreadOnlyFolders(copy);
        unreadCache = copy;
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        return now;
    }

    public static boolean isUnread(TLRPC.Dialog dialog) {
        return dialog != null && (dialog.unread_count > 0 || dialog.unread_mark);
    }

    /**
     * Used by DialogsAdapter.updateItemList. dialogsType 0 is the All chats tab, 7 and 8 are the
     * other folders (the selected filter of the page). Returns the same list when nothing applies.
     */
    public static ArrayList<TLRPC.Dialog> filterUnread(int account, int dialogsType, int folderId, ArrayList<TLRPC.Dialog> source) {
        if (source == null || folderId != 0 || unreadSet().isEmpty() || ParadoksHidden.isShowing()) {
            return source;
        }
        MessagesController controller = MessagesController.getInstance(account);
        if (controller.getDialogFilters().size() <= 1) { // no tabs on screen, so the switch could not be turned off
            return source;
        }
        int filterId;
        if (dialogsType == 0) {
            filterId = 0;
        } else if (dialogsType == 7 || dialogsType == 8) {
            MessagesController.DialogFilter filter = controller.selectedDialogFilter[dialogsType - 7];
            filterId = filter == null ? 0 : filter.id;
        } else {
            return source;
        }
        if (!isUnreadOnly(account, filterId)) {
            return source;
        }
        ArrayList<TLRPC.Dialog> result = new ArrayList<>(source.size());
        for (int i = 0, n = source.size(); i < n; i++) {
            TLRPC.Dialog dialog = source.get(i);
            if (isUnread(dialog)) {
                result.add(dialog);
            }
        }
        return result;
    }

    // ---- default tab ----

    /** Stored default folder id, or -1 when the app should open on the first tab. */
    public static int defaultFolderId(int account) {
        return ParadoksConfig.defaultFolder(account);
    }

    public static void setDefaultFolderId(int account, int filterId) {
        ParadoksConfig.setDefaultFolder(account, filterId);
    }

    /** Position of the default folder among the tabs, or -1 when it is not set or no longer exists. */
    public static int defaultTabIndex(int account, ArrayList<MessagesController.DialogFilter> filters) {
        int wanted = defaultFolderId(account);
        if (wanted < 0 || filters == null) {
            return -1;
        }
        for (int i = 0; i < filters.size(); i++) {
            MessagesController.DialogFilter filter = filters.get(i);
            if (filter != null && filter.id == wanted) {
                return i;
            }
        }
        return -1;
    }

    private ParadoksFolders() {
    }
}
