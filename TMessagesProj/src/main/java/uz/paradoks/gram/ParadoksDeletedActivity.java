package uz.paradoks.gram;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** "Deleted radar": every message that was deleted by someone else, newest first. */
public class ParadoksDeletedActivity extends BaseFragment {

    private static final class Item {
        long dialogId;
        long dialogKey;
        int messageId;
        long deletedAt;
        String chatName;
        String sender;
        String text;
        boolean hasMedia;
        String mediaPath;
        int mediaKind;
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;
    private boolean loading = true;

    @Override
    public boolean onFragmentCreate() {
        load();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("O'chirilganlar radari");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new Adapter(context));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            Item item = items.get(position);
            if (item.mediaPath != null && getParentActivity() != null) {
                org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
                builder.setTitle("Saqlangan media");
                builder.setItems(new CharSequence[]{"Chatda ochish", "Galereya/yuklamalarga saqlash"}, (d, which) -> {
                    if (which == 0) {
                        openChat(item);
                    } else {
                        java.io.File f = new java.io.File(item.mediaPath);
                        MediaController.saveFile(item.mediaPath, getParentActivity(), item.mediaKind, f.getName(), null);
                    }
                });
                builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null);
                showDialog(builder.create());
                return;
            }
            if (item.hasMedia && getParentActivity() != null) {
                org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
                builder.setTitle("Media saqlanmagan");
                builder.setItems(new CharSequence[]{"Chatda ochish", "Mediani qayta yuklab saqlash"}, (d, which) -> {
                    if (which == 0) {
                        openChat(item);
                    } else {
                        ParadoksMedia.retryNow(currentAccount, item.dialogKey, item.messageId);
                        android.widget.Toast.makeText(getParentActivity(),
                                "Yuklab olishga urinilyapti. Server fayli o'chirilgan bo'lsa, bu ishlamaydi.",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
                builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null);
                showDialog(builder.create());
                return;
            }
            openChat(item);
        });
        fragmentView = contentView;
        return fragmentView;
    }

    private void openChat(Item item) {
        Bundle args = new Bundle();
        if (item.dialogId > 0) {
            args.putLong("user_id", item.dialogId);
        } else {
            args.putLong("chat_id", -item.dialogId);
        }
        args.putInt("message_id", item.messageId);
        presentFragment(new ChatActivity(args));
    }

    private void load() {
        final int account = currentAccount;
        ParadoksDb.async(() -> {
            ArrayList<long[]> rows = new ArrayList<>();
            Cursor cursor = null;
            try {
                cursor = ParadoksDb.get().getReadableDatabase().rawQuery(
                        "SELECT dlg, mid, ts FROM deleted WHERE acc = ? ORDER BY ts DESC LIMIT 200",
                        new String[]{String.valueOf(account)});
                while (cursor.moveToNext()) {
                    rows.add(new long[]{cursor.getLong(0), cursor.getLong(1), cursor.getLong(2)});
                }
            } catch (Throwable ignore) {
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
                ArrayList<Item> result = new ArrayList<>();
                for (long[] row : rows) {
                    Item item = lookup(account, row[0], (int) row[1], row[2]);
                    if (item != null) {
                        String[] media = ParadoksDb.get().queryMedia(account, row[0], (int) row[1]);
                        if (media != null && new java.io.File(media[0]).exists()) {
                            item.mediaPath = media[0];
                            item.mediaKind = Integer.parseInt(media[1]);
                        } else if (item.hasMedia) {
                            item.text += "\n(media yuklanmagan, bosib qayta urinib ko'ring)";
                        }
                        result.add(item);
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    items.clear();
                    items.addAll(result);
                    loading = false;
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            });
        });
    }

    /** Runs on the storage queue. Reads the kept message straight from Telegram's own database. */
    private static Item lookup(int account, long dialogKey, int mid, long deletedAt) {
        SQLiteCursor cursor = null;
        try {
            MessagesStorage storage = MessagesStorage.getInstance(account);
            if (dialogKey != 0) {
                cursor = storage.getDatabase().queryFinalized("SELECT uid, data FROM messages_v2 WHERE mid = ? AND uid = ?", mid, dialogKey);
            } else {
                cursor = storage.getDatabase().queryFinalized("SELECT uid, data FROM messages_v2 WHERE mid = ? AND is_channel = 0 LIMIT 1", mid);
            }
            if (!cursor.next()) {
                return null;
            }
            long uid = cursor.longValue(0);
            NativeByteBuffer data = cursor.byteBufferValue(1);
            if (data == null) {
                return null;
            }
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            data.reuse();
            if (message == null) {
                return null;
            }
            Item item = new Item();
            item.hasMedia = message.media != null && (message.media.photo != null || message.media.document != null);
            item.dialogId = uid;
            item.dialogKey = dialogKey;
            item.messageId = mid;
            item.deletedAt = deletedAt;
            item.text = message.message != null && message.message.length() > 0 ? message.message
                    : (message.media != null ? "[media]" : "[xabar]");
            MessagesController controller = MessagesController.getInstance(account);
            item.chatName = nameOf(controller, uid);
            long fromId = MessageObject.getFromChatId(message);
            item.sender = fromId != 0 ? nameOf(controller, fromId) : item.chatName;
            return item;
        } catch (Throwable e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    private static String nameOf(MessagesController controller, long id) {
        if (id > 0) {
            TLRPC.User user = controller.getUser(id);
            return user != null ? UserObject.getUserName(user) : ("ID " + id);
        }
        TLRPC.Chat chat = controller.getChat(-id);
        return chat != null && chat.title != null ? chat.title : ("ID " + id);
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return Math.max(items.size(), 1);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
            if (items.isEmpty()) {
                cell.setTextAndValue(loading ? "Yuklanmoqda..." : "O'chirilgan xabar yo'q", "Boshqalar o'chirgan xabarlar shu yerda chiqadi", false);
                return;
            }
            Item item = items.get(position);
            String when = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(item.deletedAt * 1000L));
            String title = item.sender.equals(item.chatName) ? item.chatName : (item.sender + "  ›  " + item.chatName);
            String text = item.text.length() > 140 ? item.text.substring(0, 140) + "…" : item.text;
            cell.setTextAndValue(title, (item.mediaPath != null ? "📎 " : "") + text + "\n🗑 " + when, position != items.size() - 1);
        }
    }
}
