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
    private int keepMediaRow;
    private int trackEditsRow;
    private int hideSponsoredRow;
    private int deletedRadarRow;
    private int showIdInfoRow;
    private int ghostHeaderRow;
    private int ghostReadRow;
    private int ghostTypingRow;
    private int ghostOnlineRow;
    private int ghostStoriesRow;
    private int ghostScheduleRow;
    private int ghostStartRow;
    private int ghostEndRow;
    private int ghostInfoRow;
    private int lockHeaderRow;
    private int lockPinRow;
    private int lockInfoRow;
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
        keepMediaRow = rowCount++;
        trackEditsRow = rowCount++;
        hideSponsoredRow = rowCount++;
        deletedRadarRow = rowCount++;
        showIdInfoRow = rowCount++;
        ghostHeaderRow = rowCount++;
        ghostReadRow = rowCount++;
        ghostTypingRow = rowCount++;
        ghostOnlineRow = rowCount++;
        ghostStoriesRow = rowCount++;
        ghostScheduleRow = rowCount++;
        ghostStartRow = rowCount++;
        ghostEndRow = rowCount++;
        ghostInfoRow = rowCount++;
        lockHeaderRow = rowCount++;
        lockPinRow = rowCount++;
        lockInfoRow = rowCount++;
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
            } else if (position == keepMediaRow) {
                boolean value = !ParadoksConfig.keepDeletedMedia();
                ParadoksConfig.setKeepDeletedMedia(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == trackEditsRow) {
                boolean value = !ParadoksConfig.trackEdits();
                ParadoksConfig.setTrackEdits(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == hideSponsoredRow) {
                boolean value = !ParadoksConfig.hideSponsored();
                ParadoksConfig.setHideSponsored(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == deletedRadarRow) {
                presentFragment(new ParadoksDeletedActivity());
            } else if (position == ghostReadRow) {
                boolean value = !ParadoksConfig.ghostReadSwitch();
                ParadoksConfig.setGhostRead(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == ghostTypingRow) {
                boolean value = !ParadoksConfig.ghostTypingSwitch();
                ParadoksConfig.setGhostTyping(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == ghostOnlineRow) {
                boolean value = !ParadoksConfig.ghostOnlineSwitch();
                ParadoksConfig.setGhostOnline(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == ghostStoriesRow) {
                boolean value = !ParadoksConfig.ghostStoriesSwitch();
                ParadoksConfig.setGhostStories(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == ghostScheduleRow) {
                boolean value = !ParadoksConfig.ghostScheduleEnabled();
                ParadoksConfig.setGhostScheduleEnabled(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == ghostStartRow || position == ghostEndRow) {
                pickTime(position == ghostStartRow);
            } else if (position == lockPinRow) {
                ParadoksLock.manage(this, () -> {
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            } else if (position == voiceEffectRow) {
                showVoiceEffectDialog();
            }
        });

        fragmentView = contentView;
        return fragmentView;
    }

    private void pickTime(boolean start) {
        if (getParentActivity() == null) {
            return;
        }
        int current = start ? ParadoksConfig.ghostScheduleStart() : ParadoksConfig.ghostScheduleEnd();
        android.app.TimePickerDialog dialog = new android.app.TimePickerDialog(getParentActivity(), (picker, hour, minute) -> {
            int value = hour * 60 + minute;
            if (start) {
                ParadoksConfig.setGhostSchedule(value, ParadoksConfig.ghostScheduleEnd());
            } else {
                ParadoksConfig.setGhostSchedule(ParadoksConfig.ghostScheduleStart(), value);
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }, current / 60, current % 60, true);
        dialog.show();
    }

    private static String formatTime(int minutes) {
        return String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
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
            if (position == generalHeaderRow || position == ghostHeaderRow || position == lockHeaderRow || position == voiceHeaderRow) {
                return TYPE_HEADER;
            } else if (position == showIdRow || position == keepDeletedRow || position == keepMediaRow || position == trackEditsRow || position == hideSponsoredRow
                    || position == ghostReadRow || position == ghostTypingRow || position == ghostOnlineRow || position == ghostStoriesRow || position == ghostScheduleRow) {
                return TYPE_CHECK;
            } else if (position == voiceEffectRow || position == deletedRadarRow || position == ghostStartRow || position == ghostEndRow || position == lockPinRow) {
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
                    String title;
                    if (position == generalHeaderRow) {
                        title = "Umumiy";
                    } else if (position == ghostHeaderRow) {
                        title = "👻 Ghost mode";
                    } else if (position == lockHeaderRow) {
                        title = "🔒 Chat qulfi";
                    } else {
                        title = "Ovoz yozish";
                    }
                    cell.setText(title);
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == showIdRow) {
                        cell.setTextAndCheck("ID ni ko'rsatish", ParadoksConfig.showId(), true);
                    } else if (position == keepDeletedRow) {
                        cell.setTextAndCheck("O'chirilgan xabarlarni saqlash", ParadoksConfig.keepDeleted(), true);
                    } else if (position == keepMediaRow) {
                        cell.setTextAndCheck("O'chirilgan media'ni saqlash", ParadoksConfig.keepDeletedMedia(), true);
                    } else if (position == trackEditsRow) {
                        cell.setTextAndCheck("Tahrirlar tarixini saqlash", ParadoksConfig.trackEdits(), true);
                    } else if (position == hideSponsoredRow) {
                        cell.setTextAndCheck("Reklamani yashirish (kanal va botlar)", ParadoksConfig.hideSponsored(), true);
                    } else if (position == ghostReadRow) {
                        cell.setTextAndCheck("O'qilgan belgisini yashirish", ParadoksConfig.ghostReadSwitch(), true);
                    } else if (position == ghostTypingRow) {
                        cell.setTextAndCheck("\"Yozyapti...\" ni yashirish", ParadoksConfig.ghostTypingSwitch(), true);
                    } else if (position == ghostOnlineRow) {
                        cell.setTextAndCheck("Online holatini yashirish", ParadoksConfig.ghostOnlineSwitch(), true);
                    } else if (position == ghostStoriesRow) {
                        cell.setTextAndCheck("Story ko'rganimni yashirish", ParadoksConfig.ghostStoriesSwitch(), true);
                    } else {
                        cell.setTextAndCheck("Jadval bo'yicha ishlash", ParadoksConfig.ghostScheduleEnabled(), true);
                    }
                    break;
                }
                case TYPE_VALUE: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == deletedRadarRow) {
                        cell.setTextAndValue("O'chirilganlar radari", "Boshqalar o'chirgan xabarlar ro'yxati", false);
                    } else if (position == ghostStartRow) {
                        cell.setTextAndValue("Boshlanish vaqti", formatTime(ParadoksConfig.ghostScheduleStart()), true);
                    } else if (position == ghostEndRow) {
                        cell.setTextAndValue("Tugash vaqti", formatTime(ParadoksConfig.ghostScheduleEnd()), false);
                    } else if (position == lockPinRow) {
                        cell.setTextAndValue("PIN kod", ParadoksLock.hasPin() ? "O'rnatilgan" : "O'rnatilmagan", false);
                    } else {
                        int mode = ParadoksConfig.getVoiceEffect();
                        if (mode < 0 || mode >= VOICE_EFFECT_NAMES.length) {
                            mode = 0;
                        }
                        cell.setTextAndValue("Ovoz effekti", VOICE_EFFECT_NAMES[mode], false);
                    }
                    break;
                }
                default: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    if (position == showIdInfoRow) {
                        cell.setText("ID: profilda foydalanuvchi/kanal ID si ko'rsatiladi, bosilsa nusxa olinadi. O'chirilgan xabar chatda qoladi va \"o'chirilgan\" belgisi bilan ko'rinadi, ro'yxati radarda. O'chirilgan rasm, video va fayllar nusxasi ilovaning o'z papkasida saqlanadi (kesh tozalansa ham yo'qolmaydi, 150 MB gacha). Tahrirlangan xabar eski variantlari xabar menyusidagi \"Tahrirlar tarixi\" bandida. Reklama: kanal va botlardagi \"Sponsored\" xabarlar ko'rsatilmaydi.");
                    } else if (position == ghostInfoRow) {
                        cell.setText("Har bir qismni alohida yoqish mumkin. Jadval yoqilsa, yuqoridagi tanlanganlar faqat belgilangan vaqt oralig'ida ishlaydi (masalan 22:00 dan 08:00 gacha). Ghost o'qilgan belgisi yoqilganda chat menyusidagi \"O'qilgan deb belgilash\" orqali kerakli chatni qo'lda o'qilgan qilish mumkin.");
                    } else if (position == lockInfoRow) {
                        cell.setText("Chat ichidagi menyudan \"Chat qulfi\" ni bosib chatni qulflaysiz. Qulflangan chat har ochilganda PIN so'raydi. PIN faqat shifrlangan (hash) ko'rinishda saqlanadi. Chatlar ro'yxatidagi oxirgi xabar ko'rinishi va bildirishnomalar qulflanmaydi.");
                    } else {
                        cell.setText("Ovozli xabar yozayotganda tanlangan effekt qo'llanadi. Yuborilgan xabar effekt bilan chiqadi.");
                    }
                    break;
                }
            }
        }
    }
}
