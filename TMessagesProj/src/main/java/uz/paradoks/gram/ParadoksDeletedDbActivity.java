package uz.paradoks.gram;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * "Deleted media database": pick the chat that receives a forwarded copy of every incoming
 * photo/video/file. Own private channels and groups first; bots and private chats only after the
 * user explicitly accepts the risks.
 */
public class ParadoksDeletedDbActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int T_INFO = 0;
    private static final int T_HEADER = 1;
    private static final int T_CHAT = 2;
    private static final int T_BUTTON = 3;

    private static final int ACTION_EXTRA = 1;
    private static final int ACTION_CLEAR = 2;
    private static final int ACTION_NEW = 3;

    private static final String INTRO =
            "Bu nima? Kimdir sizga rasm, video yoki fayl yuborsa, uning nusxasi avtomatik shu yerda tanlagan "
                    + "kanalingiz yoki guruhingizga forward qilinadi. Yuboruvchi keyin uni o'chirib yuborsa ham, "
                    + "nusxa sizning bazangizda qoladi.\n\n"
                    + "Oddiy misol: do'stingiz sizga rasm berdi. Siz uning nusxasini o'z qutingizga solib qo'ydingiz. "
                    + "Do'stingiz rasmini qaytib olib ketsa ham, sizning qutingizda nusxasi bor.\n\n"
                    + "Faqat boshqalar yuborgan media forward qilinadi (o'zingiznikilar emas). Maxfiy chatlar, kanallar, "
                    + "bir marta ko'riladigan media, stikerlar va \"saqlash taqiqlangan\" chatlar o'tkazib yuboriladi. "
                    + "Bazaning o'zidan forward qilinmaydi (aylanib ketmasligi uchun). Telegram cheklovlariga tushmaslik uchun "
                    + "media sekin, birma-bir yuboriladi.\n\n"
                    + "Faqat siz admin bo'lgan shaxsiy kanal va guruhlar ko'rsatiladi. Bazani faqat o'zingiz kiradigan "
                    + "joy qiling: u yerga kira oladigan hamma shu mediani ko'radi.";

    private static final class Row {
        int type;
        int action;
        long dialogId;
        boolean personal; // bot / private chat
        Object avatar; // TLRPC.Chat or TLRPC.User, used for the profile picture
        String title;
        String subtitle;
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private String query = "";
    private RecyclerListView listView;
    private Adapter adapter;

    @Override
    public boolean onFragmentCreate() {
        build();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsNeedReload);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            refresh();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh(); // a channel/group created from here shows up when we come back
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("O'chirilgan media bazasi");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
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
                refresh();
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
        });
        searchItem.setSearchFieldHint("Kanal, guruh, bot yoki odam nomi");

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new Adapter(context));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row row = rows.get(position);
            if (row.type == T_BUTTON) {
                if (row.action == ACTION_EXTRA) {
                    toggleExtra();
                } else if (row.action == ACTION_NEW) {
                    showCreateDialog();
                } else if (row.action == ACTION_CLEAR) {
                    ParadoksConfig.setDbTarget(currentAccount, 0);
                    refresh();
                }
            } else if (row.type == T_CHAT) {
                choose(row);
            }
        });
        fragmentView = contentView;
        return fragmentView;
    }

    private void refresh() {
        build();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void build() {
        rows.clear();
        long target = ParadoksConfig.dbTarget(currentAccount);
        boolean extra = ParadoksConfig.dbExtraConsent();
        MessagesController controller = MessagesController.getInstance(currentAccount);

        final boolean searching = !query.isEmpty();
        if (!searching) {
            rows.add(info(INTRO));
        }
        Row create = new Row();
        create.type = T_BUTTON;
        create.action = ACTION_NEW;
        create.title = "+ Yangi kanal yoki guruh ochish";
        create.subtitle = "Telegram'ning o'z oynasi ochiladi. Tayyor bo'lgach shu yerga qaytib, uni tanlang";
        if (!searching) {
            rows.add(create);
        }
        if (target != 0 && !searching) {
            rows.add(header("Hozirgi baza"));
            Row current = new Row();
            current.type = T_CHAT;
            current.dialogId = target;
            current.title = nameOf(controller, target);
            current.avatar = target > 0 ? controller.getUser(target) : controller.getChat(-target);
            current.subtitle = "✔ Baza (bosilsa o'chiriladi)";
            rows.add(current);
        }

        ArrayList<Row> channels = new ArrayList<>();
        ArrayList<Row> groups = new ArrayList<>();
        ArrayList<Row> bots = new ArrayList<>();
        ArrayList<Row> people = new ArrayList<>();

        for (TLRPC.Dialog dialog : new ArrayList<>(controller.getAllDialogs())) {
            long id = dialog.id;
            if (DialogObject.isEncryptedDialog(id) || id == target) {
                continue;
            }
            if (DialogObject.isChatDialog(id)) {
                TLRPC.Chat chat = controller.getChat(-id);
                if (chat == null || chat.left || chat.kicked || chat.deactivated || ChatObject.isForum(chat)) {
                    continue;
                }
                if (!(chat.creator || ChatObject.hasAdminRights(chat))) {
                    continue;
                }
                if (!TextUtils.isEmpty(ChatObject.getPublicUsername(chat))) {
                    continue; // only private ones
                }
                boolean broadcast = ChatObject.isChannelAndNotMegaGroup(chat);
                if (broadcast ? !ChatObject.canPost(chat) : !ChatObject.canSendMessages(chat)) {
                    continue;
                }
                Row row = chatRow(id, chat.title, broadcast ? "Shaxsiy kanal" : (ChatObject.isChannel(chat) ? "Superguruh" : "Guruh"), false, chat);
                (broadcast ? channels : groups).add(row);
            } else if (extra && DialogObject.isUserDialog(id)) {
                TLRPC.User user = controller.getUser(id);
                if (user == null || user.deleted) {
                    continue;
                }
                if (UserObject.isUserSelf(user)) {
                    people.add(0, chatRow(id, "Saqlangan xabarlar", "Faqat o'zingiz ko'rasiz", true, user));
                } else if (user.bot) {
                    bots.add(chatRow(id, UserObject.getUserName(user), "Bot", true, user));
                } else {
                    people.add(chatRow(id, UserObject.getUserName(user), "Shaxsiy chat: bu odam mediani ko'radi!", true, user));
                }
            }
        }

        int before = rows.size();
        addSection("Shaxsiy kanallaringiz (siz adminsiz)", channels, "Mos kanal topilmadi. Telegram'da shaxsiy kanal yarating (siz admin bo'lasiz) va shu yerga qayting.");
        addSection("Guruhlar va superguruhlar (siz adminsiz)", groups, "Mos guruh topilmadi.");

        Row extraButton = new Row();
        extraButton.type = T_BUTTON;
        extraButton.action = ACTION_EXTRA;
        extraButton.title = extra ? "Botlar va shaxsiy chatlarni yashirish" : "Qo'shimcha: botlar va shaxsiy chatlar";
        extraButton.subtitle = extra ? "Ro'yxatdan olib tashlanadi" : "Ehtiyot bo'ling, avval tushuntirish chiqadi";
        if (!searching) {
            rows.add(extraButton);
        }

        if (extra) {
            addSection("Botlar", bots, "Bot topilmadi.");
            addSection("Shaxsiy chatlar", people, "Chat topilmadi.");
        }
        if (searching && rows.size() == before) {
            rows.add(info("Hech narsa topilmadi. Qidiruv faqat siz admin bo'lgan shaxsiy kanal/guruhlar" + (extra ? ", botlar va shaxsiy chatlar" : "") + " ichida ishlaydi."));
        }
        if (target != 0 && !searching) {
            Row clear = new Row();
            clear.type = T_BUTTON;
            clear.action = ACTION_CLEAR;
            clear.title = "Bazani o'chirish";
            clear.subtitle = "Media forward qilish to'xtaydi";
            rows.add(clear);
        }
    }

    /** Adds a header and its rows; while searching only matching rows (and only non-empty sections). */
    private void addSection(String headerText, ArrayList<Row> list, String emptyText) {
        ArrayList<Row> shown = new ArrayList<>();
        for (Row row : list) {
            if (query.isEmpty() || (row.title != null && row.title.toLowerCase(Locale.ROOT).contains(query))) {
                shown.add(row);
            }
        }
        if (!query.isEmpty() && shown.isEmpty()) {
            return;
        }
        rows.add(header(headerText));
        if (shown.isEmpty()) {
            rows.add(info(emptyText));
        } else {
            rows.addAll(shown);
        }
    }

    private static Row info(String text) {
        Row row = new Row();
        row.type = T_INFO;
        row.title = text;
        return row;
    }

    private static Row header(String text) {
        Row row = new Row();
        row.type = T_HEADER;
        row.title = text;
        return row;
    }

    private static Row chatRow(long id, String title, String subtitle, boolean personal, Object avatar) {
        Row row = new Row();
        row.type = T_CHAT;
        row.dialogId = id;
        row.personal = personal;
        row.avatar = avatar;
        row.title = title != null ? title : ("ID " + id);
        row.subtitle = subtitle;
        return row;
    }

    private void choose(Row row) {
        long target = ParadoksConfig.dbTarget(currentAccount);
        if (row.dialogId == target) {
            ParadoksConfig.setDbTarget(currentAccount, 0);
            refresh();
            return;
        }
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Baza: " + row.title);
        String message = "Endi boshqalar sizga yuborgan rasm, video va fayllar shu yerga forward qilinadi. "
                + "Shu joyga kira oladigan hamma ularni ko'radi.";
        if (row.personal) {
            message += "\n\nDiqqat: bu bot yoki odam. Suhbatdoshlaringizning mediasi shu bot/odamga yuboriladi. "
                    + "Ularning roziligisiz tarqatish ularning maxfiyligini buzadi.";
        }
        builder.setMessage(message);
        builder.setPositiveButton("Baza qilish", (d, w) -> {
            ParadoksConfig.setDbTarget(currentAccount, row.dialogId);
            refresh();
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    private void showCreateDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Nimani ochamiz?");
        builder.setItems(new CharSequence[]{"Yangi kanal", "Yangi guruh"}, (d, which) -> {
            if (which == 0) {
                Bundle args = new Bundle();
                args.putInt("step", 0);
                presentFragment(new ChannelCreateActivity(args));
            } else {
                presentFragment(new GroupCreateActivity(new Bundle()));
            }
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    private void toggleExtra() {
        if (ParadoksConfig.dbExtraConsent()) {
            ParadoksConfig.setDbExtraConsent(false);
            refresh();
            return;
        }
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Avval o'qing, keyin rozi bo'ling");
        builder.setMessage("Tasavvur qiling: do'stingiz sizga rasm berdi. Siz o'sha rasmning nusxasini sevimli qutingizga solib qo'yasiz. "
                + "Do'stingiz rasmini qaytib olib ketsa ham, sizning qutingizda nusxasi qoladi. Bu yaxshi.\n\n"
                + "Agar quti o'z kanalingiz bo'lsa, rasmni faqat o'zingiz ko'rasiz. Xavfsiz.\n\n"
                + "Lekin qutingiz bot yoki boshqa odam bo'lsa, siz do'stingizning rasmini o'sha botga yoki odamga ham berib yuborgan bo'lasiz. "
                + "Do'stingiz buni bilmaydi va xohlamagan bo'lishi mumkin. Bot rasmni saqlab qolishi, boshqa odam esa uni boshqalarga ko'rsatishi mumkin.\n\n"
                + "Shuning uchun:\n"
                + "1. Faqat o'zingiz ishongan joyni tanlang.\n"
                + "2. O'z kanalingiz yoki \"Saqlangan xabarlar\" eng xavfsiz.\n"
                + "3. Boshqalarning rasm va videolari bilan nima qilinishi uchun javobgarlik sizda.\n\n"
                + "Buni tushundingizmi?");
        builder.setPositiveButton("Roziman", (d, w) -> {
            ParadoksConfig.setDbExtraConsent(true);
            refresh();
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    static String nameOf(MessagesController controller, long id) {
        if (id > 0) {
            TLRPC.User user = controller.getUser(id);
            if (user != null && UserObject.isUserSelf(user)) {
                return "Saqlangan xabarlar";
            }
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
            int type = holder.getItemViewType();
            return type == T_CHAT || type == T_BUTTON;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == T_HEADER) {
                view = new HeaderCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == T_INFO) {
                view = new TextInfoPrivacyCell(context);
            } else if (viewType == T_CHAT) {
                view = new UserCell(context, 4, 0, false);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextDetailSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.type == T_HEADER) {
                ((HeaderCell) holder.itemView).setText(row.title);
            } else if (row.type == T_INFO) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                cell.setText(row.title);
            } else if (row.type == T_CHAT) {
                ((UserCell) holder.itemView).setData(row.avatar, row.title, row.subtitle, 0, true);
            } else {
                ((TextDetailSettingsCell) holder.itemView).setTextAndValue(row.title, row.subtitle, true);
            }
        }
    }
}
