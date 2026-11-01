package uz.paradoks.gram;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Xabarlar statistikasi": how many messages (and of which kind) a person wrote in this chat,
 * and what share of the whole chat that is. Also the "N ta reaksiya" dialog.
 */
public final class ParadoksMessageStats {

    private ParadoksMessageStats() {
    }

    private static final class Stat {
        final String label;
        final TLRPC.MessagesFilter filter;
        final boolean ofUser;
        int count = -1; // -1 = olib bo'lmadi

        Stat(String label, TLRPC.MessagesFilter filter, boolean ofUser) {
            this.label = label;
            this.filter = filter;
            this.ofUser = ofUser;
        }
    }

    /** Counts the messages of [userId] in [dialogId] by type using messages.search (limit 1, only the total count is used). */
    public static void showSenderStats(BaseFragment fragment, int account, long dialogId, long userId, String userName) {
        if (fragment == null || fragment.getParentActivity() == null || userId == 0) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.InputPeer peer = mc.getInputPeer(dialogId);
        final TLRPC.InputPeer fromPeer = mc.getInputPeer(userId);
        if (peer == null || fromPeer == null) {
            return;
        }

        final ArrayList<Stat> stats = new ArrayList<>();
        stats.add(new Stat("Chatdagi jami xabarlar", new TLRPC.TL_inputMessagesFilterEmpty(), false));
        stats.add(new Stat("Xabarlari", new TLRPC.TL_inputMessagesFilterEmpty(), true));
        stats.add(new Stat("Rasmlar", new TLRPC.TL_inputMessagesFilterPhotos(), true));
        stats.add(new Stat("Videolar", new TLRPC.TL_inputMessagesFilterVideo(), true));
        stats.add(new Stat("Dumaloq videolar", new TLRPC.TL_inputMessagesFilterRoundVideo(), true));
        stats.add(new Stat("Fayllar", new TLRPC.TL_inputMessagesFilterDocument(), true));
        stats.add(new Stat("Ovozli xabarlar", new TLRPC.TL_inputMessagesFilterVoice(), true));
        stats.add(new Stat("Musiqa", new TLRPC.TL_inputMessagesFilterMusic(), true));
        stats.add(new Stat("GIF", new TLRPC.TL_inputMessagesFilterGif(), true));
        stats.add(new Stat("Linkli xabarlar", new TLRPC.TL_inputMessagesFilterUrl(), true));

        final AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        progress.show();

        final AtomicInteger left = new AtomicInteger(stats.size());
        for (Stat stat : stats) {
            TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = peer;
            req.q = "";
            req.limit = 1;
            req.filter = stat.filter;
            if (stat.ofUser) {
                req.from_id = fromPeer;
                req.flags |= 1;
            }
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    stat.count = Math.max(res.count, res.messages != null ? res.messages.size() : 0);
                }
                if (left.decrementAndGet() == 0) {
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (Throwable ignore) {
                        }
                        showStatsDialog(context, userName, stats);
                    });
                }
            });
        }
    }

    private static String num(int value) {
        return value < 0 ? "—" : String.format(Locale.US, "%,d", value).replace(',', ' ');
    }

    private static void showStatsDialog(Context context, String userName, ArrayList<Stat> stats) {
        int chatTotal = stats.get(0).count;
        int userTotal = stats.get(1).count;
        StringBuilder sb = new StringBuilder();
        for (Stat stat : stats) {
            sb.append(stat.label).append(": ").append(num(stat.count));
            if (stat == stats.get(1) && chatTotal > 0 && userTotal >= 0) {
                sb.append(String.format(Locale.US, "  (%.1f%%)", userTotal * 100f / chatTotal));
            } else if (stat.ofUser && stat != stats.get(1) && userTotal > 0 && stat.count >= 0) {
                sb.append(String.format(Locale.US, "  (%.1f%%)", stat.count * 100f / userTotal));
            }
            sb.append("\n");
        }
        final String text = sb.toString().trim();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(!TextUtils.isEmpty(userName) ? userName + " — xabarlar statistikasi" : "Xabarlar statistikasi");
        builder.setMessage(text);
        builder.setPositiveButton("Nusxa olish", (d, w) -> AndroidUtilities.addToClipboard(text));
        builder.setNegativeButton("Yopish", null);
        builder.show();
    }

    /** "N ta reaksiya": counts per reaction and (when Telegram gives them) who reacted. */
    public static void showReactions(BaseFragment fragment, int account, MessageObject mo) {
        if (fragment == null || fragment.getParentActivity() == null || mo == null || mo.messageOwner == null) {
            return;
        }
        final String text = ParadoksInspector.reactionDetails(account, mo.messageOwner);
        if (text == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle("Reaksiyalar");
        builder.setMessage(text);
        builder.setPositiveButton("Nusxa olish", (d, w) -> AndroidUtilities.addToClipboard(text));
        builder.setNegativeButton("Yopish", null);
        builder.show();
    }
}
