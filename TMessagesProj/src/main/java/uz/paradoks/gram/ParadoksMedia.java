package uz.paradoks.gram;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
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
    private static final long[] RETRY_DELAYS_MS = {3000, 10000, 30000, 90000, 300000};

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
                process(account, dialogKey, mid, message, 0);
            }
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
        try {
            if (message.media.document != null) {
                if (message.media.document.size <= MAX_SIZE) {
                    FileLoader.getInstance(account).loadFile(message.media.document, message, FileLoader.PRIORITY_NORMAL, 0);
                }
            } else if (message.media.photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.media.photo.sizes, AndroidUtilities.getPhotoSize());
                if (size != null) {
                    FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(size, message.media.photo), message, null, FileLoader.PRIORITY_NORMAL, 1);
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
            return;
        }
        File target = new File(baseDir(), account + "_" + Math.abs(dialogKey) + "_" + mid + "_" + source.getName());
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
            ParadoksDb.get().insertMedia(account, dialogKey, mid, target.getAbsolutePath(), kind);
        } catch (Throwable ignore) {
            target.delete();
        }
    }

    private ParadoksMedia() {
    }
}
