package uz.paradoks.gram;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
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

import java.util.ArrayList;

/**
 * One page of the ParadoksGram settings, built like Telegram's own settings pages: a group of plain
 * rows (switches and "title - value" rows) followed by a gray explanation text.
 */
public class ParadoksSettingsPageActivity extends BaseFragment {

    public static final int PAGE_GHOST = 0;
    public static final int PAGE_DELETED = 1;
    public static final int PAGE_SECURITY = 2;
    public static final int PAGE_ADS = 3;
    public static final int PAGE_MISC = 4;
    public static final int PAGE_LOOK = 5;

    private interface BoolGet {
        boolean get();
    }

    private interface BoolSet {
        void set(boolean value);
    }

    private interface Text {
        String get();
    }

    private static final int T_HEADER = 0;
    private static final int T_CHECK = 1;
    private static final int T_VALUE = 2;
    private static final int T_INFO = 3;

    private static final String[] VOICE_EFFECT_NAMES = {"O'chiq", "Robot", "G'or (cave)", "Minecraft villager"};

    private static final class Row {
        int type;
        String title;
        BoolGet get;
        BoolSet set;
        boolean warn; // ask for confirmation before switching on
        Text value;
        Runnable click;
    }

    private final int page;
    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;

    public ParadoksSettingsPageActivity(int page) {
        this.page = page;
    }

    // ---------------------------------------------------------------- rows

    private void header(String title) {
        Row row = new Row();
        row.type = T_HEADER;
        row.title = title;
        rows.add(row);
    }

    private void info(String text) {
        Row row = new Row();
        row.type = T_INFO;
        row.title = text;
        rows.add(row);
    }

    private void check(String title, BoolGet get, BoolSet set) {
        Row row = new Row();
        row.type = T_CHECK;
        row.title = title;
        row.get = get;
        row.set = set;
        rows.add(row);
    }

    private void checkWarn(String title, BoolGet get, BoolSet set) {
        check(title, get, set);
        rows.get(rows.size() - 1).warn = true;
    }

    private void value(String title, Text value, Runnable click) {
        Row row = new Row();
        row.type = T_VALUE;
        row.title = title;
        row.value = value;
        row.click = click;
        rows.add(row);
    }

    private String glassSupportText() {
        int sdk = android.os.Build.VERSION.SDK_INT;
        String version = "Android " + android.os.Build.VERSION.RELEASE;
        if (sdk >= 33) {
            return version + ": to'liq qo'llab-quvvatlanadi";
        } else if (sdk >= 31) {
            return version + ": faqat xira (Liquid Glass uchun Android 13+)";
        }
        return version + ": qo'llab-quvvatlanmaydi (Android 12+ kerak)";
    }

