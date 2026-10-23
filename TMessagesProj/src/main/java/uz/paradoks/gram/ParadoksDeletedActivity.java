package uz.paradoks.gram;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** "Deleted radar": every message that was deleted by someone else, newest first. */
public class ParadoksDeletedActivity extends BaseFragment {

    static final class Item {
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
        boolean edited;
        long copyDlg;
        int copyMid;
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<Item> allDeleted = new ArrayList<>();
    private final ArrayList<Item> allEdited = new ArrayList<>();
    private int mode; // 0 = deleted, 1 = edited

    public ParadoksDeletedActivity() {
    }

    /** @param startMode 0 = deleted messages, 1 = edited messages */
    public ParadoksDeletedActivity(int startMode) {
        this.mode = startMode;
    }
    private int typeFilter; // 0 all, 1 media, 2 text
    private int periodFilter; // 0 all, 1 today, 2 = 7 days, 3 = 30 days
    private String query = "";
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
                } else if (id == 1) {
                    showFilterDialog();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
            }

            @Override
            public boolean canCollapseSearch() {
                return true;
            }

            @Override
            public void onSearchCollapse() {
                query = "";
                applyFilter();
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        searchItem.setSearchFieldHint("Matn, chat yoki odam bo'yicha qidirish");
        menu.addItem(1, "Filtr");

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new Adapter(context));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size() || getParentActivity() == null) {
                return;
            }
            onItemSelected(items.get(position));
        });
        fragmentView = contentView;
        return fragmentView;
    }

    private void onItemSelected(Item item) {
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        labels.add("Chatda ochish");
        actions.add(() -> openChat(item));
        if (item.edited) {
            labels.add("Tahrirlar tarixi");
            actions.add(() -> ParadoksEdits.showHistoryById(this, currentAccount, item.dialogId, item.messageId));
        } else if (item.mediaPath != null) {
            labels.add("Galereya/yuklamalarga saqlash");
            actions.add(() -> {
                java.io.File f = new java.io.File(item.mediaPath);
                MediaController.saveFile(item.mediaPath, getParentActivity(), item.mediaKind, f.getName(), null);
            });
        } else if (item.hasMedia) {
            labels.add("Mediani qayta yuklab saqlash");
            actions.add(() -> {
                ParadoksMedia.retryNow(currentAccount, item.dialogKey, item.messageId);
                android.widget.Toast.makeText(getParentActivity(),
                        "Yuklab olishga urinilyapti. Server fayli o'chirilgan bo'lsa, bu ishlamaydi.",
                        android.widget.Toast.LENGTH_LONG).show();
            });
        }
        if (item.copyMid != 0) {
            labels.add("Bazadagi nusxani ochish");
            actions.add(() -> openDatabaseCopy(item));
        }
        if (labels.size() == 1) {
            actions.get(0).run();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(item.edited ? "Tahrirlangan xabar" : (item.mediaPath != null ? "Saqlangan media" : "O'chirilgan xabar"));
        builder.setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run());
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    /** Opens the copy that was forwarded to the media database chat. */
    private void openDatabaseCopy(Item item) {
        Bundle args = new Bundle();
        if (item.copyDlg > 0) {
            args.putLong("user_id", item.copyDlg);
        } else {
            args.putLong("chat_id", -item.copyDlg);
        }
        args.putInt("message_id", item.copyMid);
        presentFragment(new ChatActivity(args));
    }

    private void showFilterDialog() {
        if (getParentActivity() == null) {
            return;
        }
        String[] types = {"Hammasi", "Faqat media", "Faqat matn"};
        String[] periods = {"Hammasi", "Bugun", "Oxirgi 7 kun", "Oxirgi 30 kun"};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Filtr");
        builder.setItems(new CharSequence[]{
                "Ko'rsatish: " + (mode == 0 ? "o'chirilganlar" : "tahrirlanganlar") + " (almashtirish)",
                "Tur: " + types[typeFilter] + " (o'zgartirish)",
                "Davr: " + periods[periodFilter] + " (o'zgartirish)",
                "Filtrni tozalash"}, (d, which) -> {
            if (which == 0) {
                mode = 1 - mode;
            } else if (which == 1) {
                typeFilter = (typeFilter + 1) % 3;
            } else if (which == 2) {
                periodFilter = (periodFilter + 1) % 4;
            } else {
                typeFilter = 0;
                periodFilter = 0;
            }
            applyFilter();
            if (which == 1 || which == 2) {
                showFilterDialog();
            }
        });
        builder.setNegativeButton("Yopish", null);
        showDialog(builder.create());
    }

    private void applyFilter() {
        long cutoff = 0;
        if (periodFilter == 1) {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            cutoff = calendar.getTimeInMillis() / 1000L;
        } else if (periodFilter == 2) {
            cutoff = System.currentTimeMillis() / 1000L - 7L * 24 * 3600;
        } else if (periodFilter == 3) {
            cutoff = System.currentTimeMillis() / 1000L - 30L * 24 * 3600;
        }
        items.clear();
        for (Item item : (mode == 0 ? allDeleted : allEdited)) {
            if (mode == 0 && typeFilter == 1 && !item.hasMedia) {
                continue;
            }
            if (mode == 0 && typeFilter == 2 && item.hasMedia) {
                continue;
            }
            if (item.deletedAt < cutoff) {
                continue;
            }
            if (!query.isEmpty()) {
                String haystack = ((item.text == null ? "" : item.text) + " " + item.chatName + " " + item.sender).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) {
                    continue;
                }
            }
            items.add(item);
        }
        if (actionBar != null) {
            actionBar.setTitle(mode == 0 ? "O'chirilganlar radari" : "Tahrirlar radari");
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
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
                        long[] copy = ParadoksDb.get().queryForward(account, item.dialogId, (int) row[1]);
                        if (copy != null) {
                            item.copyDlg = copy[0];
                            item.copyMid = (int) copy[1];
                            if (item.mediaPath == null && item.hasMedia) {
                                item.text += "\n(bazada nusxasi bor)";
                            }
                        }
                        result.add(item);
                    }
                }
                final ArrayList<Item> edited = loadEdited(account);
                AndroidUtilities.runOnUIThread(() -> {
                    allDeleted.clear();
                    allDeleted.addAll(result);
                    allEdited.clear();
                    allEdited.addAll(edited);
                    loading = false;
                    applyFilter();
                });
            });
        });
    }

    /** Edited messages from our own history table. */
    private static ArrayList<Item> loadEdited(int account) {
        ArrayList<Item> result = new ArrayList<>();
        MessagesController controller = MessagesController.getInstance(account);
        for (long[] row : ParadoksDb.get().queryEditGroups(account)) {
            Item item = new Item();
            item.edited = true;
            item.dialogId = row[0];
            item.dialogKey = row[0];
            item.messageId = (int) row[1];
            item.deletedAt = row[2];
            item.chatName = nameOf(controller, row[0]);
            item.sender = item.chatName;
            String last = ParadoksDb.get().editText(row[4]);
            item.text = (last == null || last.isEmpty() ? "(matnsiz)" : last) + "\n(" + row[3] + " ta eski variant)";
            result.add(item);
        }
        return result;
    }

    /** Runs on the storage queue. Reads the kept message straight from Telegram's own database. */
    static Item lookup(int account, long dialogKey, int mid, long deletedAt) {
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
                boolean filtered = !query.isEmpty() || typeFilter != 0 || periodFilter != 0;
                cell.setTextAndValue(loading ? "Yuklanmoqda..." : (filtered ? "Hech narsa topilmadi" : (mode == 0 ? "O'chirilgan xabar yo'q" : "Tahrirlangan xabar yo'q")),
                        filtered ? "Qidiruv yoki filtrni o'zgartirib ko'ring" : (mode == 0 ? "Boshqalar o'chirgan xabarlar shu yerda chiqadi" : "Boshqalar tahrirlagan xabarlarning eski variantlari shu yerda chiqadi"), false);
                return;
            }
            Item item = items.get(position);
            String when = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(item.deletedAt * 1000L));
            String title = item.sender.equals(item.chatName) ? item.chatName : (item.sender + "  ›  " + item.chatName);
            String text = item.text.length() > 140 ? item.text.substring(0, 140) + "…" : item.text;
            cell.setTextAndValue(title, (item.mediaPath != null ? "📎 " : "") + text + "\n" + (item.edited ? "✏ " : "🗑 ") + when, position != items.size() - 1);
        }
    }
}
