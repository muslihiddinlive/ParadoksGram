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
import org.telegram.ui.Cells.TextDetailSettingsCell;
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
    private int preSaveRow;
    private int deletedDbRow;
    private int notifyDeletedRow;
    private int cleanupRow;
    private int keepWarnRow;
    private int trackEditsRow;
    private int hideSponsoredRow;
    private int adInteractionRow;
    private int ownAdsRow;
    private int adServerRow;
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
    private int hiddenSearchRow;
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
        preSaveRow = rowCount++;
        notifyDeletedRow = rowCount++;
        cleanupRow = rowCount++;
        deletedDbRow = rowCount++;
        keepWarnRow = rowCount++;
        trackEditsRow = rowCount++;
        hideSponsoredRow = rowCount++;
        adInteractionRow = rowCount++;
        ownAdsRow = rowCount++;
        adServerRow = rowCount++;
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
        hiddenSearchRow = rowCount++;
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
                final boolean value = !ParadoksConfig.keepDeleted();
                Runnable apply = () -> {
                    ParadoksConfig.setKeepDeleted(value);
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(value);
                    }
                };
                if (value) {
                    confirmEnable(apply);
                } else {
                    apply.run();
                }
            } else if (position == keepMediaRow) {
                final boolean value = !ParadoksConfig.keepDeletedMedia();
                Runnable apply = () -> {
                    ParadoksConfig.setKeepDeletedMedia(value);
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(value);
                    }
                };
                if (value) {
                    confirmEnable(apply);
                } else {
                    apply.run();
                }
            } else if (position == deletedDbRow) {
                presentFragment(new ParadoksDeletedDbActivity());
            } else if (position == notifyDeletedRow) {
                boolean value = !ParadoksConfig.notifyDeleted();
                ParadoksConfig.setNotifyDeleted(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == cleanupRow) {
                showCleanupDialog();
            } else if (position == preSaveRow) {
                boolean value = !ParadoksConfig.preSaveMedia();
                ParadoksConfig.setPreSaveMedia(value);
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
            } else if (position == adInteractionRow) {
                boolean value = !ParadoksConfig.adOnInteraction();
                ParadoksConfig.setAdOnInteraction(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == ownAdsRow) {
                boolean value = !ParadoksConfig.ownAdsEnabled();
                ParadoksConfig.setOwnAdsEnabled(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
            } else if (position == adServerRow) {
                showAdServerDialog();
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
            } else if (position == hiddenSearchRow) {
                boolean value = !ParadoksConfig.showHiddenInSearch();
                ParadoksConfig.setShowHiddenInSearch(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
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

    private void showAdServerDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(getParentActivity());
        input.setSingleLine(true);
        input.setHint("https://sizning-server.onrender.com");
        input.setText(ParadoksConfig.adServerUrl());
        input.setPadding(org.telegram.messenger.AndroidUtilities.dp(24), org.telegram.messenger.AndroidUtilities.dp(12),
                org.telegram.messenger.AndroidUtilities.dp(24), org.telegram.messenger.AndroidUtilities.dp(12));
        org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle("Reklama serveri");
        builder.setMessage("Reklama serveringiz manzili. Bo'sh qoldirsangiz, o'z reklamangiz o'chadi.");
        builder.setView(input);
        builder.setPositiveButton("OK", (d, w) -> {
            String value = input.getText().toString().trim();
            if (!value.isEmpty() && !value.startsWith("https://") && !value.startsWith("http://")) {
                value = "https://" + value;
            }
            ParadoksConfig.setAdServerUrl(value);
            ParadoksAdsRemote.forceRefresh();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Cancel), null);
        showDialog(builder.create());
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

    private static String cleanupPolicyText() {
        int days = ParadoksConfig.mediaRetentionDays();
        int maxMb = ParadoksConfig.mediaMaxMb();
        if (days > 0) {
            return days + " kundan keyin o'chiriladi";
        }
        if (maxMb > 0) {
            return (maxMb / 1024) + " GB dan oshsa eskisi o'chiriladi";
        }
        return "o'chirilmaydi";
    }

    private void showCleanupDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final int[][] options = {{0, 0}, {30, 0}, {90, 0}, {180, 0}, {0, 1024}, {0, 5120}};
        org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle("Avto-tozalash");
        builder.setItems(new CharSequence[]{"Hech qachon (o'chirmaslik)", "30 kundan keyin", "90 kundan keyin", "180 kundan keyin",
                "1 GB dan oshsa eskisini o'chirish", "5 GB dan oshsa eskisini o'chirish"}, (d, which) -> {
            ParadoksConfig.setMediaCleanup(options[which][0], options[which][1]);
            ParadoksCleanup.run();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    private void confirmEnable(Runnable onOk) {
        if (getParentActivity() == null) {
            onOk.run();
            return;
        }
        org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle("Ogohlantirish");
        builder.setMessage("Bu funksiya boshqalar o'chirgan xabar va mediani telefoningizda saqlab qoladi. "
                + "Suhbatdoshingiz xabarni o'chirgan bo'lsa, u buni xohlamagan bo'lishi mumkin. "
                + "Saqlangan narsalarni boshqalarga ko'rsatmang, tarqatmang va qonunga zid maqsadda ishlatmang. "
                + "Maxfiylik va qonunlar bo'yicha javobgarlik sizda.");
        builder.setPositiveButton("Tushundim, yoqish", (d, w) -> onOk.run());
        builder.setNegativeButton("Bekor qilish", null);
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
            } else if (position == showIdRow || position == keepDeletedRow || position == keepMediaRow || position == preSaveRow || position == notifyDeletedRow || position == trackEditsRow || position == hideSponsoredRow || position == adInteractionRow || position == ownAdsRow
                    || position == ghostReadRow || position == ghostTypingRow || position == ghostOnlineRow || position == ghostStoriesRow || position == ghostScheduleRow || position == hiddenSearchRow) {
                return TYPE_CHECK;
            } else if (position == voiceEffectRow || position == adServerRow || position == deletedDbRow || position == cleanupRow || position == deletedRadarRow || position == ghostStartRow || position == ghostEndRow || position == lockPinRow) {
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
                    view = new TextDetailSettingsCell(context);
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
                        cell.setTextAndValueAndCheck("ID ni ko'rsatish", "Profilda foydalanuvchi, bot yoki kanalning ID raqami ko'rinadi, bosilsa nusxa olinadi. Stiker, emoji yoki GIF xabarini bosib turib (yoki stiker panelida ustiga bosib turib) ularning ID'sini ham ko'rasiz. Botlar va dasturlash bilan ishlaganda kerak bo'ladi.", ParadoksConfig.showId(), true, true);
                    } else if (position == keepDeletedRow) {
                        cell.setTextAndValueAndCheck("O'chirilgan xabarlarni saqlash", "Kimdir sizga yozgan xabarni o'chirsa, u chatingizda qoladi va vaqt yonida \"o'chirilgan\" belgisi chiqadi. Faqat sizning telefoningizda ishlaydi, suhbatdoshingiz bilmaydi. Ro'yxati \"O'chirilganlar radari\"da.", ParadoksConfig.keepDeleted(), true, true);
                    } else if (position == keepMediaRow) {
                        cell.setTextAndValueAndCheck("O'chirilgan media'ni saqlash", "O'chirilgan xabardagi rasm, video va fayllarning nusxasi ilovaning o'z papkasida saqlanadi (150 MB gacha), kesh tozalansa ham yo'qolmaydi. Fayl hali yuklanmagan bo'lsa, ilova uni yuklashga urinadi, lekin Telegram serveri o'chirib yuborgan bo'lsa, bo'lmaydi.", ParadoksConfig.keepDeletedMedia(), true, true);
                    } else if (position == notifyDeletedRow) {
                        cell.setTextAndValueAndCheck("Xabar o'chirilganda bildirishnoma", "Kimdir xabarini o'chirsa, telefoningizga \"X xabarni o'chirdi\" degan bildirishnoma keladi (matn boshi bilan). Ovozsiz (mute) chatlar uchun kelmaydi. Ekran qulfida matn yashirin ko'rsatiladi. Standart: o'chiq.", ParadoksConfig.notifyDeleted(), true, true);
                    } else if (position == preSaveRow) {
                        cell.setTextAndValueAndCheck("Media'ni oldindan saqlash (faqat Wi-Fi, 25 MB gacha)", "Yangi kelgan rasm va fayllar fonda oldindan yuklab qo'yiladi, shunda keyin o'chirilsa ham nusxasi tayyor bo'ladi. Faqat Wi-Fi'da, shaxsiy chat va guruhlarda, 25 MB gacha ishlaydi. Trafik va xotira sarflaydi.", ParadoksConfig.preSaveMedia(), true, true);
                    } else if (position == trackEditsRow) {
                        cell.setTextAndValueAndCheck("Tahrirlar tarixini saqlash", "Kimdir xabarini tahrirlasa, eski variantlari saqlanadi. Ularni xabar menyusidagi \"Tahrirlar tarixi\" bandidan ko'rasiz.", ParadoksConfig.trackEdits(), true, true);
                    } else if (position == hideSponsoredRow) {
                        cell.setTextAndValueAndCheck("Reklamani yashirish (kanal va botlar)", "Kanallar va botlardagi \"Sponsored\" (homiylik) reklama xabarlari ko'rsatilmaydi.", ParadoksConfig.hideSponsored(), true, true);
                    } else if (position == ownAdsRow) {
                        cell.setTextAndValueAndCheck("ParadoksGram reklamasi (\"Reklama\" belgisi bilan)", "ParadoksGram reklamasi dasturchi serveridan olinadi va \"Reklama\" belgisi bilan kanal oxirida yoki bot chati tepasida ko'rinadi. Serverga Telegram ma'lumotlaringiz yuborilmaydi, faqat reklama raqami, hodisa turi (ko'rildi yoki bosildi) va tasodifiy anonim raqam.", ParadoksConfig.ownAdsEnabled(), true, true);
                    } else if (position == adInteractionRow) {
                        cell.setTextAndValueAndCheck("Reaksiya yoki izohda reklama ko'rsatish", "Yoqilsa, chatda reaksiya bosganingizda yoki izoh yozganingizda o'sha chatning reklamasi ko'rinadi. Reklama ko'rilgani faqat u haqiqatan ekranda ko'ringandagina hisoblanadi.", ParadoksConfig.adOnInteraction(), true, true);
                    } else if (position == hiddenSearchRow) {
                        cell.setTextAndValueAndCheck("Qidiruvda yashirin chatlarni ko'rsatish", "Yashirin chatlar (chat menyusida \"Yashirin chat\") ro'yxat, forward va bildirishnomalardan yo'qoladi. Bu yoqilsa, ular qidiruvda ham ko'rinadi. Standart: ko'rinmaydi. Yashirinlarni ko'rish uchun qidiruv belgisini bosib turing va PIN kiriting.", ParadoksConfig.showHiddenInSearch(), true, false);
                    } else if (position == ghostReadRow) {
                        cell.setTextAndValueAndCheck("O'qilgan belgisini yashirish", "Xabarlarni o'qisangiz ham, yuboruvchiga \"o'qildi\" belgisi bormaydi. Kerakli chatni chat menyusidagi \"O'qilgan deb belgilash\" bilan qo'lda o'qilgan qilasiz.", ParadoksConfig.ghostReadSwitch(), true, true);
                    } else if (position == ghostTypingRow) {
                        cell.setTextAndValueAndCheck("\"Yozyapti...\" ni yashirish", "Yozayotganingizda suhbatdoshingizga \"yozyapti...\" holati ko'rsatilmaydi.", ParadoksConfig.ghostTypingSwitch(), true, true);
                    } else if (position == ghostOnlineRow) {
                        cell.setTextAndValueAndCheck("Online holatini yashirish", "Ilovada bo'lsangiz ham, boshqalarga online bo'lib ko'rinmaysiz.", ParadoksConfig.ghostOnlineSwitch(), true, true);
                    } else if (position == ghostStoriesRow) {
                        cell.setTextAndValueAndCheck("Story ko'rganimni yashirish", "Boshqalarning story'sini ko'rsangiz, egasi sizning ko'rganingizni bilmaydi.", ParadoksConfig.ghostStoriesSwitch(), true, true);
                    } else {
                        cell.setTextAndValueAndCheck("Jadval bo'yicha ishlash", "Yoqilsa, yuqoridagi ghost sozlamalari faqat quyida belgilangan vaqt oralig'ida ishlaydi (masalan 22:00 dan 08:00 gacha). Boshqa vaqtda odatdagidek ishlaydi.", ParadoksConfig.ghostScheduleEnabled(), true, true);
                    }
                    break;
                }
                case TYPE_VALUE: {
                    TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                    cell.setMultilineDetail(true);
                    String title;
                    String now = null;
                    String info;
                    boolean divider = true;
                    if (position == adServerRow) {
                        String url = ParadoksConfig.adServerUrl();
                        title = "Reklama serveri";
                        now = url.isEmpty() ? "O'rnatilmagan" : url;
                        info = "Reklama olinadigan server manzili. Bo'sh qoldirsangiz, ParadoksGram reklamasi chiqmaydi.";
                    } else if (position == deletedDbRow) {
                        long dbTarget = ParadoksConfig.dbTarget(currentAccount);
                        title = "O'chirilgan media bazasi";
                        now = dbTarget == 0 ? "Tanlanmagan" : ParadoksDeletedDbActivity.nameOf(org.telegram.messenger.MessagesController.getInstance(currentAccount), dbTarget);
                        info = "Boshqalar yuborgan rasm, video va fayllar avtomatik siz tanlagan shaxsiy kanal yoki guruhga forward qilinadi, yuboruvchi o'chirsa ham nusxasi qoladi. Tanlash va to'liq tushuntirish ichkarida.";
                    } else if (position == cleanupRow) {
                        title = "Saqlangan media'ni avto-tozalash";
                        now = cleanupPolicyText() + " (hozir saqlangan: " + (ParadoksCleanup.usedBytes() / (1024 * 1024)) + " MB)";
                        info = "Saqlangan o'chirilgan media'lar vaqt o'tishi yoki hajm oshishi bilan avtomatik o'chiriladi, shunda xotira to'lib ketmaydi. Xabar matni va radardagi yozuv qoladi, faqat media fayl o'chadi. Tozalash ilova ochilganda ishlaydi.";
                    } else if (position == deletedRadarRow) {
                        title = "O'chirilganlar radari";
                        info = "Boshqalar o'chirgan va tahrirlagan xabarlar ro'yxati: kim, qaysi chatda va qachon. Qidiruv va filtr (tur, davr) bor. Saqlangan media'ni ochish yoki galereyaga saqlash, bazadagi nusxani ochish mumkin.";
                        divider = false;
                    } else if (position == ghostStartRow) {
                        title = "Boshlanish vaqti";
                        now = formatTime(ParadoksConfig.ghostScheduleStart());
                        info = "Jadval yoqilgan bo'lsa, ghost mode shu vaqtdan boshlab ishlaydi.";
                    } else if (position == ghostEndRow) {
                        title = "Tugash vaqti";
                        now = formatTime(ParadoksConfig.ghostScheduleEnd());
                        info = "Jadval yoqilgan bo'lsa, ghost mode shu vaqtda o'chadi.";
                        divider = false;
                    } else if (position == lockPinRow) {
                        title = "PIN kod";
                        now = ParadoksLock.hasPin() ? "O'rnatilgan" : "O'rnatilmagan";
                        info = "Chat qulfi uchun PIN. Chat menyusidagi \"Chat qulfi\" bilan chatni qulflaysiz va u har ochilganda PIN so'raladi. PIN faqat shifrlangan (hash) ko'rinishda saqlanadi.";
                        divider = false;
                    } else {
                        int mode = ParadoksConfig.getVoiceEffect();
                        if (mode < 0 || mode >= VOICE_EFFECT_NAMES.length) {
                            mode = 0;
                        }
                        title = "Ovoz effekti";
                        now = VOICE_EFFECT_NAMES[mode];
                        info = "Ovozli xabar yozayotganda tanlangan effekt qo'llanadi va xabar shu effekt bilan yuboriladi.";
                        divider = false;
                    }
                    cell.setTextAndValue(title, now == null ? info : ("Hozir: " + now + "\n" + info), divider);
                    break;
                }
                default: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    if (position == keepWarnRow) {
                        cell.setText("Diqqat: o'chirilgan xabar va mediani saqlash boshqalarning maxfiyligiga tegadi. Saqlanganlarni tarqatmang. \"O'chirilgan media bazasi\"ni yoqsangiz, boshqalar yuborgan media siz tanlagan kanal yoki chatga forward qilinadi.");
                    } else if (position == showIdInfoRow) {
                        cell.setText("Bu bo'limdagi sozlamalar faqat shu ilovada, sizning telefoningizda saqlanadi.");
                    } else if (position == ghostInfoRow) {
                        cell.setText("Ghost mode Telegram'ga ba'zi so'rovlarni yubormaslik orqali ishlaydi. Har bir qismni alohida yoqish mumkin.");
                    } else if (position == lockInfoRow) {
                        cell.setText("Chat qulfi va yashirin chat ilovaning o'zida ishlaydi, ular haqida Telegram serveriga hech narsa yuborilmaydi.");
                    } else {
                        cell.setText("Effekt faqat siz yozadigan ovozli xabarlarga ta'sir qiladi.");
                    }
                    break;
                }
            }
        }
    }
}
