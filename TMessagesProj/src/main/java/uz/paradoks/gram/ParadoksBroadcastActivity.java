package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Broadcast panel ("Ommaviy yuborish"): write one message (text and/or one media file), pick many
 * chats / a whole folder, confirm the list and send. Finished sends stay in a small cache so they can be
 * loaded again later or deleted.
 */
public class ParadoksBroadcastActivity extends BaseFragment {

    private static final int REQ_MEDIA = 9611;

    private final ParadoksBroadcast.Draft draft = new ParadoksBroadcast.Draft();
    private EditText input;
    private TextView mediaView;
    private TextView recipientsView;
    private LinearLayout historyBox;
    private ParadoksBroadcast.SendJob job;
    private boolean sending;

    // ------------------------------------------------------------------ view

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Ommaviy yuborish");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(88));
        scroll.addView(content, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // message text
        content.addView(label(context, "Xabar"));
        input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHint("Xabar matni (media bo'lsa, izoh bo'lib ketadi)");
        input.setTextSize(16);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(dp(8));
        border.setStroke(dp(1), Theme.getColor(Theme.key_divider));
        border.setColor(0);
        input.setBackground(border);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (!draft.text.isEmpty()) {
            input.setText(draft.text);
        }
        content.addView(input, params(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4));

        // media
        mediaView = button(context, "", this::onMediaClick);
        content.addView(mediaView, params(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10));

        // recipients
        content.addView(label(context, "Qabul qiluvchilar"));
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button(context, "+ Chatlar", this::pickChats), weighted(0));
        buttons.addView(button(context, "+ Jild", this::pickFolder), weighted(8));
        buttons.addView(button(context, "Tozalash", () -> {
            draft.recipients.clear();
            refreshRecipients();
        }), weighted(8));
        content.addView(buttons, params(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4));

        recipientsView = new TextView(context);
        recipientsView.setTextSize(14);
        recipientsView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        recipientsView.setPadding(dp(4), dp(10), dp(4), dp(10));
        recipientsView.setOnClickListener(v -> editRecipients());
        content.addView(recipientsView, params(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4));

