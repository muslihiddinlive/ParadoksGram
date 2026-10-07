package uz.paradoks.gram;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/** ParadoksGram settings page (Settings -> ParadoksGram). */
public class ParadoksSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;

    private static final String[] VOICE_EFFECT_NAMES = {
            "O'chiq", "Robot", "G'or (cave)", "Minecraft villager"
    };

    private int rowCount;
    private int generalHeaderRow;
    private int showIdRow;
    private int keepDeletedRow;
    private int trackEditsRow;
    private int showIdInfoRow;
    private int voiceHeaderRow;
    private int voiceEffectRow;
    private int voiceInfoRow;

    private RecyclerListView listView;
    private Adapter adapter;

    private void updateRows() {
        rowCount = 0;
        generalHeaderRow = rowCount++;
        showIdRow = rowCount++;
        keepDeletedRow = rowCount++;
        trackEditsRow = rowCount++;
        showIdInfoRow = rowCount++;
        voiceHeaderRow = rowCount++;
        voiceEffectRow = rowCount++;
        voiceInfoRow = rowCount++;
    }

    @Override
    public boolean onFragmentCreate() {
        updateRows();
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
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new Adapter(context));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == showIdRow) {
                boolean value = !ParadoksConfig.showId();
                ParadoksConfig.setShowId(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == keepDeletedRow) {
                boolean value = !ParadoksConfig.keepDeleted();
                ParadoksConfig.setKeepDeleted(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == trackEditsRow) {
                boolean value = !ParadoksConfig.trackEdits();
                ParadoksConfig.setTrackEdits(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == voiceEffectRow) {
                showVoiceEffectDialog();
            }
        });

        fragmentView = contentView;
        return fragmentView;
    }

    private void showVoiceEffectDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Ovoz effekti");
        builder.setItems(VOICE_EFFECT_NAMES, (dialog, which) -> {
            ParadoksConfig.setVoiceEffect(which);
            ParadoksVoice.reset();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_CHECK || type == TYPE_VALUE;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == generalHeaderRow || position == voiceHeaderRow) {
                return TYPE_HEADER;
            } else if (position == showIdRow || position == keepDeletedRow || position == trackEditsRow) {
                return TYPE_CHECK;
            } else if (position == voiceEffectRow) {
                return TYPE_VALUE;
            }
            return TYPE_INFO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_VALUE:
                    view = new TextSettingsCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    view = new TextInfoPrivacyCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(position == generalHeaderRow ? "Umumiy" : "Ovoz yozish");
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == showIdRow) {
                        cell.setTextAndCheck("ID ni ko'rsatish", ParadoksConfig.showId(), true);
                    } else if (position == keepDeletedRow) {
                        cell.setTextAndCheck("O'chirilgan xabarlarni saqlash", ParadoksConfig.keepDeleted(), true);
                    } else {
                        cell.setTextAndCheck("Tahrirlar tarixini saqlash", ParadoksConfig.trackEdits(), false);
                    }
                    break;
                }
                case TYPE_VALUE: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    int mode = ParadoksConfig.getVoiceEffect();
                    if (mode < 0 || mode >= VOICE_EFFECT_NAMES.length) {
                        mode = 0;
                    }
                    cell.setTextAndValue("Ovoz effekti", VOICE_EFFECT_NAMES[mode], false);
                    break;
                }
                default: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    if (position == showIdInfoRow) {
                        cell.setText("ID: profilda foydalanuvchi/kanal ID si (kanalda -100 bilan) ko'rsatiladi, bosilsa nusxa olinadi. O'chirilgan xabar chatda qoladi va \"o'chirilgan\" belgisi bilan ko'rinadi (belgi chatni qayta ochganda yoki aylantirganda paydo bo'ladi). Tahrirlangan xabar eski variantlari xabar menyusidagi \"Tahrirlar tarixi\" bandida ko'rinadi. Faqat ushbu o'rnatishdan keyingi o'zgarishlar saqlanadi.");
                    } else {
                        cell.setText("Ovozli xabar yozayotganda tanlangan effekt qo'llanadi. Yuborilgan xabar effekt bilan chiqadi.");
                    }
                    break;
                }
            }
        }
    }
}
