package uz.paradoks.gram;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ommaviy stiker qo'shish: foydalanuvchi .tgs fayllardan iborat ZIP tanlaydi,
 * biz uni ochib chiqamiz va Telegram'ning haqiqiy "stiker paketi yaratish"
 * oynasini (StickersAlert) ko'rsatamiz - u yerda har bir stikerga emoji
 * tanlanadi, paket nomi kiritiladi va "Yaratish" tugmasi bosilsa,
 * stickers.createStickerSet orqali to'g'ridan-to'g'ri API chaqirilib,
 * @Stickers botiga yozishmasdan paket chop etiladi.
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
                    ((TextCell) holder.itemView).setTextAndIcon("ZIP fayl tanlash (.tgs stikerlar)", R.drawable.msg_folders, false);
                } else {
                    ((TextInfoPrivacyCell) holder.itemView).setText("ZIP ichida .tgs (animatsion stiker) fayllar bo'lishi kerak. Ularni tanlagach, har biriga emoji belgilaysiz, paket nomini kiritasiz va \"Yaratish\"ni bosasiz - paket @Stickers botiga yozishmasdan, to'g'ridan-to'g'ri hisobingizga qo'shiladi.");
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
        if (name != null && !name.toLowerCase().endsWith(".zip")) {
            showError("Bu ZIP fayl emasga o'xshaydi: " + name);
            return;
        }
        loading = true;
        showProgress("ZIP ochilmoqda...");
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<File> extracted = new ArrayList<>();
            int skipped = 0;
            File outDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "sticker_import_" + System.currentTimeMillis());
            outDir.mkdirs();
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry entry;
                int index = 0;
                while ((entry = zis.getNextEntry()) != null && extracted.size() < 120) {
                    String entryName = entry.getName();
                    if (entry.isDirectory() || entryName == null || !entryName.toLowerCase().endsWith(".tgs")) {
                        continue;
                    }
                    File out = new File(outDir, "sticker_" + (index++) + ".tgs");
                    long written = 0;
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                            written += len;
                        }
                    }
                    // Telegram animatsion stikerlar uchun joriy limit ~64KB atrofida;
                    // bo'sh yoki haddan katta fayllarni o'tkazib yuboramiz, qolganlari davom etadi.
                    if (written <= 0 || written > 64 * 1024) {
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
                    showError("ZIP ichidan to'g'ri .tgs fayl topilmadi" + (finalSkipped > 0 ? " (" + finalSkipped + " ta fayl hajmi noto'g'ri bo'lgani uchun o'tkazib yuborildi)" : "") + ".");
                    return;
                }
                ArrayList<android.os.Parcelable> uris = new ArrayList<>();
                for (File f : extracted) {
                    uris.add(Uri.fromFile(f));
                }
                if (finalSkipped > 0) {
                    showError(finalSkipped + " ta fayl o'tkazib yuborildi (hajmi noto'g'ri), qolgan " + extracted.size() + " ta bilan davom etamiz.");
                }
                if (getParentActivity() == null) {
                    return;
                }
                showDialog(new StickersAlert(getParentActivity(), "ParadoksGram", uris, null, null));
            });
        });
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        try {
            String path = uri.getLastPathSegment();
            return path;
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
