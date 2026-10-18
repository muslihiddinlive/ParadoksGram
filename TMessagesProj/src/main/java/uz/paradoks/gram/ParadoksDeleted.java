package uz.paradoks.gram;

import android.database.Cursor;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Keeps messages that were deleted by someone else: they stay in Telegram's own message
 * database and in the chat, and are marked as deleted ("o'chirilgan") in the UI.
 * Deleting messages yourself still removes them normally.
 */
public final class ParadoksDeleted {

    private static final Object LOCK = new Object();
    private static HashSet<String> cache;

    private static String key(int account, long dialogKey, int messageId) {
        return account + ":" + dialogKey + ":" + messageId;
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (cache != null) {
                return;
            }
            HashSet<String> set = new HashSet<>();
            Cursor cursor = null;
            try {
                cursor = ParadoksDb.get().getReadableDatabase().rawQuery("SELECT acc, dlg, mid FROM deleted", null);
                while (cursor.moveToNext()) {
                    set.add(key(cursor.getInt(0), cursor.getLong(1), cursor.getInt(2)));
                }
            } catch (Throwable ignore) {
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            cache = set;
        }
    }

    /**
     * Called with the deletions Telegram is about to apply. Everything in {@code map} is
     * marked as deleted and removed from the map, so Telegram does not delete it.
     * Map key: 0 for private chats / basic groups, -channelId for channels and supergroups.
     */
    public static void filter(int account, LongSparseArray<ArrayList<Integer>> map) {
        if (map == null || !ParadoksConfig.keepDeleted()) {
            return;
        }
        for (int i = map.size() - 1; i >= 0; i--) {
            long dialogKey = map.keyAt(i);
            ArrayList<Integer> ids = map.valueAt(i);
            if (ids != null && !ids.isEmpty()) {
                mark(account, dialogKey, ids);
            }
            map.removeAt(i);
        }
    }

    /** Push-notification deletions. Returns true when the deletion must be skipped. */
    public static boolean keepByPush(int account, long dialogKey, ArrayList<Integer> ids) {
        if (!ParadoksConfig.keepDeleted() || ids == null || ids.isEmpty()) {
            return false;
        }
        mark(account, dialogKey, ids);
        return true;
    }

    private static void mark(int account, long dialogKey, ArrayList<Integer> ids) {
        ensureLoaded();
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        synchronized (LOCK) {
            for (int id : copy) {
                cache.add(key(account, dialogKey, id));
            }
        }
        ParadoksDb.async(() -> {
            for (int id : copy) {
                ParadoksDb.get().insertDeleted(account, dialogKey, id);
            }
        });
        ParadoksMedia.saveFor(account, dialogKey, copy);
    }

    /** Continues unfinished deleted-media copies from the previous session. */
    public static void resumePendingMedia() {
        ParadoksMedia.installPrefetch();
        ParadoksMedia.resumePending();
    }

    public static boolean isDeleted(int account, MessageObject object) {
        if (object == null || object.messageOwner == null || object.getId() <= 0) {
            return false;
        }
        ensureLoaded();
        TLRPC.Peer peer = object.messageOwner.peer_id;
        long dialogKey = (peer != null && peer.channel_id != 0) ? -peer.channel_id : 0;
        synchronized (LOCK) {
            return cache.contains(key(account, dialogKey, object.getId()));
        }
    }

    private ParadoksDeleted() {
    }
}
