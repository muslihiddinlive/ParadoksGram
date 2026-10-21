package uz.paradoks.gram;

/*
 * Edit history screen.
 *
 * The idea of drawing every saved revision as a real chat bubble on a plain background follows the
 * edit-history screen of AyuGram (AyuMessageHistory, Copyright @Radolyn, 2023, GPL v2). This is an
 * independent implementation for ParadoksGram: it reads ParadoksGram's own "edits" table, adds
 * date labels between the bubbles and a "copy all" action.
 */

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class ParadoksEditsActivity extends BaseFragment {

    private static final int T_LABEL = 0;
    private static final int T_BUBBLE = 1;
    private static final int T_INFO = 2;

    private static final class Entry {
        int type;
        String text;
        long ts;
        String label;
        TLRPC.Message stored; // full old message (text, formatting, media), null for rows saved by older versions
        String mediaPath;
        MessageObject object;
    }

    private final int account;
    private final long dialogId;
    private final int messageId;
    private final TLRPC.Message base; // the message as it is now (null when opened from the radar and not loaded yet)
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
    private TLRPC.Message current;
    private boolean loaded;
    private RecyclerListView listView;
    private Adapter adapter;

    public ParadoksEditsActivity(int account, long dialogId, int messageId, TLRPC.Message base) {
        this.account = account;
        this.dialogId = dialogId;
        this.messageId = messageId;
        this.base = base;
        this.current = base;
    }

    @Override
    public boolean onFragmentCreate() {
        ParadoksDb.async(() -> {
            final ArrayList<Object[]> rows = new ArrayList<>();
            android.database.Cursor cursor = null;
            try {
                cursor = ParadoksDb.get().getReadableDatabase().rawQuery(
                        "SELECT ts, txt, blob, media_path FROM edits WHERE acc = ? AND dlg = ? AND mid = ? ORDER BY id ASC",
                        new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)});
                while (cursor.moveToNext()) {
                    rows.add(new Object[]{cursor.getLong(0), cursor.getString(1), cursor.getBlob(2), cursor.getString(3)});
                }
            } catch (Throwable ignore) {
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            TLRPC.Message now = base;
            if (now == null) {
                try {
                    now = MessagesStorage.getInstance(account).getMessage(dialogId, messageId);
                } catch (Throwable ignore) {
                }
            }
            final TLRPC.Message finalNow = now;
            AndroidUtilities.runOnUIThread(() -> onLoaded(rows, finalNow));
        });
        return super.onFragmentCreate();
    }

    private void onLoaded(ArrayList<Object[]> rows, TLRPC.Message now) {
        current = now;
        entries.clear();
        for (int i = 0; i < rows.size(); i++) {
            Entry label = new Entry();
            label.type = T_LABEL;
            label.label = "Variant " + (i + 1) + "  •  " + format.format(new Date((Long) rows.get(i)[0] * 1000L));
            entries.add(label);
            Entry bubble = new Entry();
            bubble.type = T_BUBBLE;
            bubble.ts = (Long) rows.get(i)[0];
            bubble.text = textOrPlaceholder((String) rows.get(i)[1]);
            bubble.stored = ParadoksEdits.deserialize((byte[]) rows.get(i)[2]);
            bubble.mediaPath = (String) rows.get(i)[3];
            entries.add(bubble);
        }
        if (!rows.isEmpty() && now != null) {
            long when = now.edit_date != 0 ? now.edit_date : now.date;
            Entry label = new Entry();
            label.type = T_LABEL;
            label.label = "Hozirgi variant  •  " + format.format(new Date(when * 1000L));
            entries.add(label);
            Entry bubble = new Entry();
            bubble.type = T_BUBBLE;
            bubble.ts = when;
            bubble.text = textOrPlaceholder(now.message);
            bubble.stored = ParadoksEdits.copyOf(now);
            entries.add(bubble);
        }
        if (rows.isEmpty()) {
            Entry info = new Entry();
            info.type = T_INFO;
            info.text = "Bu xabarning eski variantlari saqlanmagan.\n\n"
                    + "Odatda sababi: xabar bu ilova o'rnatilishidan oldin yoki \"Tahrirlar tarixini saqlash\" yoqilishidan oldin tahrirlangan. "
                    + "Endi yangi tahrir qilinsa, eski matn saqlanadi.\n\n"
                    + "Tahrirlarni saqlash: " + (ParadoksConfig.trackEdits() ? "yoniq" : "O'CHIQ (sozlamalarda yoqing)");
            entries.add(info);
        }
        loaded = true;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            if (listView != null && !entries.isEmpty()) {
                listView.scrollToPosition(entries.size() - 1); // newest version at the bottom, like a chat
            }
        }
    }

    private static String textOrPlaceholder(String text) {
        return text == null || text.isEmpty() ? "(matnsiz)" : text;
    }

    @Override
    public View createView(Context context) {
        Theme.createChatResources(context, false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(ParadoksDeletedDbActivity.nameOf(MessagesController.getInstance(account), dialogId));
        actionBar.setSubtitle("Tahrirlar tarixi  •  ID " + messageId);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    copyAll();
                }
            }
        });
        actionBar.createMenu().addItem(1, "Hammasini nusxalash");

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        listView = new RecyclerListView(context);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(true);
        listView.setPadding(0, dp(8), 0, dp(8));
        listView.setClipToPadding(false);
        listView.setAdapter(adapter = new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < entries.size() && entries.get(position).type == T_BUBBLE) {
                AndroidUtilities.addToClipboard(entries.get(position).text);
                Toast.makeText(context, "Nusxa olindi", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (loaded) {
            adapter.notifyDataSetChanged();
            if (!entries.isEmpty()) {
                listView.scrollToPosition(entries.size() - 1);
            }
        }
        return fragmentView;
    }

    private void copyAll() {
        StringBuilder sb = new StringBuilder();
        String pending = null;
        for (Entry entry : entries) {
            if (entry.type == T_LABEL) {
                pending = entry.label;
            } else if (entry.type == T_BUBBLE) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                if (pending != null) {
                    sb.append(pending).append('\n');
                }
                sb.append(entry.text);
            }
        }
        if (sb.length() > 0) {
            AndroidUtilities.addToClipboard(sb.toString());
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), "Hamma variantlar nusxalandi", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Builds a message object that looks like the original message but carries one saved text. */
    private MessageObject bubbleFor(Entry entry, int index) {
        if (entry.object != null) {
            return entry.object;
        }
        if (entry.stored != null) {
            TLRPC.Message full = entry.stored;
            full.id = 100000 + index;
            full.date = (int) entry.ts;
            full.dialog_id = dialogId;
            full.edit_hide = true; // no "edited" label inside the revision bubbles
            full.reply_to = null;
            full.replyMessage = null;
            full.reply_markup = null;
            if (full.peer_id == null && base != null) {
                full.peer_id = base.peer_id;
            }
            restoreMedia(full, entry.mediaPath);
            entry.object = new MessageObject(account, full, true, true);
            return entry.object;
        }
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 100000 + index;
        message.date = (int) entry.ts;
        message.message = entry.text;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.dialog_id = dialogId;
        message.edit_hide = true; // no "edited" label inside the revision bubbles
        if (base != null) {
            message.out = base.out;
            message.from_id = base.from_id;
            message.peer_id = base.peer_id;
        } else {
            message.out = false;
            message.peer_id = peerFor(dialogId);
            message.from_id = dialogId > 0 ? peerFor(dialogId) : null;
        }
        entry.object = new MessageObject(account, message, true, false);
        return entry.object;
    }

    /** If the old photo/file is gone from the cache, put our saved copy back so the bubble can show it. */
    private void restoreMedia(TLRPC.Message message, String savedPath) {
        if (savedPath == null || message.media == null) {
            return;
        }
        try {
            File saved = new File(savedPath);
            File target = FileLoader.getInstance(account).getPathToMessage(message);
            if (saved.exists() && target != null && !target.exists()) {
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                java.io.FileInputStream in = new java.io.FileInputStream(saved);
                java.io.FileOutputStream out = new java.io.FileOutputStream(target);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                in.close();
                out.close();
            }
        } catch (Throwable ignore) {
        }
    }

    private TLRPC.Peer peerFor(long id) {
        if (id > 0) {
            TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
            peer.user_id = id;
            return peer;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-id);
        if (chat != null && ChatObject.isChannel(chat)) {
            TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
            peer.channel_id = -id;
            return peer;
        }
        TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
        peer.chat_id = -id;
        return peer;
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == T_BUBBLE;
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        @Override
        public int getItemViewType(int position) {
            return entries.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == T_BUBBLE) {
                ChatMessageCell cell = new ChatMessageCell(context, account);
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                });
                view = cell;
            } else if (viewType == T_INFO) {
                view = new TextInfoPrivacyCell(context);
            } else {
                FrameLayout holder = new FrameLayout(context);
                TextView pill = new TextView(context);
                pill.setTextSize(13);
                pill.setTypeface(Typeface.DEFAULT_BOLD);
                pill.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
                pill.setPadding(dp(12), dp(5), dp(12), dp(5));
                pill.setBackground(Theme.createRoundRectDrawable(dp(15), Theme.getColor(Theme.key_chat_serviceBackground)));
                pill.setTag("pill");
                holder.addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 6));
                view = holder;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Entry entry = entries.get(position);
            if (entry.type == T_BUBBLE) {
                ((ChatMessageCell) holder.itemView).setMessageObject(bubbleFor(entry, position), null, false, false, false);
            } else if (entry.type == T_INFO) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(entry.text);
            } else {
                ((TextView) holder.itemView.findViewWithTag("pill")).setText(entry.label);
            }
        }
    }
}
