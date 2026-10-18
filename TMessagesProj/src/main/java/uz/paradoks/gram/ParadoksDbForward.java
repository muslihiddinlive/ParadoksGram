package uz.paradoks.gram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * "Deleted media database": every incoming photo/video/file (from private chats and groups) is
 * forwarded silently to the chat the user picked, so a copy exists even if the sender deletes it.
 *
 * Safety rules: own messages, the database chat itself (prevents forward loops), secret chats,
 * channels, view-once media, stickers and chats with forwarding disabled are never forwarded.
 * Forwards are sent one by one with a pause so Telegram flood limits are not hit.
 */
final class ParadoksDbForward {

    private static final int MAX_QUEUE = 100;
    private static final int MAX_SEEN = 500;
    private static final long INTERVAL_MS = 1500;

    private static final class Pending {
        final int account;
        final MessageObject object;

        Pending(int account, MessageObject object) {
            this.account = account;
            this.object = object;
        }
    }

    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private static final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private static boolean draining;

    private ParadoksDbForward() {
    }

    @SuppressWarnings("unchecked")
    static final NotificationCenter.NotificationCenterDelegate OBSERVER = (id, account, args) -> {
        try {
            if (id != NotificationCenter.didReceiveNewMessages || args == null || args.length < 3) {
                return;
            }
            long target = ParadoksConfig.dbTarget(account);
            if (target == 0 || !UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            if (Boolean.TRUE.equals(args[2])) { // scheduled messages
                return;
            }
            ArrayList<MessageObject> list = new ArrayList<>((ArrayList<MessageObject>) args[1]);
            for (MessageObject object : list) {
                if (eligible(object, target)) {
                    enqueue(account, object);
                }
            }
            if (!queue.isEmpty() && !draining) {
                draining = true;
                AndroidUtilities.runOnUIThread(ParadoksDbForward::drain, 500);
            }
        } catch (Throwable ignore) {
        }
    };

    private static boolean eligible(MessageObject object, long target) {
        TLRPC.Message m = object.messageOwner;
        if (m == null || m.out || m.id <= 0 || m.post || m.media == null) {
            return false;
        }
        long dialogId = object.getDialogId();
        if (dialogId == target || DialogObject.isEncryptedDialog(dialogId)) {
            return false; // loop protection + secret chats
        }
        if (m.media.photo == null && m.media.document == null) {
            return false; // only media
        }
        if (MessageObject.isStickerMessage(m) || MessageObject.isAnimatedStickerMessage(m) || MessageObject.isSecretMedia(m)) {
            return false;
        }
        return object.canForwardMessage(); // respects "restrict saving content"
    }

    private static void enqueue(int account, MessageObject object) {
        String key = account + "_" + object.getDialogId() + "_" + object.getId();
        if (!seen.add(key)) {
            return;
        }
        if (seen.size() > MAX_SEEN) {
            Iterator<String> it = seen.iterator();
            it.next();
            it.remove();
        }
        queue.add(new Pending(account, object));
        if (queue.size() > MAX_QUEUE) {
            queue.poll();
        }
    }

    private static void drain() {
        Pending pending = queue.poll();
        if (pending == null) {
            draining = false;
            return;
        }
        try {
            long target = ParadoksConfig.dbTarget(pending.account);
            if (target != 0 && UserConfig.getInstance(pending.account).isClientActivated()) {
                ArrayList<MessageObject> one = new ArrayList<>();
                one.add(pending.object);
                SendMessagesHelper.getInstance(pending.account).sendMessage(one, target, false, false, false, 0, 0);
            }
        } catch (Throwable ignore) {
        }
        AndroidUtilities.runOnUIThread(ParadoksDbForward::drain, INTERVAL_MS);
    }
}
