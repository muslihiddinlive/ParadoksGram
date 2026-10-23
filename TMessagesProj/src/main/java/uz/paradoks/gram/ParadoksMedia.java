package uz.paradoks.gram;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Keeps the media of messages deleted by someone else. When a deletion is intercepted,
 * the file is copied from Telegram's cache into the app's own folder (so cache cleanup
 * never removes it). If the file was not downloaded yet, a download is started and the
 * copy is made as soon as it finishes.
 */
final class ParadoksMedia {

    private static final long MAX_SIZE = 150L * 1024 * 1024;
    private static final long[] RETRY_DELAYS_MS = {3000, 10000, 30000, 90000, 300000, 900000, 3600000};
    /** Pending items older than this are dropped (seconds). */
    private static final long PENDING_TTL_SEC = 3L * 24 * 3600;

    /** Incoming media above this size is not pre-downloaded. */
    private static final long PREFETCH_MAX_SIZE = 25L * 1024 * 1024;
    private static boolean prefetchInstalled;

    static final int KIND_PHOTO = 0;
    static final int KIND_VIDEO = 1;
    static final int KIND_OTHER = 2;

    static void saveFor(int account, long dialogKey, ArrayList<Integer> ids) {
        if (!ParadoksConfig.keepDeletedMedia() || ids == null || ids.isEmpty()) {
            return;
        }
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            for (int mid : copy) {
                TLRPC.Message message = loadMessage(account, dialogKey, mid);
                if (message == null || message.media == null) {
                    continue;
                }
                if (message.media.photo == null && message.media.document == null) {
                    continue;
                }
                final int fmid = mid;
                ParadoksDb.async(() -> ParadoksDb.get().insertPending(account, dialogKey, fmid));
                process(account, dialogKey, mid, message, 0);
            }
        });
    }

    /**
     * Called shortly after app start: continues copying media that could not be saved in the
     * previous session (no connection, app killed, ...).
     */
    static void resumePending() {
        if (!ParadoksConfig.keepDeletedMedia()) {
            return;
        }
        ParadoksDb.async(() -> {
            ParadoksDb.get().purgePending(System.currentTimeMillis() / 1000 - PENDING_TTL_SEC);
            for (long[] row : ParadoksDb.get().queryPending()) {
                final int account = (int) row[0];
                final long dialogKey = row[1];
                final int mid = (int) row[2];
                if (!org.telegram.messenger.UserConfig.getInstance(account).isClientActivated()) {
                    continue;
                }
                MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
                    TLRPC.Message message = loadMessage(account, dialogKey, mid);
                    if (message == null || message.media == null || (message.media.photo == null && message.media.document == null)) {
                        ParadoksDb.async(() -> ParadoksDb.get().deletePending(account, dialogKey, mid));
                        return;
                    }
                    process(account, dialogKey, mid, message, 0);
                });
            }
        });
    }

    /**
     * Pre-save: every new incoming photo/file (private chats and groups, Wi-Fi only, up to 25 MB)
     * is downloaded in the background. If the sender deletes the message later, the file is
     * already in the cache and saveFor() copies it to the permanent folder. Without this the
     * server usually removes the file together with the message and it can no longer be loaded.
     */
    static void installPrefetch() {
        AndroidUtilities.runOnUIThread(() -> {
            if (prefetchInstalled) {
                return;
            }
            prefetchInstalled = true;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).addObserver(PREFETCH_OBSERVER, NotificationCenter.didReceiveNewMessages);
                NotificationCenter.getInstance(a).addObserver(ParadoksDbForward.OBSERVER, NotificationCenter.didReceiveNewMessages);
                NotificationCenter.getInstance(a).addObserver(ParadoksDbForward.SERVER_OBSERVER, NotificationCenter.messageReceivedByServer);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static final NotificationCenter.NotificationCenterDelegate PREFETCH_OBSERVER = (id, account, args) -> {
        try {
            if (id != NotificationCenter.didReceiveNewMessages || args == null || args.length < 3) {
                return;
            }
            if (!ParadoksConfig.keepDeletedMedia() || !ParadoksConfig.preSaveMedia()) {
                return;
            }
            if (!UserConfig.getInstance(account).isClientActivated() || !ApplicationLoader.isConnectedToWiFi()) {
                return;
            }
            if (Boolean.TRUE.equals(args[2])) { // scheduled messages
                return;
            }
            ArrayList<MessageObject> list = new ArrayList<>((ArrayList<MessageObject>) args[1]);
            for (MessageObject object : list) {
                TLRPC.Message message = object.messageOwner;
                if (message == null || message.out || message.id <= 0 || message.post || message.media == null) {
                    continue;
                }
                if (message.media.photo == null && message.media.document == null) {
                    continue;
                }
                if (MessageObject.isStickerMessage(message) || MessageObject.isAnimatedStickerMessage(message)) {
                    continue;
                }
                if (message.media.document != null && message.media.document.size > PREFETCH_MAX_SIZE) {
                    continue;
                }
                startDownload(account, message, FileLoader.PRIORITY_LOW);
            }
        } catch (Throwable ignore) {
        }
    };

    /** Downloads the media of an arbitrary message (used to fetch the database copy of a deleted message). */
    static void downloadFor(int account, TLRPC.Message message) {
        AndroidUtilities.runOnUIThread(() -> startDownload(account, message, FileLoader.PRIORITY_NORMAL));
    }

    /** Manual retry from the radar: loads the kept message again and tries to download/copy it. */
    static void retryNow(int account, long dialogKey, int mid) {
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            TLRPC.Message message = loadMessage(account, dialogKey, mid);
            if (message == null || message.media == null || (message.media.photo == null && message.media.document == null)) {
                return;
            }
            ParadoksDb.async(() -> ParadoksDb.get().insertPending(account, dialogKey, mid));
            process(account, dialogKey, mid, message, 0);
        });
    }

    /** Must run on the storage queue. */
    private static TLRPC.Message loadMessage(int account, long dialogKey, int mid) {
        SQLiteCursor cursor = null;
        try {
            MessagesStorage storage = MessagesStorage.getInstance(account);
            if (dialogKey != 0) {
                cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE mid = ? AND uid = ?", mid, dialogKey);
            } else {
                cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE mid = ? AND is_channel = 0 LIMIT 1", mid);
            }
            if (!cursor.next()) {
                return null;
            }
            NativeByteBuffer data = cursor.byteBufferValue(0);
            if (data == null) {
                return null;
            }
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            data.reuse();
            return message;
        } catch (Throwable e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    private static void process(int account, long dialogKey, int mid, TLRPC.Message message, int attempt) {
        try {
            File source = FileLoader.getInstance(account).getPathToMessage(message);
            if (source != null && source.exists() && source.length() > 0) {
                final File src = source;
                final int kind = kindOf(message);
                ParadoksDb.async(() -> copyAndRecord(account, dialogKey, mid, src, kind));
                return;
            }
            if (message.media.document != null && message.media.document.size > MAX_SIZE) {
                ParadoksDb.async(() -> ParadoksDb.get().deletePending(account, dialogKey, mid));
                return;
            }
            if (attempt == 0) {
                startDownload(account, message);
            }
            if (attempt < RETRY_DELAYS_MS.length) {
                final int next = attempt + 1;
                Utilities.globalQueue.postRunnable(() -> process(account, dialogKey, mid, message, next), RETRY_DELAYS_MS[attempt]);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void startDownload(int account, TLRPC.Message message) {
        startDownload(account, message, FileLoader.PRIORITY_NORMAL);
    }

    private static void startDownload(int account, TLRPC.Message message, int priority) {
        try {
            if (message.media.document != null) {
                if (message.media.document.size <= MAX_SIZE) {
                    FileLoader.getInstance(account).loadFile(message.media.document, message, priority, 0);
                }
            } else if (message.media.photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.media.photo.sizes, AndroidUtilities.getPhotoSize());
                if (size != null) {
                    FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(size, message.media.photo), message, null, priority, 1);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static int kindOf(TLRPC.Message message) {
        if (message.media.photo != null) {
            return KIND_PHOTO;
        }
        if (message.media.document != null && MessageObject.isVideoDocument(message.media.document)) {
            return KIND_VIDEO;
        }
        return KIND_OTHER;
    }

    static File baseDir() {
        File base = ApplicationLoader.applicationContext.getExternalFilesDir(null);
        if (base == null) {
            base = ApplicationLoader.applicationContext.getFilesDir();
        }
        File dir = new File(base, "paradoks_deleted");
        dir.mkdirs();
        return dir;
    }

    private static void copyAndRecord(int account, long dialogKey, int mid, File source, int kind) {
        if (source.length() > MAX_SIZE) {
            ParadoksDb.get().deletePending(account, dialogKey, mid);
            return;
        }
        // Signed dialog key (p = private/basic group, c = channel/supergroup) so different
        // dialogs can never share a file name; the original name is sanitised.
        String dialogPart = dialogKey < 0 ? "c" + (-dialogKey) : "p" + dialogKey;
        String safeName = source.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(baseDir(), account + "_" + dialogPart + "_" + mid + "_" + safeName);
        try {
            if (!target.exists() || target.length() != source.length()) {
                InputStream in = new FileInputStream(source);
                OutputStream out = new FileOutputStream(target);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                } finally {
                    in.close();
                    out.close();
                }
            }
            String[] old = ParadoksDb.get().queryMedia(account, dialogKey, mid);
            ParadoksDb.get().insertMedia(account, dialogKey, mid, target.getAbsolutePath(), kind);
            ParadoksDb.get().deletePending(account, dialogKey, mid);
            if (old != null && !old[0].equals(target.getAbsolutePath())) {
                new File(old[0]).delete(); // replaced record: do not leave an orphan file
            }
        } catch (Throwable ignore) {
            target.delete();
        }
    }

    private ParadoksMedia() {
    }
}