    private void askRestart() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Qayta ishga tushirish");
        builder.setMessage("O'zgarish to'liq qo'llanishi uchun ilovani qayta ishga tushirish kerak.");
        builder.setPositiveButton("Hozir qayta ishga tushirish", (d, w) -> restartApp());
        builder.setNegativeButton("Keyinroq", null);
        showDialog(builder.create());
    }

    private static void restartApp() {
        try {
            android.content.Context context = org.telegram.messenger.ApplicationLoader.applicationContext;
            android.content.Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            android.app.PendingIntent pending = android.app.PendingIntent.getActivity(context, 7731, intent,
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            ((android.app.AlarmManager) context.getSystemService(android.content.Context.ALARM_SERVICE))
                    .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pending);
            System.exit(0);
        } catch (Throwable ignore) {
        }
    }

    private String title() {
        switch (page) {
            case PAGE_GHOST:
                return "Ghost mode";
            case PAGE_DELETED:
                return "O'chirilgan va tahrirlangan";
            case PAGE_SECURITY:
                return "Chat qulfi va yashirin chat";
            case PAGE_ADS:
                return "Reklama";
            case PAGE_LOOK:
                return "Liquid Glass va ko'rinish";
            default:
                return "Qo'shimcha";
        }
    }

    private void buildRows() {
        rows.clear();
        switch (page) {
            case PAGE_GHOST:
                header("Yashirish");
                check("O'qildi belgisini yashirish", ParadoksConfig::ghostReadSwitch, ParadoksConfig::setGhostRead);
                check("\"Yozyapti...\" ni yashirish", ParadoksConfig::ghostTypingSwitch, ParadoksConfig::setGhostTyping);
                check("Online holatini yashirish", ParadoksConfig::ghostOnlineSwitch, ParadoksConfig::setGhostOnline);
                check("Story ko'rganimni yashirish", ParadoksConfig::ghostStoriesSwitch, ParadoksConfig::setGhostStories);
                info("Xabarlarni o'qisangiz ham yuboruvchiga \"o'qildi\" belgisi bormaydi (kerakli chatni chat menyusidagi "
                        + "\"O'qilgan deb belgilash\" bilan qo'lda o'qilgan qilasiz). Yozayotganingizda \"yozyapti...\" ko'rinmaydi. "
                        + "Online yoqilsa, boshqalarga online bo'lib ko'rinmaysiz va \"oxirgi ko'rinish\" vaqti qotib qoladi. "
                        + "Boshqalarning story'sini ko'rsangiz, egasi bilmaydi.\n\n"
                        + "Chat tepasidagi ghost tugmasi hamma qismni bir bosishda yoqadi yoki o'chiradi.");
                header("Jadval");
                check("Jadval bo'yicha ishlash", ParadoksConfig::ghostScheduleEnabled, ParadoksConfig::setGhostScheduleEnabled);
                value("Boshlanish vaqti", () -> formatTime(ParadoksConfig.ghostScheduleStart()), () -> pickTime(true));
                value("Tugash vaqti", () -> formatTime(ParadoksConfig.ghostScheduleEnd()), () -> pickTime(false));
                info("Jadval yoqilsa, yuqoridagi ghost sozlamalari faqat belgilangan vaqt oralig'ida ishlaydi "
                        + "(masalan 22:00 dan 08:00 gacha). Boshqa vaqtda odatdagidek ishlaydi.");
                break;

            case PAGE_DELETED:
                header("Saqlash");
                checkWarn("O'chirilgan xabarlarni saqlash", ParadoksConfig::keepDeleted, ParadoksConfig::setKeepDeleted);
                checkWarn("O'chirilgan media'ni saqlash", ParadoksConfig::keepDeletedMedia, ParadoksConfig::setKeepDeletedMedia);
                check("Media'ni oldindan saqlash", ParadoksConfig::preSaveMedia, ParadoksConfig::setPreSaveMedia);
                check("Saqlangan media'ni chatda ko'rsatish", ParadoksConfig::restoreBackupMedia, ParadoksConfig::setRestoreBackupMedia);
                check("Tahrirlar tarixini saqlash", ParadoksConfig::trackEdits, ParadoksConfig::setTrackEdits);
                info("Kimdir xabarini o'chirsa, u chatingizda qoladi va \"o'chirilgan\" belgisi chiqadi. Rasm, video va fayllarning nusxasi "
                        + "ilova papkasida saqlanadi (150 MB gacha). Oldindan saqlash yangi kelgan rasm va fayllarni fonda yuklab qo'yadi "
                        + "(faqat Wi-Fi, shaxsiy chat va guruhlarda, 25 MB gacha), shunda keyin o'chirilsa ham nusxasi tayyor bo'ladi. "
                        + "\"Saqlangan media'ni chatda ko'rsatish\": o'chirilgan xabardagi rasm yoki video server'dan o'chib ketgan bo'lsa ham, chatning o'zida ochiladi "
                        + "(saqlangan fayldan, u bo'lmasa bazadagi nusxadan yuklab).\n\n"
                        + "Tahrirlangan xabarlarning eski variantlari saqlanadi, ularni xabar menyusidagi \"Tahrirlar tarixi\" dan ko'rasiz.\n\n"
                        + "Diqqat: bu boshqalarning maxfiyligiga tegadi. Saqlanganlarni tarqatmang.");
                header("Bildirishnoma va tozalash");
                check("Xabar o'chirilganda bildirishnoma", ParadoksConfig::notifyDeleted, ParadoksConfig::setNotifyDeleted);
                value("Saqlangan media'ni avto-tozalash", () -> cleanupPolicyText() + ", " + (ParadoksCleanup.usedBytes() / (1024 * 1024)) + " MB", this::showCleanupDialog);
                info("Bildirishnoma \"X xabarni o'chirdi\" ko'rinishida keladi, matn ekran qulfida yashirin ko'rsatiladi, ovozsiz (mute) chatlar uchun kelmaydi. "
                        + "Avto-tozalash eski saqlangan media fayllarni vaqt yoki hajm bo'yicha o'chiradi, xabar matni va radardagi yozuv qoladi. "
                        + "Tozalash ilova ochilganda ishlaydi.");
                header("Ko'rish va baza");
                value("O'chirilganlar radari", () -> "", () -> presentFragment(new ParadoksDeletedActivity()));
                value("O'chirilgan media bazasi", () -> {
                    long target = ParadoksConfig.dbTarget(currentAccount);
                    return target == 0 ? "Tanlanmagan" : ParadoksDeletedDbActivity.nameOf(MessagesController.getInstance(currentAccount), target);
                }, () -> presentFragment(new ParadoksDeletedDbActivity()));
                info("Radar boshqalar o'chirgan va tahrirlagan xabarlar ro'yxatini ko'rsatadi (qidiruv va filtr bor). "
                        + "Baza: boshqalar yuborgan rasm, video va fayllar avtomatik siz tanlagan shaxsiy kanal yoki guruhga forward qilinadi, yuboruvchi o'chirsa ham nusxasi qoladi.");
                break;

            case PAGE_SECURITY:
                header("Chat qulfi");
                value("PIN kod", () -> ParadoksLock.hasPin() ? "O'rnatilgan" : "O'rnatilmagan", () -> ParadoksLock.manage(this, () -> {
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                }));
                check("Qidiruvda yashirin chatlarni ko'rsatish", ParadoksConfig::showHiddenInSearch, ParadoksConfig::setShowHiddenInSearch);
                info("Chat menyusidagi \"Chat qulfi\" chatni qulflaydi va u har ochilganda PIN so'raydi. PIN faqat shifrlangan (hash) ko'rinishida saqlanadi.\n\n"
                        + "\"Yashirin chat\" ro'yxat, forward va bildirishnomalardan yo'qoladi. Tumbler yoqilsa, ular qidiruvda ham ko'rinadi; "
                        + "o'chiq bo'lsa, yashirinlarni ko'rish uchun qidiruv belgisini bosib turing va PIN kiriting.\n\n"
                        + "Ikkalasi ham ilovaning o'zida ishlaydi, ular haqida Telegram serveriga hech narsa yuborilmaydi.");
                break;

            case PAGE_ADS:
                header("Reklama");
                check("Reklamani yashirish (kanal va botlar)", ParadoksConfig::hideSponsored, ParadoksConfig::setHideSponsored);
                check("ParadoksGram reklamasi", ParadoksConfig::ownAdsEnabled, ParadoksConfig::setOwnAdsEnabled);
                check("Reaksiya yoki izohda reklama ko'rsatish", ParadoksConfig::adOnInteraction, ParadoksConfig::setAdOnInteraction);
                value("Reklama serveri", () -> ParadoksConfig.adServerUrl().isEmpty() ? "O'rnatilmagan" : ParadoksConfig.adServerUrl(), this::showAdServerDialog);
                info("Birinchisi kanal va botlardagi \"Sponsored\" (homiylik) xabarlarini yashiradi.\n\n"
                        + "ParadoksGram reklamasi dasturchi serveridan olinadi va \"Reklama\" belgisi bilan kanal oxirida yoki bot chati tepasida ko'rinadi. "
                        + "Serverga Telegram ma'lumotlaringiz yuborilmaydi, faqat reklama raqami, hodisa turi (ko'rildi yoki bosildi) va tasodifiy anonim raqam.\n\n"
                        + "Reaksiya yoki izoh yozganingizda o'sha chatning reklamasi ko'rinadi; reklama haqiqatan ekranda ko'ringandagina hisoblanadi. "
                        + "Server manzili bo'sh bo'lsa, ParadoksGram reklamasi chiqmaydi.");
                break;

            case PAGE_LOOK:
                header("Liquid Glass");
                check("Liquid Glass (Apple uslubi)", () -> org.telegram.messenger.LiteMode.isEnabledSetting(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS), v -> {
                    org.telegram.messenger.LiteMode.toggleFlag(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS, v);
                    askRestart();
                });
                check("Xira (blur) effekti", () -> org.telegram.messenger.LiteMode.isEnabledSetting(org.telegram.messenger.LiteMode.FLAG_CHAT_BLUR), v -> {
                    org.telegram.messenger.LiteMode.toggleFlag(org.telegram.messenger.LiteMode.FLAG_CHAT_BLUR, v);
                    askRestart();
                });
                check("Zaif telefonda ham majburan yoqish", ParadoksConfig::forceBlur, v -> {
                    ParadoksConfig.setForceBlur(v);
                    if (v) {
                        org.telegram.messenger.LiteMode.toggleFlag(org.telegram.messenger.LiteMode.FLAG_CHAT_BLUR, true);
                    }
                    askRestart();
                });
                value("Qurilma", this::glassSupportText, () -> {
                });
                info("Liquid Glass: iOS'dagi kabi shaffof, yorug'likni sindiradigan \"suyuq shisha\". Pastki tab paneli, chat va profil oynalaridagi panellarga qo'llanadi. "
                        + "To'liq effekt uchun Android 13 yoki yangisi kerak; Android 12'da faqat xira (blur) ishlaydi, eskirog'ida ikkalasi ham yo'q.\n\n"
                        + "Telegram bu effektlarni zaif telefonlarda (masalan, Redmi Note 11 sinfida) o'chirib qo'yadi. \"Majburan yoqish\" xira (blur) effektini shunday telefonlarda ham yoqadi: "
                        + "chat paneli va menyular orqasi xira bo'ladi. Batareyani ko'proq sarflaydi va ilovani sekinlashtirishi mumkin, sekin bo'lsa o'chiring.\n\n"
                        + "O'zgarish ilova qayta ishga tushgandan keyin to'liq qo'llanadi.");
                break;

            default:
                header("Qo'shimcha");
                check("ID ni ko'rsatish", ParadoksConfig::showId, ParadoksConfig::setShowId);
                value("Ovoz effekti", () -> {
                    int mode = ParadoksConfig.getVoiceEffect();
                    return VOICE_EFFECT_NAMES[mode < 0 || mode >= VOICE_EFFECT_NAMES.length ? 0 : mode];
                }, this::showVoiceEffectDialog);
                header("Paradoks AI");
                check("Sarlavhada Paradoks AI tugmasi", ParadoksConfig::showAiButton, ParadoksConfig::setShowAiButton);
                info("Chat ro'yxati sarlavhasida (ghost tugmasi yonida) Paradoks AI tugmasi chiqadi. Bosilganda @ParadoksAiBot bilan gaplashadigan AI oynasi ochiladi: "
                        + "savolingiz botga o'zingizning akkauntingizdan yuboriladi va javob shu yerda ko'rinadi. Botning xotirasi, vositalari va limitlari ishlayveradi. "
                        + "O'zgarish chat ro'yxati qayta ochilganda ko'rinadi.");
                header("Paradoks AI buyruqlari");
                check("AI buyruqlarini yoqish", ParadoksConfig::deviceCommandsEnabled, v -> {
                    if (v) {
                        confirmDeviceCommands(() -> {
                            ParadoksConfig.setDeviceCommandsEnabled(true);
                            refresh();
                        });
                    } else {
                        ParadoksConfig.setDeviceCommandsEnabled(false);
                        refresh();
                    }
                });
                value("AI server manzili", () -> ParadoksConfig.aiServerUrl().isEmpty() ? "O'rnatilmagan" : ParadoksConfig.aiServerUrl(), this::showAiServerDialog);
                info("Yoqsangiz, Paradoks AI chatida so'ragan amallarni telefoningizda O'ZI bajara oladi: ko'rsatgan chatga xabar yozish, qaysi chatlarda o'qilmagan xabar borligini aytish (o'qildi deb BELGILAMAY), "
                        + "yuborgan videongizni story qilib qo'yish, kimdir yuborgan .apk faylining paket nomi/ruxsatlari/imzosini o'qib berish. Chatni o'chirish ANIQ roziligingizdan keyingina bajariladi. "
                        + "Bu amallar sizning akkauntingizdan, siz Telegram'da qanday qilsangiz xuddi shunday bajariladi — AI hech qachon so'ramasdan muhim amal qilmaydi.");
                header("Jonli efir");
                check("Jonli efirni yozib olish", ParadoksConfig::recordStreams, ParadoksConfig::setRecordStreams);
                info("Jonli efirni (Live stream) tomosha qilayotganingizda orqa fonda yozib boradi. Efir tugaganda (yoki 1 daqiqa hech narsa kelmasa) "
                        + "yozuv bitta videoga birlashtirilib \"Saqlangan xabarlar\"ga yuboriladi. Bu faqat jonli efirlarda ishlaydi, guruhdagi oddiy video chatda emas. "
                        + "\"Saqlashni cheklash\" yoqilgan chat efirlari yozilmaydi.\n\n"
                        + "Eslatma: boshqa ishtirokchilar yozayotganingizni bilmaydi. Ustoz yoki tashkilotchining ruxsatisiz yozib olish ba'zi joylarda qoidaga yoki qonunga zid bo'lishi mumkin, "
                        + "yozuvni faqat shaxsiy ko'rish uchun ishlating va tarqatmang.");
                info("ID: profilda foydalanuvchi, bot yoki kanalning ID raqami ko'rinadi (bosilsa nusxa olinadi). Stiker, emoji yoki GIF xabarini "
                        + "bosib turib (yoki stiker panelida ustiga bosib turib) ularning ID'sini ham ko'rasiz.\n\n"
                        + "Ovoz effekti: ovozli xabar yozayotganda tanlangan effekt qo'llanadi va xabar shu effekt bilan yuboriladi.");
                header("Sozlamalarni zaxiralash");
                value("Sozlamalarni yuklab olish", () -> "JSON fayl", this::exportSettings);
                value("Sozlamalarni tiklash", () -> "Fayldan", this::importSettings);
                info("ParadoksGram'ning o'ziga tegishli sozlamalar (ghost mode, chat qulfi, yashirin/qulflangan chatlar ro'yxati, "
                        + "reklama va AI server manzillari, o'chirilganlar bazasi manzili va h.k.) JSON faylga yoziladi. "
                        + "Telegram akkaunti, xabarlar, kontaktlar va bu qurilmaning AI buyruqlari uchun kaliti FAYLGA yozilmaydi — ular qurilmada qoladi.\n\n"
                        + "Faylda chat qulfi PIN'ining xeshi va qaysi chatlarni yashirgan/qulflaganingiz bor, shuning uchun uni faqat ishongan joyda saqlang. "
                        + "Tiklashda faqat ParadoksGram taniydigan sozlamalar qo'llanadi, faylda boshqa nima bo'lsa ham e'tiborsiz qoldiriladi.");
                break;
        }
    }

    // ---------------------------------------------------------------- UI

    @Override
    public boolean onFragmentCreate() {
        buildRows();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title());
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
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row row = rows.get(position);
            if (row.type == T_CHECK) {
                final boolean newValue = !row.get.get();
                Runnable apply = () -> {
                    row.set.set(newValue);
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(newValue);
                    }
                };
                if (newValue && row.warn) {
                    confirmEnable(apply);
                } else {
                    apply.run();
                }
            } else if (row.type == T_VALUE && row.click != null) {
                row.click.run();
            }
        });
        fragmentView = contentView;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged(); // values may have changed in a sub-screen
        }
    }

    // ---------------------------------------------------------------- dialogs

    private void confirmDeviceCommands(Runnable onOk) {
        if (getParentActivity() == null) {
            onOk.run();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("AI buyruqlariga ishonasizmi?");
        builder.setMessage("Paradoks AI chatidagi so'rovlar bo'yicha telefoningizda amal bajara boshlaydi: xabar yozish, story qo'yish va hokazo — "
                + "bularning hammasi SIZNING akkauntingizdan ketadi. Faqat o'zingiz ishongan AI server manzilini kiriting. "
                + "Chatni o'chirish kabi qaytarib bo'lmaydigan amallarni AI avval sizdan so'raydi, lekin boshqa amallarni darhol bajaradi.");
        builder.setPositiveButton("Tushundim, yoqish", (d, w) -> onOk.run());
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    private void showAiServerDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(getParentActivity());
        input.setSingleLine(true);
        input.setHint("https://sizning-ai-bot.onrender.com");
        input.setText(ParadoksConfig.aiServerUrl());
        input.setPadding(org.telegram.messenger.AndroidUtilities.dp(24), org.telegram.messenger.AndroidUtilities.dp(12),
                org.telegram.messenger.AndroidUtilities.dp(24), org.telegram.messenger.AndroidUtilities.dp(12));
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("AI server manzili");
        builder.setMessage("Paradoks AI botingiz joylashgan server manzili (Render va h.k.). AI buyruqlari shu bilan gaplashadi.");
        builder.setView(input);
        builder.setPositiveButton("OK", (d, w) -> {
            String value = input.getText().toString().trim();
            if (!value.isEmpty() && !value.startsWith("https://") && !value.startsWith("http://")) {
                value = "https://" + value;
            }
            ParadoksConfig.setAiServerUrl(value);
            refresh();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
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
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
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
            refresh();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
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
            refresh();
        }, current / 60, current % 60, true);
        dialog.show();
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
            refresh();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private static String cleanupPolicyText() {
        int days = ParadoksConfig.mediaRetentionDays();
        int maxMb = ParadoksConfig.mediaMaxMb();
        if (days > 0) {
            return days + " kundan keyin";
        }
        if (maxMb > 0) {
            return (maxMb / 1024) + " GB gacha";
        }
        return "o'chirilmaydi";
    }

    private void showCleanupDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final int[][] options = {{0, 0}, {30, 0}, {90, 0}, {180, 0}, {0, 1024}, {0, 5120}};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Avto-tozalash");
        builder.setItems(new CharSequence[]{"Hech qachon (o'chirmaslik)", "30 kundan keyin", "90 kundan keyin", "180 kundan keyin",
                "1 GB dan oshsa eskisini o'chirish", "5 GB dan oshsa eskisini o'chirish"}, (d, which) -> {
            ParadoksConfig.setMediaCleanup(options[which][0], options[which][1]);
            ParadoksCleanup.run();
            refresh();
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    private void confirmEnable(Runnable onOk) {
        if (getParentActivity() == null) {
            onOk.run();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Ogohlantirish");
        builder.setMessage("Bu funksiya boshqalar o'chirgan xabar va mediani telefoningizda saqlab qoladi. "
                + "Suhbatdoshingiz xabarni o'chirgan bo'lsa, u buni xohlamagan bo'lishi mumkin. "
                + "Saqlangan narsalarni boshqalarga ko'rsatmang, tarqatmang va qonunga zid maqsadda ishlatmang. "
                + "Maxfiylik va qonunlar bo'yicha javobgarlik sizda.");
        builder.setPositiveButton("Tushundim, yoqish", (d, w) -> onOk.run());
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    // ---------------------------------------------------------------- backup

    private static final int REQ_IMPORT = 8821;

    private void exportSettings() {
        if (getParentActivity() == null) return;
        new Thread(() -> {
            try {
                String json = ParadoksBackup.buildExportJson().toString(2);
                java.io.File out = new java.io.File(ApplicationLoader.applicationContext.getExternalFilesDir(null),
                        ParadoksBackup.suggestedFileName());
                try (java.io.FileWriter fw = new java.io.FileWriter(out)) { fw.write(json); }
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        ApplicationLoader.applicationContext,
                        ApplicationLoader.applicationContext.getPackageName() + ".provider", out);
                android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
                share.setType("application/json");
                share.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                share.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    try {
                        getParentActivity().startActivity(android.content.Intent.createChooser(share, "Sozlamalarni ulashish"));
                    } catch (Throwable e) {
                        toast(getParentActivity(), "Faylni ulashib bo'lmadi: " + out.getAbsolutePath());
                    }
                });
            } catch (Throwable e) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                        toast(getParentActivity(), "Eksport xatosi: " + e.getMessage()));
            }
        }, "ParadoksBackupExport").start();
    }

    private void importSettings() {
        try {
            android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            pick.setType("*/*");
            pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            startActivityForResult(android.content.Intent.createChooser(pick, "ParadoksGram sozlamalar faylini tanlang"), REQ_IMPORT);
        } catch (Throwable e) {
            toast(getParentActivity(), "Fayl tanlashni ochib bo'lmadi");
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != REQ_IMPORT || resultCode != android.app.Activity.RESULT_OK || data == null) return;
        android.net.Uri uri = data.getData();
        if (uri == null) return;
        new Thread(() -> {
            try {
                java.io.InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("Faylni ochib bo'lmadi");
                byte[] buf = new byte[2 * 1024 * 1024];
                int n = 0, read;
                while ((read = in.read(buf, n, buf.length - n)) > 0) n += read;
                in.close();
                String text = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                org.json.JSONObject root = ParadoksBackup.parse(text);
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> confirmRestore(root));
            } catch (Throwable e) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                        toast(getParentActivity(), "Fayl o'qib bo'lmadi: " + e.getMessage()));
            }
        }, "ParadoksBackupRead").start();
    }

    private void confirmRestore(org.json.JSONObject root) {
        if (getParentActivity() == null) return;
        String exportedAt = root.optString("exported_at", "?");
        int build = root.optInt("build", 0);
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Sozlamalarni tiklash")
                .setMessage("Sana: " + exportedAt + "\nBuild: " + build + "\n\nJoriy sozlamalar o'chirib, fayldagilar qo'llanadi. Davom etasizmi?")
                .setPositiveButton("Tiklash", (d, w) -> applyRestore(root))
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void applyRestore(org.json.JSONObject root) {
        ParadoksBackup.Result r = ParadoksBackup.restore(root);
        if (r.ok) {
            buildRows();
            if (adapter != null) adapter.notifyDataSetChanged();
            toast(getParentActivity(), "Tiklandi: " + r.applied + " ta sozlama qo'llandi"
                    + (r.skipped > 0 ? ", " + r.skipped + " ta e'tiborsiz" : ""));
        } else {
            toast(getParentActivity(), "Tiklash xatosi: " + r.error);
        }
    }

    private static void toast(android.content.Context ctx, String msg) {
        if (ctx == null) return;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show());
    }

    private void refresh() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private static String formatTime(int minutes) {
        return String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    // ---------------------------------------------------------------- adapter

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == T_CHECK || type == T_VALUE;
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
            } else if (viewType == T_CHECK) {
                view = new TextCheckCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == T_VALUE) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            boolean divider = position + 1 < rows.size() && (rows.get(position + 1).type == T_CHECK || rows.get(position + 1).type == T_VALUE);
            switch (row.type) {
                case T_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.title);
                    break;
                case T_CHECK:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(row.title, row.get.get(), divider);
                    break;
                case T_VALUE:
                    String value = row.value == null ? "" : row.value.get();
                    ((TextSettingsCell) holder.itemView).setTextAndValue(row.title, value, divider);
                    break;
                default:
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    boolean last = position == rows.size() - 1;
                    cell.setBackgroundDrawable(Theme.getThemedDrawableByKey(context, last ? R.drawable.greydivider_bottom : R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    cell.setText(row.title);
                    break;
            }
        }
    }
}
