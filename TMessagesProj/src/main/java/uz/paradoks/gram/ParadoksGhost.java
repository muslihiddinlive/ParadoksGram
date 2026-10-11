package uz.paradoks.gram;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Ghost mode: prevents Telegram from leaking your presence to others. Each part has its
 * own switch (and an optional daily schedule), see {@link ParadoksConfig}.
 *
 *   read    - messages.readHistory & co: server never learns you read a message
 *   typing  - messages.setTyping is silently dropped
 *   online  - account.updateStatus is always sent as offline=true
 *   stories - stories.readStories / incrementStoryViews are dropped
 *
 * Integration:
 *   - {@link #shouldDropRequest(TLObject)} - ConnectionsManager.sendRequestInternal
 *   - {@link #forceOffline(TLObject)}      - same, for updateStatus requests
 *   - {@link #shouldSendTyping()}          - MessagesController.sendTyping
 */
public final class ParadoksGhost {

    /** Requests created by {@link #markReadNow} that must pass the filter exactly once. */
    private static final Set<TLObject> ALLOWED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<TLObject, Boolean>()));

    /**
     * Returns true if the outgoing request must be silently dropped.
     * Called from ConnectionsManager.sendRequestInternal before native_sendRequest.
     */
    public static boolean shouldDropRequest(TLObject object) {
        if (object == null) return false;
        if (object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_channels_readHistory
                || object instanceof TLRPC.TL_messages_readEncryptedHistory
                || object instanceof TLRPC.TL_messages_readMessageContents
                || object instanceof TLRPC.TL_messages_readDiscussion) {
            if (ALLOWED.remove(object)) return false;
            return ParadoksConfig.ghostRead();
        }
        if (object instanceof TLRPC.TL_messages_setTyping) {
            return ParadoksConfig.ghostTyping();
        }
        if (object instanceof TL_stories.TL_stories_readStories
                || object instanceof TL_stories.TL_stories_incrementStoryViews) {
            return ParadoksConfig.ghostStories();
        }
        return false;
    }

    /**
     * If the online part is on and this is account.updateStatus(offline=false),
     * flip it to offline=true so we always appear offline.
     */
    public static void forceOffline(TLObject object) {
        if (object == null || !ParadoksConfig.ghostOnline()) return;
        if (object instanceof TL_account.updateStatus) {
            ((TL_account.updateStatus) object).offline = true;
        }
    }

    /** Returns false when typing is hidden - caller skips sendTyping entirely. */
    public static boolean shouldSendTyping() {
        return !ParadoksConfig.ghostTyping();
    }

    /**
     * Manually tells the server that the chat is read, even while ghost read is on.
     * Locally the chat is already marked as read; this only sends the receipt.
     */
    public static boolean markReadNow(int account, long dialogId) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = mc.dialogs_dict.get(dialogId);
        int maxId = dialog != null ? dialog.top_message : 0;
        if (maxId <= 0) {
            return false;
        }
        TLObject req;
        if (DialogObject.isChatDialog(dialogId) && ChatObject.isChannel(mc.getChat(-dialogId))) {
            TLRPC.TL_channels_readHistory r = new TLRPC.TL_channels_readHistory();
            r.channel = mc.getInputChannel(-dialogId);
            r.max_id = maxId;
            req = r;
        } else {
            TLRPC.TL_messages_readHistory r = new TLRPC.TL_messages_readHistory();
            r.peer = mc.getInputPeer(dialogId);
            r.max_id = maxId;
            req = r;
        }
        ALLOWED.add(req);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {});
        return true;
    }

    private ParadoksGhost() {}
}
