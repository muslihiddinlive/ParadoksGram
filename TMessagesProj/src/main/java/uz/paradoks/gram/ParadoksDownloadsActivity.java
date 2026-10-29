package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Download manager: files that are downloading now (progress, speed), files paused from here, and buttons to pause or resume.
 * "Pause" stops the transfer but keeps the part that is already downloaded; "Resume" continues from it.
 */
public class ParadoksDownloadsActivity extends BaseFragment {

    private static final int MENU_PAUSE_ALL = 1;
    private static final int MENU_RESUME_ALL = 2;
    private static final long REFRESH_MS = 1000;

    /** Files paused from this screen, so they can be resumed later (kept while the app runs). */
    private static final ArrayList<MessageObject> PAUSED = new ArrayList<>();

    private static final class Row {
        MessageObject message;
        boolean paused;
        String name;
        long total;
        long loaded;
        float speed; // bytes per second
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private final HashMap<String, long[]> lastSample = new HashMap<>(); // file -> {loaded, elapsedRealtime}
    private RecyclerListView listView;
    private Adapter adapter;
    private TextView empty;
    private boolean alive;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!alive) {
                return;
            }
            rebuild();
            AndroidUtilities.runOnUIThread(this, REFRESH_MS);
        }
    };

    @Override
    public boolean onFragmentCreate() {
        alive = true;
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        alive = false;
        AndroidUtilities.cancelRunOnUIThread(ticker);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        alive = true;
        AndroidUtilities.cancelRunOnUIThread(ticker);
        AndroidUtilities.runOnUIThread(ticker);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(ticker);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Yuklashlar");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_PAUSE_ALL) {
                    pauseAll();
                } else if (id == MENU_RESUME_ALL) {
                    resumeAll();
                }
            }
        });
        org.telegram.ui.ActionBar.ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_PAUSE_ALL, "Hammasini to'xtatish");
        menu.addItem(MENU_RESUME_ALL, "Hammasini davom ettirish");

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < rows.size()) {
                showActions(rows.get(position));
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        empty = new TextView(context);
        empty.setText("Hozir yuklanayotgan fayl yo'q.\nFayl yuklashni boshlasangiz, shu yerda ko'rinadi.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        empty.setTextSize(15);
        empty.setPadding(dp(32), 0, dp(32), 0);
        root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        rebuild();
        return fragmentView;
    }

    // ------------------------------------------------------------------ data

    private static long sizeOf(MessageObject message) {
        TLRPC.Document document = message.getDocument();
        return document != null ? document.size : 0;
    }

    private void rebuild() {
        rows.clear();
        long now = SystemClock.elapsedRealtime();
        ArrayList<MessageObject> active = new ArrayList<>(DownloadController.getInstance(currentAccount).downloadingFiles);
        FileLoader loader = FileLoader.getInstance(currentAccount);
        for (MessageObject message : active) {
            TLRPC.Document document = message.getDocument();
            if (document == null) {
                continue;
            }
            String fileName = FileLoader.getAttachFileName(document);
            boolean loading = loader.isLoadingFile(fileName);
            Row row = new Row();
            row.message = message;
            row.paused = !loading && isPaused(message);
            if (!loading && !row.paused) {
                continue; // finished or failed
            }
            row.name = FileLoader.getDocumentFileName(document);
            row.total = sizeOf(message);
            long[] sizes = ImageLoader.getInstance().getFileProgressSizes(fileName);
            row.loaded = sizes != null && sizes.length > 0 ? sizes[0] : 0;
            long[] last = lastSample.get(fileName);
            if (loading && last != null && now > last[1] && row.loaded >= last[0]) {
                row.speed = (row.loaded - last[0]) * 1000f / (now - last[1]);
            }
            lastSample.put(fileName, new long[]{row.loaded, now});
            rows.add(row);
        }
        for (MessageObject message : PAUSED) {
            boolean listed = false;
            for (Row row : rows) {
                if (row.message == message || (row.message.getId() == message.getId() && row.message.getDialogId() == message.getDialogId())) {
                    listed = true;
                    break;
                }
            }
            if (!listed && message.getDocument() != null) {
                Row row = new Row();
                row.message = message;
                row.paused = true;
                row.name = FileLoader.getDocumentFileName(message.getDocument());
                row.total = sizeOf(message);
                long[] sizes = ImageLoader.getInstance().getFileProgressSizes(FileLoader.getAttachFileName(message.getDocument()));
                row.loaded = sizes != null && sizes.length > 0 ? sizes[0] : 0;
                rows.add(row);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (empty != null) {
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private static boolean isPaused(MessageObject message) {
        for (MessageObject paused : PAUSED) {
            if (paused == message || (paused.getId() == message.getId() && paused.getDialogId() == message.getDialogId())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ actions

    private void pause(MessageObject message) {
        TLRPC.Document document = message.getDocument();
        if (document == null || isPaused(message)) {
            return;
        }
        PAUSED.add(message);
        FileLoader.getInstance(currentAccount).cancelLoadFile(document, false); // false: keep the downloaded part
    }

    private void resume(MessageObject message) {
        TLRPC.Document document = message.getDocument();
        if (document == null) {
            return;
        }
        for (int i = PAUSED.size() - 1; i >= 0; i--) {
            MessageObject paused = PAUSED.get(i);
            if (paused == message || (paused.getId() == message.getId() && paused.getDialogId() == message.getDialogId())) {
                PAUSED.remove(i);
            }
        }
        FileLoader.getInstance(currentAccount).loadFile(document, message, FileLoader.PRIORITY_NORMAL, 0);
    }

    private void cancel(MessageObject message) {
        TLRPC.Document document = message.getDocument();
        if (document == null) {
            return;
        }
        for (int i = PAUSED.size() - 1; i >= 0; i--) {
            MessageObject paused = PAUSED.get(i);
            if (paused == message || (paused.getId() == message.getId() && paused.getDialogId() == message.getDialogId())) {
                PAUSED.remove(i);
            }
        }
        FileLoader.getInstance(currentAccount).cancelLoadFile(document, true); // true: delete the downloaded part
    }

    private void pauseAll() {
        for (Row row : new ArrayList<>(rows)) {
            if (!row.paused) {
                pause(row.message);
            }
        }
        rebuild();
    }

    private void resumeAll() {
        for (Row row : new ArrayList<>(rows)) {
            if (row.paused) {
                resume(row.message);
            }
        }
        rebuild();
    }

    private void showActions(Row row) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(row.name);
        builder.setItems(new CharSequence[]{row.paused ? "Davom ettirish" : "To'xtatib turish", "Bekor qilish (yuklanganini o'chirish)"}, (d, which) -> {
            if (which == 0) {
                if (row.paused) {
                    resume(row.message);
                } else {
                    pause(row.message);
                }
            } else {
                cancel(row.message);
            }
            rebuild();
        });
        showDialog(builder.create());
    }

    // ------------------------------------------------------------------ list

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576f);
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
            return rows.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(10), dp(16), dp(10));
            box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView title = new TextView(context);
            title.setId(1);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            box.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            bar.setId(2);
            bar.setMax(1000);
            box.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8, 0, 6, 0, 4));
            TextView sub = new TextView(context);
            sub.setId(3);
            sub.setTextSize(13);
            sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            box.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(box);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            View v = holder.itemView;
            ((TextView) v.findViewById(1)).setText(row.name == null ? "Fayl" : row.name);
            ProgressBar bar = v.findViewById(2);
            int permille = row.total > 0 ? (int) Math.min(1000, row.loaded * 1000L / row.total) : 0;
            bar.setProgress(permille);
            String text;
            if (row.paused) {
                text = "To'xtatilgan  •  " + mb(row.loaded) + " / " + mb(row.total) + " (" + permille / 10 + "%)";
            } else {
                text = mb(row.loaded) + " / " + mb(row.total) + " (" + permille / 10 + "%)  •  "
                        + (row.speed > 0 ? String.format(Locale.US, "%.2f MB/s", row.speed / 1048576f) : "...");
            }
            ((TextView) v.findViewById(3)).setText(text);
        }
    }
}
