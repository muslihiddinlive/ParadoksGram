package uz.paradoks.gram;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.tl.TL_account;

/**
 * Ghost mode: prevents Telegram from leaking your presence to others.
 *
 * Blocks when active:
 *   1. Read receipts   — server never learns you read a message
 *   2. Online status   — account.updateStatus is always sent as offline=true
 *   3. Typing          — messages.setTyping is silently dropped
 *
 * Integration:
 *   - {@link #shouldDropRequest(TLObject)} — ConnectionsManager.sendRequestInternal
 *   - {@link #forceOffline(TLObject)}      — same, for updateStatus requests
 *   - {@link #shouldSendTyping()}          — MessagesController.sendTyping
 */
public final class ParadoksGhost {

    /**
     * Returns true if the outgoing request must be silently dropped.
     * Called from ConnectionsManager.sendRequestInternal before native_sendRequest.
     */
    public static boolean shouldDropRequest(TLObject object) {
        if (object == null || !ParadoksConfig.ghostMode()) return false;
        return object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_channels_readHistory
                || object instanceof TLRPC.TL_messages_readEncryptedHistory
                || object instanceof TLRPC.TL_messages_readMessageContents
                || object instanceof TLRPC.TL_messages_readDiscussion
                || object instanceof TLRPC.TL_messages_setTyping;
    }

    /**
     * If ghost mode is on and this is account.updateStatus(offline=false),
     * flip it to offline=true so we always appear offline.
     */
    public static void forceOffline(TLObject object) {
        if (object == null || !ParadoksConfig.ghostMode()) return;
        if (object instanceof TL_account.updateStatus) {
            ((TL_account.updateStatus) object).offline = true;
        }
    }

    /**
     * Returns false when ghost mode is on — caller skips sendTyping entirely.
     */
    public static boolean shouldSendTyping() {
        return !ParadoksConfig.ghostMode();
    }

    private ParadoksGhost() {}
}
