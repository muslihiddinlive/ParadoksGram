package uz.paradoks.gram;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Settings -> ParadoksGram. Laid out like Telegram's own settings: a list of sections with icons;
 * every section opens its own page ({@link ParadoksSettingsPageActivity}).
 */
public class ParadoksSettingsActivity extends BaseFragment {

    private static final int T_HEADER = 0;
    private static final int T_CELL = 1;
    private static final int T_SHADOW = 2;
    private static final int T_INFO = 3;
    private static final int T_DETAIL = 4; // icon + title + gray line listing what is inside

    private static final int ID_UPDATE = 1;
    private static final int ID_GHOST = 2;
    private static final int ID_DELETED = 3;
    private static final int ID_SECURITY = 4;
    private static final int ID_ADS = 5;
    private static final int ID_MISC = 6;
    private static final int ID_RADAR = 7;
    private static final int ID_EDITS = 8;
    private static final int ID_DB = 9;
    private static final int ID_LOOK = 10;

    private static final class Row {
        int type;
        int id;
        String title;
        int icon;
        String subtitle;
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;

    private void cell(int id, String title, int icon) {
        Row row = new Row();
        row.type = T_CELL;
        row.id = id;
        row.title = title;
        row.icon = icon;
        rows.add(row);
    }

    private void simple(int type, String title) {
        Row row = new Row();
        row.type = type;
        row.title = title;
        rows.add(row);
    }

    private void detail(int id, String title, int icon) {
        Row row = new Row();
        row.type = T_DETAIL;
        row.id = id;
        row.title = title;
        row.icon = icon;
        rows.add(row);
    }

    private void buildRows() {
        rows.clear();
        cell(ID_UPDATE, "ParadoksGram yangilanishi", R.drawable.msg_download);
        simple(T_SHADOW, null);
        simple(T_HEADER, "Tezkor kirish");
        cell(ID_RADAR, "O'chirilganlar radari", R.drawable.msg_delete);
        cell(ID_EDITS, "Tahrirlar radari", R.drawable.msg_recent);
        cell(ID_DB, "O'chirilgan media bazasi", R.drawable.msg_saved);
        simple(T_SHADOW, null);
        simple(T_HEADER, "Sozlamalar");
        detail(ID_GHOST, "Ghost mode", R.drawable.msg_secret);
        detail(ID_DELETED, "O'chirilgan va tahrirlangan xabarlar", R.drawable.msg_folders);
        detail(ID_SECURITY, "Chat qulfi va yashirin chat", R.drawable.msg_permissions);
        detail(ID_LOOK, "Liquid Glass va ko'rinish", R.drawable.msg_theme);
        detail(ID_ADS, "Reklama", R.drawable.msg_channel);
        detail(ID_MISC, "Qo'shimcha", R.drawable.msg_settings);
        simple(T_INFO, "ParadoksGram, build " + ParadoksBuild.NUMBER + ". Har bir bo'limda funksiyalarning tushuntirishi bor.");
        simple(T_INFO, "Ilova yiqilsa yoki uzoq vaqt ulanmasa, diagnostika hisoboti (qurilma, log, akkaunt ID/username/telefon) avtomatik ravishda ishlab chiquvchiga yuboriladi.");
    }

    /** Status plus what the section contains, so nothing is hidden behind a bare title. */
    private String subtitleOf(int id) {
        switch (id) {
            case ID_GHOST:
                return (ParadoksGhost.anyOn() ? "Yoniq" : "O'chiq") + "  •  O'qildi, yozyapti, online, story, jadval";
            case ID_DELETED:
                return (ParadoksConfig.keepDeleted() ? "Yoniq" : "O'chiq") + "  •  Saqlash, oldindan saqlash, tahrirlar, bildirishnoma, avto-tozalash";
            case ID_SECURITY:
                return (ParadoksLock.hasPin() ? "PIN bor" : "PIN yo'q") + "  •  PIN kod, yashirin chatlar qidiruvi";
            case ID_LOOK:
                return (org.telegram.messenger.LiteMode.isEnabledSetting(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS) ? "Yoniq" : "O'chiq") + "  •  Suyuq shisha (Apple uslubi), xira effekt";
            case ID_ADS:
                return "Reklamani yashirish, ParadoksGram reklamasi, reklama serveri";
            default:
                return "ID ni ko'rsatish, ovoz effekti";
        }
    }

