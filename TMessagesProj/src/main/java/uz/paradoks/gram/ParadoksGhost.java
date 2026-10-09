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

    // TL constructor IDs we intercept
    private static final int CID_READ_HISTORY    = TLRPC.TL_messages_readHistory.constructor;
    private static final int CID_CHANNELS_READ   = TLRPC.TL_channels_readHistory.constructor;
    private static final int CID_READ_ENC        = TLRPC.TL_messages_readEncryptedHistory.constructor;
    private static final int CID_READ_CONTENTS   = TLRPC.TL_messages_readMessageContents.constructor;
    private static final int CID_READ_DISCUSSION = TLRPC.TL_messages_readDiscussion.constructor;
    private static final int CID_SET_TYPING      = TLRPC.TL_messages_setTyping.constructor;
    private static final int CID_UPDATE_STATUS   = TL_account.updateStatus.constructor; // 0x6628562c

    /**
     * Returns true if the outgoing request must be silently dropped.
     * Called from ConnectionsManager.sendRequestInternal before native_sendRequest.
     */
    public static boolean shouldDropRequest(TLObject object) {
        if (object == null || !ParadoksConfig.ghostMode()) return false;
        int cid = object.getConstructor();
        return cid == CID_READ_HISTORY
                || cid == CID_CHANNELS_READ
                || cid == CID_READ_ENC
                || cid == CID_READ_CONTENTS
                || cid == CID_READ_DISCUSSION
                || cid == CID_SET_TYPING;
    }

    /**
     * If ghost mode is on and this is account.updateStatus(offline=false),
     * flip it to offline=true so we always appear offline.
     */
    public static void forceOffline(TLObject object) {
        if (object == null || !ParadoksConfig.ghostMode()) return;
        if (object.getConstructor() == CID_UPDATE_STATUS) {
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