        // history
        content.addView(label(context, "Oxirgi yuborilganlar"));
        historyBox = new LinearLayout(context);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(historyBox, params(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4));

        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        TextView send = button(context, "Yuborish", this::confirmAndSend);
        send.setTextSize(16);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(send, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 0, 16, 16));

        refreshMedia();
        refreshRecipients();
        refreshHistory();
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (!sending) {
            ParadoksBroadcast.deleteMediaIfUnused(draft.mediaPath);
        }
    }

    // ------------------------------------------------------------------ small view helpers

    private TextView label(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        tv.setPadding(dp(4), dp(18), dp(4), dp(4));
        return tv;
    }

    private TextView button(Context context, String text, Runnable action) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        tv.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setOnClickListener(v -> action.run());
        return tv;
    }

    private LinearLayout.LayoutParams params(int width, int height, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout.LayoutParams weighted(int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f);
        p.leftMargin = dp(leftDp);
        return p;
    }

    private void toast(String text) {
        Context context = getParentActivity();
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    // ------------------------------------------------------------------ media

    private void refreshMedia() {
        if (mediaView == null) {
            return;
        }
        if (draft.hasMedia()) {
            mediaView.setText("📎 " + (draft.mediaName != null ? draft.mediaName : "media") + " (" + formatSize(new File(draft.mediaPath).length()) + ")  •  o'zgartirish");
        } else {
            mediaView.setText("📎 Media biriktirish (rasm, video, fayl)");
        }
    }

    private void onMediaClick() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        if (!draft.hasMedia()) {
            pickMedia();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Media");
        builder.setItems(new CharSequence[]{"Boshqasini tanlash", "Olib tashlash"}, (d, which) -> {
            if (which == 0) {
                pickMedia();
            } else {
                releaseDraftMedia();
                refreshMedia();
            }
        });
        builder.setNegativeButton("Yopish", null);
        showDialog(builder.create());
    }

    private void pickMedia() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Media tanlang"), REQ_MEDIA);
    }

    private void releaseDraftMedia() {
        String path = draft.mediaPath;
        draft.mediaPath = null;
        draft.mediaName = null;
        draft.mime = null;
        ParadoksBroadcast.deleteMediaIfUnused(path);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_MEDIA || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final Uri uri = data.getData();
        mediaView.setText("Media yuklanmoqda...");
        new Thread(() -> {
            try {
                String name = displayName(context, uri);
                String mime = context.getContentResolver().getType(uri);
                if (mime == null || mime.equals("application/octet-stream")) {
                    mime = ParadoksBroadcast.guessMime(name);
                }
                String safe = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
                if (safe.length() > 60) {
                    safe = safe.substring(safe.length() - 60);
                }
                File target = new File(ParadoksBroadcast.mediaDir(), System.currentTimeMillis() + "_" + safe);
                try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(target)) {
                    if (in == null) {
                        throw new java.io.IOException("no stream");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                final String finalName = name;
                final String finalMime = mime;
                AndroidUtilities.runOnUIThread(() -> {
                    releaseDraftMedia();
                    draft.mediaPath = target.getAbsolutePath();
                    draft.mediaName = finalName;
                    draft.mime = finalMime;
                    refreshMedia();
                });
            } catch (Throwable e) {
                AndroidUtilities.runOnUIThread(() -> {
                    toast("Mediani o'qib bo'lmadi");
                    refreshMedia();
                });
            }
        }, "ParadoksBroadcastCopy").start();
    }

    private static String displayName(Context context, Uri uri) {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
            }
        } catch (Throwable ignore) {
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "media";
        }
        return name;
    }

    // ------------------------------------------------------------------ recipients

    private void refreshRecipients() {
        if (recipientsView == null) {
            return;
        }
        if (draft.recipients.isEmpty()) {
            recipientsView.setText("Hech kim tanlanmagan");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Tanlangan: ").append(draft.recipients.size()).append(" ta\n");
        int shown = 0;
        for (ParadoksBroadcast.Recipient r : draft.recipients.values()) {
            if (shown == 8) {
                break;
            }
            sb.append(shown == 0 ? "" : ", ").append(r.title);
            shown++;
        }
        if (draft.recipients.size() > shown) {
            sb.append(" va yana ").append(draft.recipients.size() - shown).append(" ta");
        }
        sb.append("\n(ro'yxatni o'zgartirish uchun bosing)");
        recipientsView.setText(sb.toString());
    }

    private void pickChats() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", false);
        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((f, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            ArrayList<Long> ids = new ArrayList<>();
            for (MessagesStorage.TopicKey key : dids) {
                ids.add(key.dialogId);
            }
            ParadoksBroadcast.AddResult result = ParadoksBroadcast.addDialogs(currentAccount, draft, ids);
            f.finishFragment();
            AndroidUtilities.runOnUIThread(() -> {
                refreshRecipients();
                showAddResult(null, result);
            }, 300);
            return true;
        });
        presentFragment(fragment);
    }

    private void pickFolder() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final ArrayList<MessagesController.DialogFilter> folders = ParadoksBroadcast.folders(currentAccount);
        if (folders.isEmpty()) {
            toast("Jildlar topilmadi. Telegramda jild yarating (Sozlamalar → Jildlar).");
            return;
        }
        CharSequence[] names = new CharSequence[folders.size()];
        for (int i = 0; i < names.length; i++) {
            MessagesController.DialogFilter f = folders.get(i);
            String name = f.name != null && !f.name.isEmpty() ? f.name : "Jild";
            names[i] = name + "  (" + ParadoksBroadcast.folderDialogIds(f).size() + ")";
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Jildni tanlang");
        builder.setItems(names, (d, which) -> {
            MessagesController.DialogFilter f = folders.get(which);
            ParadoksBroadcast.AddResult result = ParadoksBroadcast.addDialogs(currentAccount, draft, ParadoksBroadcast.folderDialogIds(f));
            refreshRecipients();
            showAddResult(f.name, result);
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    private void showAddResult(String source, ParadoksBroadcast.AddResult result) {
        Context context = getParentActivity();
        if (context == null || (result.added == 0 && result.skipped.isEmpty())) {
            return;
        }
        if (result.skipped.isEmpty()) {
            toast("Qo'shildi: " + result.added + " ta");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Qo'shildi: ").append(result.added).append(" ta\n\nO'tkazib yuborildi (").append(result.skipped.size()).append(" ta):");
        for (int i = 0; i < result.skipped.size() && i < 30; i++) {
            sb.append("\n• ").append(result.skipped.get(i));
        }
        if (result.skipped.size() > 30) {
            sb.append("\n... va yana ").append(result.skipped.size() - 30).append(" ta");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(source != null && !source.isEmpty() ? source : "Qo'shildi");
        builder.setMessage(sb.toString());
        builder.setNegativeButton("Yopish", null);
        showDialog(builder.create());
    }

    private void editRecipients() {
        Context context = getParentActivity();
        if (context == null || draft.recipients.isEmpty()) {
            return;
        }
        ScrollView scroll = new ScrollView(context);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));
        scroll.addView(box);
        final ArrayList<ParadoksBroadcast.Recipient> list = new ArrayList<>(draft.recipients.values());
        final ArrayList<CheckBox> boxes = new ArrayList<>();
        for (ParadoksBroadcast.Recipient r : list) {
            CheckBox cb = new CheckBox(context);
            cb.setText(r.title);
            cb.setChecked(true);
            cb.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            box.addView(cb);
            boxes.add(cb);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Qabul qiluvchilar (belgisizlari olib tashlanadi)");
        builder.setView(scroll);
        builder.setPositiveButton("Saqlash", (d, w) -> {
            for (int i = 0; i < list.size(); i++) {
                if (!boxes.get(i).isChecked()) {
                    draft.recipients.remove(list.get(i).dialogId);
                }
            }
            refreshRecipients();
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    // ------------------------------------------------------------------ sending

    private void confirmAndSend() {
        Context context = getParentActivity();
        if (context == null || sending) {
            return;
        }
        final String text = input.getText().toString().trim();
        if (text.isEmpty() && !draft.hasMedia()) {
            toast("Xabar matnini yozing yoki media biriktiring");
            return;
        }
        if (draft.recipients.isEmpty()) {
            toast("Qabul qiluvchilarni tanlang");
            return;
        }
        draft.text = text;

        final ArrayList<ParadoksBroadcast.Recipient> valid = new ArrayList<>();
        final ArrayList<String> skipped = new ArrayList<>();
        for (ParadoksBroadcast.Recipient r : draft.recipients.values()) {
            String reason = ParadoksBroadcast.checkSendable(currentAccount, r.dialogId);
            if (reason == null) {
                valid.add(r);
            } else {
                skipped.add(r.title + " — " + reason);
            }
        }
        if (valid.isEmpty()) {
            toast("Hech bir chatga yuborib bo'lmaydi");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Xabar:\n");
        if (!text.isEmpty()) {
            sb.append("\"").append(text.length() > 200 ? text.substring(0, 200) + "..." : text).append("\"\n");
        }
        if (draft.hasMedia()) {
            sb.append("📎 ").append(draft.mediaName != null ? draft.mediaName : "media").append("\n");
        }
        sb.append("\nQuyidagi ").append(valid.size()).append(" ta chatga yuboriladi:");
        for (int i = 0; i < valid.size() && i < 50; i++) {
            sb.append("\n• ").append(valid.get(i).title);
        }
        if (valid.size() > 50) {
            sb.append("\n... va yana ").append(valid.size() - 50).append(" ta");
        }
        if (!skipped.isEmpty()) {
            sb.append("\n\nO'tkazib yuboriladi (").append(skipped.size()).append(" ta):");
            for (int i = 0; i < skipped.size() && i < 10; i++) {
                sb.append("\n• ").append(skipped.get(i));
            }
            if (skipped.size() > 10) {
                sb.append("\n... va yana ").append(skipped.size() - 10).append(" ta");
            }
        }
        sb.append("\n\nAniq yubormoqchimisiz?");

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Yuborishni tasdiqlang");
        builder.setMessage(sb.toString());
        builder.setPositiveButton("Ha, yuborish", (d, w) -> startSend(valid));
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    private void startSend(ArrayList<ParadoksBroadcast.Recipient> valid) {
        Context context = getParentActivity();
        if (context == null || sending) {
            return;
        }
        final ParadoksBroadcast.Draft snapshot = draft.copy();
        snapshot.recipients.clear();
        for (ParadoksBroadcast.Recipient r : valid) {
            snapshot.recipients.put(r.dialogId, r);
        }
        sending = true;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), 0);
        final TextView status = new TextView(context);
        status.setTextSize(15);
        status.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        status.setText("Boshlanmoqda...");
        layout.addView(status, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        layout.addView(bar, params(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16));

        job = new ParadoksBroadcast.SendJob(currentAccount, snapshot);
        final ParadoksBroadcast.SendJob currentJob = job;
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Yuborilmoqda");
        builder.setView(layout);
        builder.setNegativeButton("To'xtatish", (d, w) -> currentJob.cancelled.set(true));
        final AlertDialog dialog = builder.create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        currentJob.progress = (done, total, name) -> AndroidUtilities.runOnUIThread(() -> {
            status.setText("Yuborilmoqda: " + (done + 1) + " / " + total + "\n" + name);
            bar.setProgress((int) (1000f * done / Math.max(1, total)));
        });
        currentJob.done = (queued, skippedCount, cancelled) -> AndroidUtilities.runOnUIThread(() -> {
            sending = false;
            try {
                dialog.dismiss();
            } catch (Throwable ignore) {
            }
            if (queued > 0) {
                ParadoksBroadcast.remember(snapshot, queued);
            }
            if (historyBox != null) {
                refreshHistory();
            }
            Context ctx = getParentActivity();
            if (ctx == null) {
                return;
            }
            AlertDialog.Builder result = new AlertDialog.Builder(ctx);
            result.setTitle(cancelled ? "To'xtatildi" : "Yuborildi");
            result.setMessage(queued + " ta chatga yuborildi"
                    + (skippedCount > 0 ? ", " + skippedCount + " tasi o'tkazib yuborildi" : "")
                    + ".\n\nXabar \"Oxirgi yuborilganlar\" ro'yxatida saqlandi, keyinroq qayta yuborishingiz mumkin.");
            result.setNegativeButton("Yopish", null);
            showDialog(result.create());
        });

        showDialog(dialog);
        Thread thread = new Thread(currentJob, "ParadoksBroadcast");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    // ------------------------------------------------------------------ history

    private void refreshHistory() {
        Context context = getParentActivity();
        if (context == null || historyBox == null) {
            return;
        }
        historyBox.removeAllViews();
        ArrayList<ParadoksBroadcast.Entry> list = ParadoksBroadcast.history();
        if (list.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("Hali hech narsa yuborilmagan");
            empty.setTextSize(14);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            historyBox.addView(empty);
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("dd.MM HH:mm", Locale.US);
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            final ParadoksBroadcast.Entry entry = list.get(i);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            String preview = entry.draft.text.isEmpty() ? "[media]" : entry.draft.text.replace('\n', ' ');
            if (preview.length() > 60) {
                preview = preview.substring(0, 60) + "...";
            }
            TextView info = new TextView(context);
            info.setText(preview + "\n" + format.format(new Date(entry.time)) + "  •  " + entry.draft.recipients.size() + " ta chat"
                    + (entry.draft.mediaPath != null ? "  •  media" : ""));
            info.setTextSize(14);
            info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            info.setPadding(dp(4), dp(10), dp(4), dp(10));
            info.setOnClickListener(v -> loadEntry(entry));
            row.addView(info, new LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView delete = new TextView(context);
            delete.setText("✕");
            delete.setTextSize(18);
            delete.setGravity(Gravity.CENTER);
            delete.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            delete.setPadding(dp(14), dp(10), dp(14), dp(10));
            delete.setOnClickListener(v -> confirmDelete(index));
            row.addView(delete, new LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            historyBox.addView(row, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        TextView clear = new TextView(context);
        clear.setText("Hammasini tozalash");
        clear.setTextSize(14);
        clear.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        clear.setPadding(dp(4), dp(14), dp(4), dp(10));
        clear.setOnClickListener(v -> confirmClear());
        historyBox.addView(clear);
    }

    private void loadEntry(ParadoksBroadcast.Entry entry) {
        releaseDraftMedia();
        draft.text = entry.draft.text;
        input.setText(draft.text);
        draft.recipients.clear();
        draft.recipients.putAll(entry.draft.recipients);
        if (entry.draft.mediaPath != null) {
            if (new File(entry.draft.mediaPath).exists()) {
                draft.mediaPath = entry.draft.mediaPath;
                draft.mediaName = entry.draft.mediaName;
                draft.mime = entry.draft.mime;
            } else {
                toast("Media fayli topilmadi, faqat matn yuklandi");
            }
        }
        refreshMedia();
        refreshRecipients();
        toast("Yuklandi. Tekshirib, \"Yuborish\" ni bosing.");
    }

    /** The attached media file may have been removed together with a cache entry. */
    private void syncMedia() {
        if (draft.mediaPath != null && !draft.hasMedia()) {
            draft.mediaPath = null;
            draft.mediaName = null;
            draft.mime = null;
        }
        refreshMedia();
    }

    private void confirmDelete(int index) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Keshdan o'chirilsinmi?");
        builder.setMessage("Bu yozuv (va unga biriktirilgan media nusxasi) ro'yxatdan o'chadi. Yuborilgan xabarlar chatlarda qoladi.");
        builder.setPositiveButton("O'chirish", (d, w) -> {
            ParadoksBroadcast.deleteEntry(index);
            syncMedia();
            refreshHistory();
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    private void confirmClear() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Hammasi tozalansinmi?");
        builder.setMessage("Oxirgi yuborilganlar ro'yxati va saqlangan media nusxalari o'chadi. Yuborilgan xabarlar chatlarda qoladi.");
        builder.setPositiveButton("Tozalash", (d, w) -> {
            ParadoksBroadcast.clearHistory();
            syncMedia();
            refreshHistory();
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }
}