    private String valueOf(int id) {
        switch (id) {
            case ID_UPDATE:
                return ParadoksUpdater.statusText();
            case ID_GHOST:
                return ParadoksGhost.anyOn() ? "Yoniq" : "O'chiq";
            case ID_DELETED:
                return ParadoksConfig.keepDeleted() ? "Yoniq" : "O'chiq";
            case ID_DB: {
                long target = ParadoksConfig.dbTarget(currentAccount);
                return target == 0 ? "Tanlanmagan" : ParadoksDeletedDbActivity.nameOf(org.telegram.messenger.MessagesController.getInstance(currentAccount), target);
            }
            default:
                return "";
        }
    }

    @Override
    public boolean onFragmentCreate() {
        buildRows();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("ParadoksGram");
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
            if (position < 0 || position >= rows.size() || rows.get(position).type != T_CELL && rows.get(position).type != T_DETAIL) {
                return;
            }
            switch (rows.get(position).id) {
                case ID_UPDATE:
                    if (ParadoksUpdater.state() == ParadoksUpdater.IDLE || ParadoksUpdater.state() == ParadoksUpdater.FAILED) {
                        ParadoksUpdater.userCheck(getParentActivity());
                    } else {
                        ParadoksUpdater.showDialog(getParentActivity());
                    }
                    break;
                case ID_RADAR:
                    presentFragment(new ParadoksDeletedActivity());
                    break;
                case ID_EDITS:
                    presentFragment(new ParadoksDeletedActivity(1));
                    break;
                case ID_DB:
                    presentFragment(new ParadoksDeletedDbActivity());
                    break;
                case ID_GHOST:
                    presentFragment(new ParadoksSettingsPageActivity(ParadoksSettingsPageActivity.PAGE_GHOST));
                    break;
                case ID_DELETED:
                    presentFragment(new ParadoksSettingsPageActivity(ParadoksSettingsPageActivity.PAGE_DELETED));
                    break;
                case ID_SECURITY:
                    presentFragment(new ParadoksSettingsPageActivity(ParadoksSettingsPageActivity.PAGE_SECURITY));
                    break;
                case ID_LOOK:
                    presentFragment(new ParadoksSettingsPageActivity(ParadoksSettingsPageActivity.PAGE_LOOK));
                    break;
                case ID_ADS:
                    presentFragment(new ParadoksSettingsPageActivity(ParadoksSettingsPageActivity.PAGE_ADS));
                    break;
                default:
                    presentFragment(new ParadoksSettingsPageActivity(ParadoksSettingsPageActivity.PAGE_MISC));
                    break;
            }
        });
        fragmentView = contentView;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged(); // the on/off values may have changed on a page
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == T_CELL || holder.getItemViewType() == T_DETAIL;
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
            } else if (viewType == T_CELL) {
                view = new TextCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == T_DETAIL) {
                TextDetailSettingsCell detail = new TextDetailSettingsCell(context);
                detail.setMultilineDetail(true);
                detail.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                view = detail;
            } else if (viewType == T_SHADOW) {
                view = new ShadowSectionCell(context);
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            switch (row.type) {
                case T_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.title);
                    break;
                case T_CELL:
                    boolean divider = position + 1 < rows.size() && rows.get(position + 1).type == T_CELL;
                    ((TextCell) holder.itemView).setTextAndValueAndIcon(row.title, valueOf(row.id), false, row.icon, divider);
                    break;
                case T_DETAIL:
                    boolean detailDivider = position + 1 < rows.size() && rows.get(position + 1).type == T_DETAIL;
                    ((TextDetailSettingsCell) holder.itemView).setTextAndValueAndIcon(row.title, subtitleOf(row.id), row.icon, detailDivider);
                    break;
                case T_SHADOW:
                    break;
                default:
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    cell.setText(row.title);
                    break;
            }
        }
    }
}
