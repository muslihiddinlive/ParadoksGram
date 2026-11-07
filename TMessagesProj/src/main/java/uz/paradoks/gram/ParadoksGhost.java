package uz.paradoks.gram;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
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
        return shouldDropRequest(object, -1);
    }

    /**
     * Same as {@link #shouldDropRequest(TLObject)}, but knows the account the request belongs to, so a
     * dropped read receipt can be remembered and sent later, when ghost read is switched off.
     */
    public static boolean shouldDropRequest(TLObject object, int account) {
        if (object == null) return false;
        if (object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_channels_readHistory
                || object instanceof TLRPC.TL_messages_readEncryptedHistory
                || object instanceof TLRPC.TL_messages_readMessageContents
                || object instanceof TLRPC.TL_messages_readDiscussion) {
            if (ALLOWED.remove(object)) return false;
            if (ParadoksConfig.ghostRead()) {
                rememberRead(object, account);
                return true;
            }
            return false;
        }
        if (object instanceof TLRPC.TL_messages_setTyping) {
            return ParadoksConfig.ghostTyping();
        }
        if (object instanceof TL_account.updateStatus) {
            if (ALLOWED.remove(object)) return false;
            if (!ParadoksConfig.ghostOnline()) {
                onlineGhostActive = false;
                return false;
            }
            ensureTicker();
            if (!onlineGhostActive) {
                // first status packet after ghost-online became active: tell the server "offline" once
                onlineGhostActive = true;
                ((TL_account.updateStatus) object).offline = true;
                return false;
            }
            return true; // afterwards no status packets at all, so "last seen" stays frozen
        }
        if (object instanceof TL_stories.TL_stories_readStories
                || object instanceof TL_stories.TL_stories_incrementStoryViews) {
            return ParadoksConfig.ghostStories();
        }
        return false;
    }

    // ---- read receipts held back by ghost mode ----

    private static final class PendingRead {
        final int account;
        final TLObject request;
        final int maxId;

        PendingRead(int account, TLObject request, int maxId) {
            this.account = account;
            this.request = request;
            this.maxId = maxId;
        }
    }

    /**
     * While ghost read is on the chat is already marked as read locally, so nothing would ever
     * resend the receipt after ghost mode is switched off - the messages stayed unread on the server.
     * That is why the newest dropped readHistory per chat is kept here and sent by {@link #flushPendingReads}.
     */
    private static final java.util.HashMap<String, PendingRead> PENDING = new java.util.HashMap<>();
    private static boolean flushTickerRunning;

    private static void rememberRead(TLObject object, int account) {
        if (account < 0) {
            return;
        }
        String key;
        int maxId;
        if (object instanceof TLRPC.TL_messages_readHistory) {
            TLRPC.TL_messages_readHistory r = (TLRPC.TL_messages_readHistory) object;
            key = account + ":p:" + DialogObject.getPeerDialogId(r.peer);
            maxId = r.max_id;
        } else if (object instanceof TLRPC.TL_channels_readHistory) {
            TLRPC.TL_channels_readHistory r = (TLRPC.TL_channels_readHistory) object;
            key = account + ":c:" + (r.channel != null ? r.channel.channel_id : 0);
            maxId = r.max_id;
        } else {
            return;
        }
        synchronized (PENDING) {
            PendingRead old = PENDING.get(key);
            if (old == null || old.maxId < maxId) {
                PENDING.put(key, new PendingRead(account, object, maxId));
            }
        }
        ensureFlushTicker();
    }

    /** Sends the held back read receipts, if ghost read is not active any more. */
    public static void flushPendingReads() {
        if (ParadoksConfig.ghostRead()) {
            return;
        }
        java.util.ArrayList<PendingRead> list;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            list = new java.util.ArrayList<>(PENDING.values());
            PENDING.clear();
        }
        for (PendingRead p : list) {
            ALLOWED.add(p.request);
            ConnectionsManager.getInstance(p.account).sendRequest(p.request, (response, error) -> {});
        }
    }

    /** Called when a ghost read switch or the ghost schedule changes. */
    public static void onReadSettingsChanged() {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(ParadoksGhost::flushPendingReads);
    }

    private static final Runnable FLUSH_TICK = new Runnable() {
        @Override
        public void run() {
            flushPendingReads();
            if (flushTickerDone()) {
                return;
            }
            // still held back (ghost is active): the schedule window may end without any event, so re-check
            org.telegram.messenger.AndroidUtilities.runOnUIThread(this, 15000);
        }
    };

    private static synchronized boolean flushTickerDone() {
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                flushTickerRunning = false;
                return true;
            }
        }
        return false;
    }

    private static synchronized void ensureFlushTicker() {
        if (flushTickerRunning) {
            return;
        }
        flushTickerRunning = true;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(FLUSH_TICK, 15000);
    }

    /**
     * The fake answer for a dropped request must be of the SAME type the server's real answer would have:
     * callbacks cast the response to that type, and a wrong one crashes with ClassCastException
     * (e.g. TL_messages_affectedMessages cannot be cast to TLRPC.Bool).
     */
    public static TLObject fakeResponse(TLObject object) {
        if (object instanceof TLRPC.TL_messages_readHistory || object instanceof TLRPC.TL_messages_readMessageContents) {
            TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
            affected.pts = 0;
            affected.pts_count = 0;
            return affected;
        }
        if (object instanceof TL_stories.TL_stories_readStories) {
            // real answer: Vector<int> (ids of the stories that were marked read) - empty is fine
            return new org.telegram.tgnet.Vector<TLObject>((stream, constructor, exception) -> null);
        }
        // channels.readHistory, messages.readEncryptedHistory, messages.readDiscussion, messages.setTyping,
        // account.updateStatus, stories.incrementStoryViews all answer with Bool
        return new TLRPC.TL_boolTrue();
    }

    /** True once the "offline" packet of the current ghost-online period has been sent. */
    private static volatile boolean onlineGhostActive;

    /**
     * Called when the online part is switched on: sends one account.updateStatus(offline=true) for every
     * logged-in account right away, otherwise the server keeps showing "online" until the old status expires.
     */
    public static void onOnlineSwitched(boolean on) {
        if (!on) {
            onlineGhostActive = false;
            return;
        }
        onlineGhostActive = true;
        sendOfflineAll();
        ensureTicker();
    }

    private static void sendOfflineAll() {
        for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            TL_account.updateStatus request = new TL_account.updateStatus();
            request.offline = true;
            ALLOWED.add(request);
            ConnectionsManager.getInstance(a).sendRequest(request, (response, error) -> {});
        }
    }

    private static volatile boolean tickerRunning;
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!ParadoksConfig.ghostOnline()) {
                tickerRunning = false;
                return;
            }
            // Some actions (sending a message, opening a chat...) make the server show "online" again,
            // so while the online part is on, the "offline" status is repeated.
            sendOfflineAll();
            org.telegram.messenger.AndroidUtilities.runOnUIThread(this, 15000);
        }
    };

    private static synchronized void ensureTicker() {
        if (tickerRunning) {
            return;
        }
        tickerRunning = true;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(TICK, 15000);
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

    // ---- top-bar button ----

    /** True when at least one ghost part is switched on (the schedule is not considered). */
    public static boolean anyOn() {
        return ParadoksConfig.ghostReadSwitch() || ParadoksConfig.ghostTypingSwitch()
                || ParadoksConfig.ghostOnlineSwitch() || ParadoksConfig.ghostStoriesSwitch();
    }

    /**
     * One tap: if anything is on, remember which parts and switch them all off; otherwise bring
     * the remembered parts back (or all four on the very first time). Returns the new state.
     */
    public static boolean toggleAll() {
        if (anyOn()) {
            int mask = (ParadoksConfig.ghostReadSwitch() ? 1 : 0)
                    | (ParadoksConfig.ghostTypingSwitch() ? 2 : 0)
                    | (ParadoksConfig.ghostOnlineSwitch() ? 4 : 0)
                    | (ParadoksConfig.ghostStoriesSwitch() ? 8 : 0);
            ParadoksConfig.setGhostSavedMask(mask);
            ParadoksConfig.setGhostRead(false);
            ParadoksConfig.setGhostTyping(false);
            ParadoksConfig.setGhostOnline(false);
            ParadoksConfig.setGhostStories(false);
            return false;
        }
        int mask = ParadoksConfig.ghostSavedMask();
        if (mask == 0) {
            mask = 15;
        }
        ParadoksConfig.setGhostRead((mask & 1) != 0);
        ParadoksConfig.setGhostTyping((mask & 2) != 0);
        ParadoksConfig.setGhostOnline((mask & 4) != 0);
        ParadoksConfig.setGhostStories((mask & 8) != 0);
        return true;
    }

    /** Solid ghost = on, outlined ghost = off. */
    public static int iconRes() {
        return anyOn() ? R.drawable.paradoks_ghost_on : R.drawable.paradoks_ghost_off;
    }

    public static String statusText(boolean on) {
        if (!on) {
            return "Ghost mode o'chirildi";
        }
        boolean activeNow = ParadoksConfig.ghostRead() || ParadoksConfig.ghostTyping()
                || ParadoksConfig.ghostOnline() || ParadoksConfig.ghostStories();
        if (!activeNow) {
            return "Ghost mode yoqildi, lekin jadval bo'yicha hozir ishlamaydi";
        }
        return "Ghost mode yoqildi: " + (ParadoksConfig.ghostRead() ? "o'qildi ✔  " : "o'qildi ✘  ")
                + (ParadoksConfig.ghostTyping() ? "yozyapti ✔  " : "yozyapti ✘  ")
                + (ParadoksConfig.ghostOnline() ? "online ✔  " : "online ✘  ")
                + (ParadoksConfig.ghostStories() ? "story ✔" : "story ✘");
    }

    private ParadoksGhost() {}
}
