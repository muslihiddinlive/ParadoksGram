package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Shows a PDF (page images through Android's own PdfRenderer), a Word .docx (formatted text and tables)
 * or an Excel .xlsx/.xls file (sheet tabs and a scrollable grid). Read only.
 */
public class ParadoksDocViewerActivity extends BaseFragment {

    private final File file;
    private final String displayName;
    private final String ext;
    private final boolean protectedContent;

    private FrameLayout root;
    private ProgressBar progress;
    private volatile boolean destroyed;

    // PDF
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private ExecutorService pdfExecutor;
    private LruCache<Integer, Bitmap> pageCache;
    private int pageCount;
    private float pageRatio = 1.414f;

    public ParadoksDocViewerActivity(File file, String displayName, boolean protectedContent) {
        this.file = file;
        this.displayName = displayName;
        this.ext = ParadoksDocViewer.extension(displayName);
        this.protectedContent = protectedContent;
    }

    // ------------------------------------------------------------------ fragment

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(displayName);
        actionBar.setSubtitle(formatSize(file.length()));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    if (getParentActivity() != null) {
                        ParadoksDocViewer.openExternally(getParentActivity(), file, displayName);
                    }
                } else if (id == 2) {
                    if (getParentActivity() != null) {
                        ParadoksDocViewer.saveToDownloads(getParentActivity(), file, displayName, false);
                    }
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem more = menu.addItem(10, R.drawable.ic_ab_other);
        more.addSubItem(1, R.drawable.msg_openin, "Boshqa ilova bilan ochish");
        if (!protectedContent) {
            more.addSubItem(2, R.drawable.msg_download, "Yuklashlarga saqlash");
        }

        root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(ext.equals("pdf") ? Theme.key_windowBackgroundGray : Theme.key_windowBackgroundWhite));
        progress = new ProgressBar(context);
        root.addView(progress, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        fragmentView = root;
        load(context);
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        destroyed = true;
        if (pdfExecutor != null) {
            final PdfRenderer r = renderer;
            final ParcelFileDescriptor d = descriptor;
            try {
                pdfExecutor.execute(() -> {
                    try {
                        if (r != null) {
                            r.close();
                        }
                        if (d != null) {
                            d.close();
                        }
                    } catch (Throwable ignore) {
                    }
                });
            } catch (RejectedExecutionException ignore) {
            }
            pdfExecutor.shutdown();
        }
    }

    // ------------------------------------------------------------------ loading

    private void load(Context context) {
        new Thread(() -> {
            try {
                switch (ext) {
                    case "pdf": {
                        openPdf();
                        AndroidUtilities.runOnUIThread(() -> showPdf(context));
                        break;
                    }
                    case "docx": {
                        final ParadoksDocParsers.Doc doc = ParadoksDocParsers.readDocx(file);
                        AndroidUtilities.runOnUIThread(() -> showDoc(context, doc));
                        break;
                    }
                    case "xlsx":
                    case "xls": {
                        final ArrayList<ParadoksDocParsers.Sheet> sheets = ext.equals("xls") ? ParadoksXlsReader.read(file) : ParadoksDocParsers.readXlsx(file);
                        AndroidUtilities.runOnUIThread(() -> showSheets(context, sheets));
                        break;
                    }
                    default:
                        fail("Bu fayl turi ichki ko'rgichda ochilmaydi");
                }
            } catch (ParadoksDocParsers.ParseException e) {
                fail(e.getMessage());
            } catch (SecurityException e) {
                fail("Fayl parol bilan himoyalangan");
            } catch (OutOfMemoryError e) {
                fail("Fayl juda katta, telefon xotirasi yetmadi");
            } catch (Throwable e) {
                fail("Faylni ochib bo'lmadi (buzilgan bo'lishi mumkin)");
            }
        }, "ParadoksDocLoad").start();
    }

    private void fail(String text) {
        AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || root == null) {
                return;
            }
            root.removeAllViews();
            TextView tv = new TextView(root.getContext());
            tv.setText(text + "\n\n⋮ menyusidan \"Boshqa ilova bilan ochish\" ni sinab ko'ring.");
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(15);
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            tv.setPadding(dp(32), dp(32), dp(32), dp(32));
            root.addView(tv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        });
    }

    private void setContent(View content) {
        root.removeAllViews();
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
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

    // ------------------------------------------------------------------ PDF

    private void openPdf() throws Exception {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(descriptor);
        pageCount = renderer.getPageCount();
        if (pageCount > 0) {
            PdfRenderer.Page page = renderer.openPage(0);
            if (page.getWidth() > 0) {
                pageRatio = page.getHeight() / (float) page.getWidth();
            }
            page.close();
        }
    }

    private void showPdf(Context context) {
        if (destroyed || root == null) {
            return;
        }
        if (pageCount <= 0) {
            fail("PDF bo'sh");
            return;
        }
        actionBar.setSubtitle(pageCount + " bet  •  " + formatSize(file.length()));
        pdfExecutor = Executors.newSingleThreadExecutor();
        pageCache = new LruCache<Integer, Bitmap>(40 * 1024 * 1024) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount();
            }
        };
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setPadding(0, dp(4), 0, dp(4));
        list.setClipToPadding(false);
        list.setAdapter(new PdfAdapter());
        setContent(list);
    }

    private final class PdfAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ImageView image = new ImageView(parent.getContext());
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(Color.WHITE);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            lp.bottomMargin = dp(4);
            image.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(image) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final ImageView image = (ImageView) holder.itemView;
            image.setTag(position);
            final int width = Math.max(1, parentWidth());
            image.setMinimumHeight((int) (width * pageRatio));
            Bitmap cached = pageCache.get(position);
            if (cached != null) {
                image.setImageBitmap(cached);
                image.setMinimumHeight(0);
                return;
            }
            image.setImageDrawable(null);
            final int target = Math.min(1400, (int) (width * 1.25f));
            try {
                pdfExecutor.execute(() -> {
                    if (destroyed) {
                        return;
                    }
                    Bitmap bitmap = null;
                    try {
                        PdfRenderer.Page page = renderer.openPage(position);
                        int h = Math.max(1, (int) (target * page.getHeight() / (float) Math.max(1, page.getWidth())));
                        bitmap = Bitmap.createBitmap(target, h, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        page.close();
                    } catch (Throwable e) {
                        bitmap = null;
                    }
                    final Bitmap result = bitmap;
                    if (result != null) {
                        pageCache.put(position, result);
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        Object tag = image.getTag();
                        if (!destroyed && result != null && tag instanceof Integer && (Integer) tag == position) {
                            image.setImageBitmap(result);
                            image.setMinimumHeight(0);
                        }
                    });
                });
            } catch (RejectedExecutionException ignore) {
            }
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }

        private int parentWidth() {
            return root != null && root.getWidth() > 0 ? root.getWidth() : AndroidUtilities.displaySize.x;
        }
    }

    // ------------------------------------------------------------------ DOCX

    private void showDoc(Context context, ParadoksDocParsers.Doc doc) {
        if (destroyed || root == null) {
            return;
        }
        if (doc.blocks.isEmpty()) {
            fail("Hujjatda matn topilmadi");
            return;
        }
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setPadding(dp(16), dp(8), dp(16), dp(24));
        list.setClipToPadding(false);
        list.setAdapter(new DocAdapter(doc));
        if (doc.truncated) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            TextView note = new TextView(context);
            note.setText("Hujjat juda uzun, dastlabki qismi ko'rsatildi");
            note.setTextSize(13);
            note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            note.setPadding(dp(16), dp(8), dp(16), dp(8));
            box.addView(note);
            box.addView(list, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f));
            setContent(box);
        } else {
            setContent(list);
        }
    }

    private final class DocAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final ParadoksDocParsers.Doc doc;

        DocAdapter(ParadoksDocParsers.Doc doc) {
            this.doc = doc;
        }

        @Override
        public int getItemViewType(int position) {
            return doc.blocks.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == ParadoksDocParsers.Block.TABLE) {
                LinearLayout table = new LinearLayout(context);
                table.setOrientation(LinearLayout.VERTICAL);
                HorizontalScrollView scroll = new HorizontalScrollView(context);
                scroll.setHorizontalScrollBarEnabled(false);
                scroll.addView(table, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                view = scroll;
            } else {
                TextView tv = new TextView(context);
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                tv.setLineSpacing(0, 1.15f);
                tv.setTextIsSelectable(true);
                view = tv;
            }
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(3);
            lp.bottomMargin = dp(3);
            view.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ParadoksDocParsers.Block block = doc.blocks.get(position);
            if (block.type == ParadoksDocParsers.Block.TABLE) {
                HorizontalScrollView scroll = (HorizontalScrollView) holder.itemView;
                LinearLayout table = (LinearLayout) scroll.getChildAt(0);
                table.removeAllViews();
                int cols = 0;
                for (ArrayList<String> row : block.table) {
                    cols = Math.max(cols, row.size());
                }
                int cellWidth = cols <= 3 ? Math.max(dp(90), (AndroidUtilities.displaySize.x - dp(32)) / Math.max(1, cols)) : dp(130);
                for (ArrayList<String> row : block.table) {
                    LinearLayout line = new LinearLayout(scroll.getContext());
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    for (int c = 0; c < cols; c++) {
                        line.addView(makeCell(scroll.getContext(), c < row.size() ? row.get(c) : "", cellWidth, false, false));
                    }
                    table.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                return;
            }
            TextView tv = (TextView) holder.itemView;
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(block.prefix);
            for (ParadoksDocParsers.Run run : block.runs) {
                int start = sb.length();
                sb.append(run.text);
                int end = sb.length();
                if (run.bold) {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (run.italic) {
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (run.underline) {
                    sb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (run.strike) {
                    sb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (block.heading > 0) {
                int[] sizes = {0, 26, 22, 19, 17, 16, 16};
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new AbsoluteSizeSpan(sizes[Math.min(6, block.heading)], true), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setPadding(0, dp(block.heading <= 2 ? 10 : 6), 0, dp(2));
            } else {
                tv.setPadding(0, 0, 0, 0);
            }
            tv.setText(sb);
        }

        @Override
        public int getItemCount() {
            return doc.blocks.size();
        }
    }

    // ------------------------------------------------------------------ cells (tables and spreadsheets)

    private static boolean looksNumeric(String s) {
        if (s == null || s.isEmpty() || s.length() > 30) {
            return false;
        }
        boolean digit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if ("-+.,%: EeTRUFALS/".indexOf(c) < 0) {
                return false;
            }
        }
        return digit;
    }

    private TextView makeCell(Context context, String text, int widthPx, boolean header, boolean rowNumber) {
        TextView tv = new TextView(context);
        tv.setText(text != null ? text : "");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setMaxLines(rowNumber || header ? 1 : 4);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding(dp(6), dp(5), dp(6), dp(5));
        tv.setMinHeight(dp(30));
        boolean right = !header && !rowNumber && looksNumeric(text);
        tv.setGravity((right ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
        if (header || rowNumber) {
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            tv.setGravity(Gravity.CENTER);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        GradientDrawable border = new GradientDrawable();
        border.setStroke(1, Theme.getColor(Theme.key_divider));
        border.setColor(header || rowNumber ? Theme.getColor(Theme.key_windowBackgroundGray) : 0);
        tv.setBackground(border);
        tv.setLayoutParams(new LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT));
        return tv;
    }

    private static String columnName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ spreadsheets

    private LinearLayout sheetBody;
    private HorizontalScrollView gridScroll;
    private RecyclerView gridList;
    private TextView sheetNote;
    private final ArrayList<TextView> tabs = new ArrayList<>();

    private void showSheets(Context context, ArrayList<ParadoksDocParsers.Sheet> sheets) {
        if (destroyed || root == null) {
            return;
        }
        sheetBody = new LinearLayout(context);
        sheetBody.setOrientation(LinearLayout.VERTICAL);

        if (sheets.size() > 1) {
            HorizontalScrollView tabScroll = new HorizontalScrollView(context);
            tabScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout tabRow = new LinearLayout(context);
            tabRow.setOrientation(LinearLayout.HORIZONTAL);
            tabRow.setPadding(dp(8), dp(6), dp(8), dp(6));
            for (int i = 0; i < sheets.size(); i++) {
                final int index = i;
                TextView tab = new TextView(context);
                tab.setText(sheets.get(i).name);
                tab.setTextSize(14);
                tab.setGravity(Gravity.CENTER);
                tab.setPadding(dp(14), dp(8), dp(14), dp(8));
                tab.setMaxLines(1);
                tab.setOnClickListener(v -> selectSheet(context, sheets, index));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                tabRow.addView(tab, lp);
                tabs.add(tab);
            }
            tabScroll.addView(tabRow);
            sheetBody.addView(tabScroll, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        sheetNote = new TextView(context);
        sheetNote.setTextSize(12);
        sheetNote.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sheetNote.setPadding(dp(12), dp(4), dp(12), dp(4));
        sheetNote.setVisibility(View.GONE);
        sheetBody.addView(sheetNote, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        gridScroll = new HorizontalScrollView(context);
        gridScroll.setFillViewport(true);
        gridList = new RecyclerView(context);
        gridList.setLayoutManager(new LinearLayoutManager(context));
        gridScroll.addView(gridList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheetBody.addView(gridScroll, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f));

        setContent(sheetBody);
        selectSheet(context, sheets, 0);
    }

    private void selectSheet(Context context, ArrayList<ParadoksDocParsers.Sheet> sheets, int index) {
        ParadoksDocParsers.Sheet sheet = sheets.get(index);
        for (int i = 0; i < tabs.size(); i++) {
            TextView tab = tabs.get(i);
            boolean active = i == index;
            tab.setTextColor(active ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            tab.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16),
                    active ? Theme.getColor(Theme.key_featuredStickers_addButton) : Theme.getColor(Theme.key_windowBackgroundGray),
                    Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        }
        if (sheet.truncatedRows || sheet.truncatedCols) {
            sheetNote.setText("Faqat dastlabki " + (sheet.truncatedRows ? ParadoksDocParsers.MAX_ROWS + " qator" : "")
                    + (sheet.truncatedRows && sheet.truncatedCols ? " va " : "")
                    + (sheet.truncatedCols ? ParadoksDocParsers.MAX_COLS + " ustun" : "") + " ko'rsatildi");
            sheetNote.setVisibility(View.VISIBLE);
        } else {
            sheetNote.setVisibility(View.GONE);
        }
        int cols = Math.max(1, sheet.cols);
        int[] widths = new int[cols];
        int sample = Math.min(sheet.rows.size(), 300);
        for (int r = 0; r < sample; r++) {
            String[] row = sheet.rows.get(r);
            for (int c = 0; c < row.length && c < cols; c++) {
                if (row[c] != null) {
                    int longest = 0;
                    int line = 0;
                    for (int i = 0; i < row[c].length(); i++) {
                        if (row[c].charAt(i) == '\n') {
                            longest = Math.max(longest, line);
                            line = 0;
                        } else {
                            line++;
                        }
                    }
                    longest = Math.max(longest, line);
                    widths[c] = Math.max(widths[c], longest);
                }
            }
        }
        int total = dp(46);
        for (int c = 0; c < cols; c++) {
            widths[c] = Math.max(dp(56), Math.min(dp(240), widths[c] * dp(8) + dp(20)));
            total += widths[c];
        }
        final int rowNumberWidth = dp(46);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(total, AndroidUtilities.displaySize.x), ViewGroup.LayoutParams.MATCH_PARENT);
        gridList.setLayoutParams(lp);
        gridList.setAdapter(new GridAdapter(sheet, widths, rowNumberWidth));
        gridScroll.scrollTo(0, 0);
    }

    private final class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final ParadoksDocParsers.Sheet sheet;
        private final int[] widths;
        private final int rowNumberWidth;

        GridAdapter(ParadoksDocParsers.Sheet sheet, int[] widths, int rowNumberWidth) {
            this.sheet = sheet;
            this.widths = widths;
            this.rowNumberWidth = rowNumberWidth;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 1 : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(makeCell(context, "", rowNumberWidth, true, false));
            for (int c = 0; c < widths.length; c++) {
                row.addView(makeCell(context, "", widths[c], viewType == 1, false));
            }
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            LinearLayout row = (LinearLayout) holder.itemView;
            if (position == 0) {
                ((TextView) row.getChildAt(0)).setText("");
                for (int c = 0; c < widths.length; c++) {
                    ((TextView) row.getChildAt(c + 1)).setText(columnName(c));
                }
                return;
            }
            String[] data = sheet.rows.get(position - 1);
            TextView number = (TextView) row.getChildAt(0);
            number.setText(String.valueOf(position));
            number.setGravity(Gravity.CENTER);
            for (int c = 0; c < widths.length; c++) {
                TextView cell = (TextView) row.getChildAt(c + 1);
                String text = c < data.length && data[c] != null ? data[c] : "";
                cell.setText(text);
                cell.setGravity((looksNumeric(text) ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
            }
        }

        @Override
        public int getItemCount() {
            return sheet.rows.size() + 1;
        }
    }
}
