package uz.paradoks.gram;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.StickersAlert;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ommaviy stiker qo'shish: foydalanuvchi .tgs/.webp/.png/.gif/.webm fayllardan
 * iborat ZIP tanlaydi (yoki chatdagi ZIP xabarining uch nuqta menyusidan
 * "Stikerlar yasash"ni bosadi), biz uni ochib chiqamiz va Telegram'ning
 * haqiqiy "stiker paketi yaratish" oynasini (StickersAlert) ko'rsatamiz -
 * u yerda har bir stikerga emoji tanlanadi, paket nomi kiritiladi va
 * "Yaratish" tugmasi bosilsa, stickers.createStickerSet orqali
 * to'g'ridan-to'g'ri API chaqirilib, @Stickers botiga yozishmasdan
 * paket chop etiladi.
 */
public class ParadoksStickerImportActivity extends BaseFragment {

    private static final int REQUEST_CODE_PICK_ZIP = 7341;

    private static final int T_CELL = 0;
    private static final int T_INFO = 1;

    private boolean loading;
    private AlertDialog progressDialog;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Stiker paketi yaratish");
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

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return holder.getItemViewType() == T_CELL;
            }

            @Override
            public int getItemCount() {
                return 2;
            }

            @Override
            public int getItemViewType(int position) {
                return position == 0 ? T_CELL : T_INFO;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                View view;
                if (viewType == T_CELL) {
                    TextCell cell = new TextCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                } else {
                    TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context);
                    cell.setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    view = cell;
                }
                view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(view);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                if (position == 0) {
                    ((TextCell) holder.itemView).setTextAndIcon("ZIP fayl tanlash (.tgs, .webp, .png, .gif, .webm)", R.drawable.msg_folders, false);
                } else {
                    ((TextInfoPrivacyCell) holder.itemView).setText("ZIP ichida stiker qilsa bo'ladigan fayllar (.tgs, .webp, .png, .gif, .webm) bo'lishi kerak. Ularni tanlagach, har biriga emoji belgilaysiz, paket nomini kiritasiz va \"Yaratish\"ni bosasiz - paket @Stickers botiga yozishmasdan, to'g'ridan-to'g'ri hisobingizga qo'shiladi. Eslatma: Telegram faqat 512x512 statik rasm yoki to'g'ri animatsion formatni qabul qiladi, mos kelmagan fayllar avtomatik o'tkazib yuboriladi.");
                }
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position == 0 && !loading) {
                pickZip();
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = contentView;
        return fragmentView;
    }

    private void pickZip() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            if (Build.VERSION.SDK_INT >= 18) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            }
            startActivityForResult(Intent.createChooser(intent, "ZIP fayl tanlash"), REQUEST_CODE_PICK_ZIP);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE_PICK_ZIP || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String name = queryDisplayName(uri);
        if (name != null && !name.toLowerCase(Locale.US).endsWith(".zip")) {
            showError("Bu ZIP fayl emasga o'xshaydi: " + name);
            return;
        }
        importZip(uri);
    }

    /** Chatdagi ZIP xabarining "..." menyusidan "Stikerlar yasash" bosilganda chaqiriladi. */
    public static void presentAndImport(BaseFragment from, Uri zipUri) {
        ParadoksStickerImportActivity fragment = new ParadoksStickerImportActivity();
        from.presentFragment(fragment);
        AndroidUtilities.runOnUIThread(() -> fragment.importZip(zipUri), 300);
    }

    /** Called both from the picker above and from the chat message's "..." menu on an existing ZIP file. */
    public void importZip(Uri zipUri) {
        loading = true;
        showProgress("ZIP ochilmoqda...");
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<File> extracted = new ArrayList<>();
            int skipped = 0;
            File outDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "sticker_import_" + System.currentTimeMillis());
            outDir.mkdirs();
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(zipUri);
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry entry;
                int index = 0;
                while ((entry = zis.getNextEntry()) != null && extracted.size() < 120) {
                    String entryName = entry.getName();
                    String ext = extensionOf(entryName);
                    if (entry.isDirectory() || ext == null) {
                        continue;
                    }
                    File out = new File(outDir, "sticker_" + (index++) + "." + ext);
                    long written = 0;
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                            written += len;
                        }
                    }
                    // .tgs uchun Telegram'ning animatsion stiker hajm limiti ~64KB atrofida,
                    // statik/video formatlar uchun kattaroq limit qo'yamiz; bo'sh yoki haddan
                    // katta fayllarni o'tkazib yuboramiz, qolganlari bilan davom etamiz.
                    long maxSize = "tgs".equals(ext) ? 64 * 1024 : 512 * 1024;
                    if (written <= 0 || written > maxSize) {
                        out.delete();
                        skipped++;
                    } else {
                        extracted.add(out);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    hideProgress();
                    loading = false;
                    showError("ZIP ni ochib bo'lmadi: " + e.getMessage());
                });
                return;
            }

            final int finalSkipped = skipped;
            AndroidUtilities.runOnUIThread(() -> {
                hideProgress();
                loading = false;
                if (extracted.isEmpty()) {
                    showError("ZIP ichidan stiker qilsa bo'ladigan fayl topilmadi" + (finalSkipped > 0 ? " (" + finalSkipped + " ta fayl hajmi/formati noto'g'ri bo'lgani uchun o'tkazib yuborildi)" : "") + ".");
                    return;
                }
                ArrayList<android.os.Parcelable> uris = new ArrayList<>();
                for (File f : extracted) {
                    uris.add(Uri.fromFile(f));
                }
                if (finalSkipped > 0) {
                    showError(finalSkipped + " ta fayl o'tkazib yuborildi (hajmi/formati mos emas), qolgan " + extracted.size() + " ta bilan davom etamiz.");
                }
                if (getParentActivity() == null) {
                    return;
                }
                showDialog(new StickersAlert(getParentActivity(), "ParadoksGram", uris, null, null));
            });
        });
    }

    /** Qo'llab-quvvatlanadigan kengaytmalardan biri bo'lsa, o'shani qaytaradi; bo'lmasa null. */
    @Nullable
    private static String extensionOf(@Nullable String entryName) {
        if (entryName == null) {
            return null;
        }
        String lower = entryName.toLowerCase(Locale.US);
        if (lower.endsWith(".tgs")) return "tgs";
        if (lower.endsWith(".webp")) return "webp";
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpg";
        if (lower.endsWith(".gif")) return "gif";
        if (lower.endsWith(".webm")) return "webm";
        return null;
    }

    /**
     * Xabar menyusi uchun tezkor tekshiruv: ZIP ichida stiker qilsa bo'ladigan kamida
     * bitta fayl bormi. Faqat nom/kengaytmaga qaraydi, hech narsani ochib yozmaydi -
     * shuning uchun tez va menyu qurilayotganda chaqirsa bo'ladi.
     */
    public static boolean looksLikeStickerZip(@Nullable File zipFile) {
        if (zipFile == null || !zipFile.exists() || !zipFile.getName().toLowerCase(Locale.US).endsWith(".zip")) {
            return false;
        }
        try (InputStream in = new java.io.FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            int checked = 0;
            while ((entry = zis.getNextEntry()) != null && checked < 500) {
                checked++;
                if (!entry.isDirectory() && extensionOf(entry.getName()) != null) {
                    return true;
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        try {
            return uri.getLastPathSegment();
        } catch (Exception e) {
            return null;
        }
    }

    private void showProgress(String message) {
        if (getParentActivity() == null) {
            return;
        }
        progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setMessage(message);
        progressDialog.setCanCancel(false);
        showDialog(progressDialog);
    }

    private void hideProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignore) {
            }
            progressDialog = null;
        }
    }

    private void showError(String text) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setMessage(text);
        builder.setPositiveButton(org.telegram.messenger.LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }
}
