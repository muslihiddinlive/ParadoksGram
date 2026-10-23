package uz.paradoks.gram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.ChatMessageCell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows the media of a message that somebody deleted in the chat it was sent to, using our backups:
 * 1) the file we saved to the app folder is put back where the chat looks for it, or
 * 2) the copy forwarded to the media database chat is downloaded (a forward keeps the same file id,
 *    so the file lands exactly where the original chat expects it).
 */
public final class ParadoksMediaRestore {

    private static final Set<String> attempted = new HashSet<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final int WAIT_SECONDS = 120;

    private ParadoksMediaRestore() {
    }

    /** Called from ChatMessageCell when a message is bound (UI thread); only cheap checks happen here. */
    public static void onBind(ChatMessageCell cell, int account, MessageObject object) {
        try {
            if (object == null || object.messageOwner == null || object.messageOwner.media == null || !ParadoksConfig.restoreBackupMedia()) {
                return;
            }
            TLRPC.MessageMedia media = object.messageOwner.media;
            if (media.photo == null && media.document == null) {
                return;
            }
            if (object.mediaExists || object.attachPathExists || object.isSecretMedia()) {
                return;
            }
            if (!ParadoksDeleted.isDeleted(account, object)) {
                return;
            }
            final String key = account + "_" + object.getDialogId() + "_" + object.getId();
            synchronized (attempted) {
                if (!attempted.add(key)) {
                    return;
                }
            }
            final WeakReference<ChatMessageCell> ref = new WeakReference<>(cell);
            executor.execute(() -> restore(account, object, ref));
        } catch (Throwable ignore) {
        }
    }

    private static void restore(int account, MessageObject object, WeakReference<ChatMessageCell> cellRef) {
        try {
            final long dialogId = object.getDialogId();
            final int mid = object.getId();
            final TLRPC.Peer peer = object.messageOwner.peer_id;
            final long dialogKey = (peer != null && peer.channel_id != 0) ? -peer.channel_id : 0;
            final File target = FileLoader.getInstance(account).getPathToMessage(object.messageOwner);
            if (target == null || target.exists()) {
                refresh(object, cellRef);
                return;
            }

            // 1) our own saved file
            String[] saved = ParadoksDb.get().queryMedia(account, dialogKey, mid);
            if (saved != null && saved[0] != null && new File(saved[0]).exists()) {
                copy(new File(saved[0]), target);
                if (target.exists()) {
                    refresh(object, cellRef);
                    return;
                }
            }

            // 2) the copy forwarded to the media database chat
            long[] forward = ParadoksDb.get().queryForward(account, dialogId, mid);
            if (forward == null) {
                return;
            }
            TLRPC.Message copyMessage = MessagesStorage.getInstance(account).getMessage(forward[0], forward[1]);
            if (copyMessage == null || copyMessage.media == null || (copyMessage.media.photo == null && copyMessage.media.document == null)) {
                return;
            }
            ParadoksMedia.downloadFor(account, copyMessage); // its own file reference is valid
            for (int i = 0; i < WAIT_SECONDS; i++) {
                Thread.sleep(1000);
                if (target.exists()) {
                    refresh(object, cellRef);
                    return;
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void copy(File from, File to) throws Exception {
        File parent = to.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File part = new File(to.getAbsolutePath() + ".restoring");
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        if (!part.renameTo(to)) {
            part.delete();
        }
    }

    private static void refresh(MessageObject object, WeakReference<ChatMessageCell> cellRef) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                object.checkMediaExistance();
                ChatMessageCell cell = cellRef.get();
                if (cell != null && cell.getMessageObject() == object) {
                    cell.setMessageObject(object, cell.getCurrentMessagesGroup(), cell.pinnedBottom, cell.pinnedTop, cell.firstInChat);
                }
            } catch (Throwable ignore) {
            }
        });
    }
}
