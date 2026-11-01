package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.LruCache;
import android.text.InputType;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
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
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    private static final float MAX_ZOOM = 3f;
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private ExecutorService pdfExecutor;
    private LruCache<String, Bitmap> pageCache;
    private int pageCount;
    private float pageRatio = 1.414f;
    private float zoom = 1f;
    private int baseWidth;
    private ZoomScroll pdfScroll;
    private RecyclerView pdfList;
    private TextView pageLabel;

    // search / text viewer / options
    private ParadoksDocParsers.Doc currentDoc;
    private RecyclerView docList;
    private float textScale = 1f;
    private boolean pdfDark;
    private String query = "";
    private final ArrayList<Integer> hits = new ArrayList<>();
    private int hitIndex;
    private LinearLayout searchBar;
    private TextView searchCount;
    private ParadoksTextFormats.Text textData;
    private final ArrayList<String> chunks = new ArrayList<>();
    private final ArrayList<Integer> chunkStarts = new ArrayList<>();
    private RecyclerView textList;
    private boolean monoText;
    private boolean highlightCode;
    private String infoExtra = "";

    // Word pictures
    private ExecutorService imageExecutor;
    private LruCache<String, Bitmap> imageCache;

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
                } else if (id == 3 || id == 4) {
                    changeTextSize(id == 3 ? 1.15f : 1f / 1.15f);
                } else if (id == 5) {
                    copyAll();
                } else if (id == 6) {
                    showInfo();
                } else if (id == 7) {
                    pdfDark = !pdfDark;
                    if (pdfList != null && pdfList.getAdapter() != null) {
                        pdfList.setBackgroundColor(pdfDark ? Color.BLACK : Theme.getColor(Theme.key_windowBackgroundGray));
                        pdfList.getAdapter().notifyDataSetChanged();
                    }
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        if (isSearchable()) {
            ActionBarMenuItem searchItem = menu.addItem(20, R.drawable.outline_header_search).setIsSearchField(true);
            searchItem.setSearchFieldHint("Qidirish");
            searchItem.setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                @Override
                public void onSearchCollapse() {
                    runSearch("");
                }

                @Override
                public void onTextChanged(EditText editText) {
                    runSearch(editText.getText().toString());
                }
            });
        }
        ActionBarMenuItem more = menu.addItem(10, R.drawable.ic_ab_other);
        if (ext.equals("pdf")) {
            more.addSubItem(7, R.drawable.msg_theme, "Tungi rejim (ranglarni almashtirish)");
        } else if (!isSheetFormat()) {
            more.addSubItem(3, R.drawable.msg_zoomin, "Matnni kattalashtirish");
            more.addSubItem(4, R.drawable.msg_zoomout, "Matnni kichiklashtirish");
            more.addSubItem(5, R.drawable.msg_copy, "Hammasini nusxalash");
        }
        more.addSubItem(6, R.drawable.msg_info, "Fayl haqida");
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
        if (imageExecutor != null) {
            imageExecutor.shutdownNow();
        }
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
                    case "docx":
                    case "pptx":
                    case "odt":
                    case "odp":
                    case "rtf":
                    case "doc": {
                        final ParadoksDocParsers.Doc doc;
                        switch (ext) {
                            case "pptx": doc = ParadoksOfficeExtra.readPptx(file); break;
                            case "odt": doc = ParadoksOfficeExtra.readOdt(file); break;
                            case "odp": doc = ParadoksOfficeExtra.readOdp(file); break;
                            case "rtf": doc = ParadoksOfficeExtra.readRtf(file); break;
                            case "doc": doc = ParadoksOfficeExtra.readDoc(file); break;
                            default: doc = ParadoksDocParsers.readDocx(file);
                        }
                        AndroidUtilities.runOnUIThread(() -> showDoc(context, doc));
                        break;
                    }
                    case "xlsx":
                    case "xls":
                    case "ods": {
                        final ArrayList<ParadoksDocParsers.Sheet> sheets = ext.equals("xls") ? ParadoksXlsReader.read(file)
                                : ext.equals("ods") ? ParadoksOfficeExtra.readOds(file) : ParadoksDocParsers.readXlsx(file);
                        AndroidUtilities.runOnUIThread(() -> showSheets(context, sheets));
                        break;
                    }
                    default: {
                        if (!ParadoksTextFormats.isText(ext)) {
                            fail("Bu fayl turi ichki ko'rgichda ochilmaydi");
                            break;
                        }
                        final ParadoksTextFormats.Text data = ParadoksTextFormats.readText(file);
                        if (ParadoksTextFormats.isTable(ext)) {
                            final ArrayList<ParadoksDocParsers.Sheet> one = new ArrayList<>();
                            one.add(ParadoksTextFormats.readCsv(data.text, ext));
                            infoExtra = "Qatorlar: " + one.get(0).rows.size() + "\nUstunlar: " + one.get(0).cols + "\nKodlash: " + data.charset;
                            AndroidUtilities.runOnUIThread(() -> showSheets(context, one));
                        } else {
                            if (ext.equals("json")) {
                                String pretty = ParadoksTextFormats.prettyJson(data.text);
                                if (pretty != null) {
                                    data.text = pretty;
                                }
                            }
                            AndroidUtilities.runOnUIThread(() -> showText(context, data));
                        }
                    }
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
        if (searchBar != null) {
            if (searchBar.getParent() != null) {
                ((ViewGroup) searchBar.getParent()).removeView(searchBar);
            }
            root.addView(searchBar);
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
        pageCache = new LruCache<String, Bitmap>(64 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        baseWidth = root.getWidth() > 0 ? root.getWidth() : AndroidUtilities.displaySize.x;
        pdfList = new RecyclerView(context);
        final LinearLayoutManager manager = new LinearLayoutManager(context);
        pdfList.setLayoutManager(manager);
        pdfList.setPadding(0, dp(4), 0, dp(4));
        pdfList.setClipToPadding(false);
        pdfList.setAdapter(new PdfAdapter());

        pdfScroll = new ZoomScroll(context);
        pdfScroll.setFillViewport(true);
        pdfScroll.addView(pdfList, new FrameLayout.LayoutParams(baseWidth, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout box = new FrameLayout(context);
        box.addView(pdfScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        pageLabel = new TextView(context);
        pageLabel.setTextSize(13);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(16));
        pill.setColor(0xB0000000);
        pageLabel.setBackground(pill);
        pageLabel.setText("1 / " + pageCount);
        pageLabel.setOnClickListener(v -> askPage());
        box.addView(pageLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END, 0, 0, 16, 16));
        pdfList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                int first = manager.findFirstVisibleItemPosition();
                if (first >= 0) {
                    pageLabel.setText((first + 1) + " / " + pageCount);
                }
            }
        });
        setContent(box);
    }

    /** "Go to page" dialog, opened by tapping the page counter. */
    private void askPage() {
        Context context = getParentActivity();
        if (context == null || pageCount <= 0) {
            return;
        }
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 - " + pageCount);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setPadding(dp(24), dp(12), dp(24), dp(12));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Betga o'tish");
        builder.setView(input);
        builder.setPositiveButton("O'tish", (d, w) -> {
            try {
                int page = Integer.parseInt(input.getText().toString().trim());
                page = Math.max(1, Math.min(pageCount, page));
                ((LinearLayoutManager) pdfList.getLayoutManager()).scrollToPositionWithOffset(page - 1, 0);
            } catch (NumberFormatException ignore) {
            }
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    /**
     * Applies a new zoom level: the list is made wider (so pages are redrawn sharper) and the scroll
     * position is adjusted so the point under the fingers stays in place.
     */
    private void commitZoom(float requested, float focusX, float focusY) {
        float newZoom = Math.max(1f, Math.min(MAX_ZOOM, requested));
        if (pdfList == null || pdfScroll == null || Math.abs(newZoom - zoom) < 0.02f) {
            return;
        }
        final float ratio = newZoom / zoom;
        final int offsetY = pdfList.computeVerticalScrollOffset();
        final int scrollX = pdfScroll.getScrollX();
        zoom = newZoom;
        pageCache.evictAll();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pdfList.getLayoutParams();
        lp.width = Math.round(baseWidth * zoom);
        pdfList.setLayoutParams(lp);
        pdfList.getAdapter().notifyDataSetChanged();
        final int targetX = zoom <= 1.001f ? 0 : Math.max(0, Math.round((scrollX + focusX) * ratio - focusX));
        final int deltaY = Math.round((offsetY + focusY) * ratio - focusY) - offsetY;
        final ViewTreeObserver observer = pdfScroll.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver current = pdfScroll.getViewTreeObserver();
                if (current.isAlive()) {
                    current.removeOnGlobalLayoutListener(this);
                }
                pdfScroll.scrollTo(targetX, 0);
                pdfList.scrollBy(0, deltaY);
            }
        });
    }

    /** Horizontal scroller that also understands pinch and double-tap zoom. */
    private final class ZoomScroll extends HorizontalScrollView {
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;
        private float liveScale = 1f;

        ZoomScroll(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    liveScale = 1f;
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    liveScale = Math.max(1f / zoom, Math.min(MAX_ZOOM / zoom, liveScale * detector.getScaleFactor()));
                    pdfList.setPivotX(getScrollX() + detector.getFocusX());
                    pdfList.setPivotY(detector.getFocusY());
                    pdfList.setScaleX(liveScale);
                    pdfList.setScaleY(liveScale);
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector detector) {
                    float finished = liveScale;
                    liveScale = 1f;
                    pdfList.setScaleX(1f);
                    pdfList.setScaleY(1f);
                    commitZoom(zoom * finished, detector.getFocusX(), detector.getFocusY());
                }
            });
            tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    commitZoom(zoom > 1.3f ? 1f : 2.5f, e.getX(), e.getY());
                    return true;
                }
            });
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            tapDetector.onTouchEvent(ev);
            if (scaleDetector.isInProgress()) {
                return true;
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            tapDetector.onTouchEvent(ev);
            if (scaleDetector.isInProgress()) {
                return true;
            }
            return super.onTouchEvent(ev);
        }
    }

    private int pdfWidth() {
        return Math.max(1, Math.round(baseWidth * zoom));
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
            image.setColorFilter(pdfDark ? new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0})) : null);
            image.setBackgroundColor(pdfDark ? Color.BLACK : Color.WHITE);
            image.setTag(position);
            final int width = pdfWidth();
            final int target = Math.min(1600, Math.round(width * 1.25f));
            final String key = position + "@" + target;
            image.setMinimumHeight((int) (width * pageRatio));
            Bitmap cached = pageCache.get(key);
            if (cached != null) {
                image.setImageBitmap(cached);
                image.setMinimumHeight(0);
                return;
            }
            image.setImageDrawable(null);
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
                        pageCache.put(key, result);
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        Object tag = image.getTag();
                        if (!destroyed && result != null && tag instanceof Integer && (Integer) tag == position && pdfWidth() == width) {
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
    }

    // ------------------------------------------------------------------ DOCX


    // ------------------------------------------------------------------ search, text viewer, options

    private boolean isSheetFormat() {
        return ext.equals("xlsx") || ext.equals("xls") || ext.equals("ods") || ParadoksTextFormats.isTable(ext);
    }

    private boolean isSearchable() {
        return !ext.equals("pdf") && !isSheetFormat();
    }

    private static String blockText(ParadoksDocParsers.Block b) {
        StringBuilder sb = new StringBuilder();
        if (b.type == ParadoksDocParsers.Block.TABLE) {
            for (ArrayList<String> row : b.table) {
                for (String c : row) {
                    sb.append(c).append(' ');
                }
            }
        } else if (b.type == ParadoksDocParsers.Block.PARAGRAPH) {
            sb.append(b.prefix);
            for (ParadoksDocParsers.Run r : b.runs) {
                sb.append(r.text);
            }
        }
        return sb.toString();
    }

    private static int countWords(String s) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                in = false;
            } else if (!in) {
                in = true;
                n++;
            }
        }
        return n;
    }

    private int hitBlock() {
        return !hits.isEmpty() && currentDoc != null ? hits.get(hitIndex) : -1;
    }

    /** Paints every match of the query; the current one is brighter. */
    private static void highlight(SpannableStringBuilder sb, String q, boolean current) {
        if (q == null || q.isEmpty()) {
            return;
        }
        int[] found = ParadoksTextFormats.findAll(sb.toString(), q, 200);
        for (int p : found) {
            sb.setSpan(new BackgroundColorSpan(current ? 0xFFFF9800 : 0xFFFFEB3B), p, Math.min(sb.length(), p + q.length()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Color.BLACK), p, Math.min(sb.length(), p + q.length()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void ensureSearchBar(Context context) {
        if (searchBar != null) {
            return;
        }
        searchBar = new LinearLayout(context);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bg.setStroke(dp(1), Theme.getColor(Theme.key_divider));
        bg.setCornerRadius(dp(22));
        searchBar.setBackground(bg);
        searchBar.setElevation(dp(6));
        searchBar.setPadding(dp(14), 0, dp(6), 0);
        searchCount = new TextView(context);
        searchCount.setTextSize(14);
        searchCount.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        searchBar.addView(searchCount, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView up = arrow(context, "▲", -1);
        TextView down = arrow(context, "▼", 1);
        searchBar.addView(up, new LinearLayout.LayoutParams(dp(44), dp(44)));
        searchBar.addView(down, new LinearLayout.LayoutParams(dp(44), dp(44)));
        searchBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 16, 24);
        searchBar.setLayoutParams(lp);
        root.addView(searchBar);
    }

    private TextView arrow(Context context, String glyph, int step) {
        TextView tv = new TextView(context);
        tv.setText(glyph);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        tv.setOnClickListener(v -> {
            if (!hits.isEmpty()) {
                hitIndex = (hitIndex + step + hits.size()) % hits.size();
                goToHit();
            }
        });
        return tv;
    }

    private void runSearch(String q) {
        query = q == null ? "" : q.trim();
        hits.clear();
        hitIndex = 0;
        if (root == null) {
            return;
        }
        ensureSearchBar(root.getContext());
        if (query.length() < 1) {
            searchBar.setVisibility(View.GONE);
            refreshLists();
            return;
        }
        if (currentDoc != null) {
            for (int i = 0; i < currentDoc.blocks.size() && hits.size() < 5000; i++) {
                int[] f = ParadoksTextFormats.findAll(blockText(currentDoc.blocks.get(i)), query, 50);
                for (int k = 0; k < f.length; k++) {
                    hits.add(i);
                }
            }
        } else if (textData != null) {
            int[] f = ParadoksTextFormats.findAll(textData.text, query, 5000);
            for (int pos : f) {
                int lo = 0;
                int hi = chunkStarts.size() - 1;
                while (lo < hi) {
                    int mid = (lo + hi + 1) >>> 1;
                    if (chunkStarts.get(mid) <= pos) {
                        lo = mid;
                    } else {
                        hi = mid - 1;
                    }
                }
                hits.add(lo);
            }
        }
        searchBar.setVisibility(View.VISIBLE);
        refreshLists();
        goToHit();
    }

    private void refreshLists() {
        if (docList != null && docList.getAdapter() != null) {
            docList.getAdapter().notifyDataSetChanged();
        }
        if (textList != null && textList.getAdapter() != null) {
            textList.getAdapter().notifyDataSetChanged();
        }
    }

    private void goToHit() {
        if (searchCount == null) {
            return;
        }
        if (hits.isEmpty()) {
            searchCount.setText("Topilmadi");
            return;
        }
        searchCount.setText((hitIndex + 1) + " / " + hits.size());
        RecyclerView target = currentDoc != null ? docList : textList;
        if (target != null && target.getLayoutManager() instanceof LinearLayoutManager) {
            ((LinearLayoutManager) target.getLayoutManager()).scrollToPositionWithOffset(hits.get(hitIndex), dp(80));
            if (currentDoc != null && target.getAdapter() != null) {
                target.getAdapter().notifyDataSetChanged();
            }
        }
    }

    private void changeTextSize(float factor) {
        textScale = Math.max(0.7f, Math.min(2.2f, textScale * factor));
        refreshLists();
    }

    private void copyAll() {
        String text = null;
        if (currentDoc != null) {
            StringBuilder sb = new StringBuilder();
            for (ParadoksDocParsers.Block b : currentDoc.blocks) {
                String t = blockText(b);
                if (!t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
            text = sb.toString();
        } else if (textData != null) {
            text = textData.text;
        }
        if (text != null && !text.isEmpty() && AndroidUtilities.addToClipboard(text)) {
            Toast.makeText(getParentActivity(), "Nusxalandi", Toast.LENGTH_SHORT).show();
        }
    }

    private void showInfo() {
        if (getParentActivity() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Nomi: ").append(displayName).append("\nTuri: ").append(ext.toUpperCase(Locale.US));
        sb.append("\nHajmi: ").append(formatSize(file.length()));
        if (ext.equals("pdf")) {
            sb.append("\nSahifalar: ").append(pageCount);
        }
        if (textData != null) {
            sb.append("\nTurkum: ").append(ParadoksTextFormats.category(ext));
            sb.append("\nQatorlar: ").append(textData.lines).append("\nSo'zlar: ").append(textData.words);
            sb.append("\nO'qish vaqti: ~").append(ParadoksTextFormats.readingMinutes(textData.words)).append(" daqiqa");
            sb.append("\nKodlash: ").append(textData.charset);
        } else if (!infoExtra.isEmpty()) {
            sb.append('\n').append(infoExtra);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Fayl haqida");
        builder.setMessage(sb.toString());
        builder.setPositiveButton("OK", null);
        showDialog(builder.create());
    }

    private static final java.util.HashSet<String> KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "if", "else", "for", "while", "do", "return", "class", "interface", "enum", "struct", "function", "def", "fun", "val", "var", "let", "const",
            "import", "from", "package", "public", "private", "protected", "static", "final", "void", "int", "long", "float", "double", "boolean", "bool",
            "string", "new", "null", "nil", "None", "true", "false", "True", "False", "try", "catch", "except", "finally", "throw", "throws", "switch",
            "case", "break", "continue", "in", "is", "not", "and", "or", "async", "await", "extends", "implements", "override", "this", "self", "super",
            "select", "insert", "update", "delete", "where", "create", "table", "echo", "fi", "then", "elif", "use", "mod", "pub", "impl", "type", "namespace"));

    private void showText(Context context, ParadoksTextFormats.Text data) {
        if (destroyed || root == null) {
            return;
        }
        if (data.text.trim().isEmpty()) {
            fail("Fayl bo'sh");
            return;
        }
        textData = data;
        monoText = !(ext.equals("txt") || ext.equals("md") || ext.equals("markdown") || ext.equals("rst") || ext.equals("tex"));
        highlightCode = ParadoksTextFormats.category(ext).equals("Kod") || ext.equals("json") || ext.equals("xml") || ext.equals("html") || ext.equals("htm");
        // split into chunks of 25 lines, so a huge file is never one giant view
        String text = data.text;
        int start = 0;
        int lines = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                lines++;
                if (lines == 25 || i == text.length()) {
                    chunkStarts.add(start);
                    chunks.add(text.substring(start, Math.min(text.length(), i)));
                    start = i + 1;
                    lines = 0;
                }
            }
        }
        actionBar.setSubtitle(ParadoksTextFormats.category(ext) + " · " + formatSize(file.length()) + " · " + data.lines + " qator · " + data.words + " so'z");
        textList = new RecyclerView(context);
        textList.setLayoutManager(new LinearLayoutManager(context));
        textList.setPadding(dp(14), dp(8), dp(14), dp(24));
        textList.setClipToPadding(false);
        textList.setAdapter(new TextAdapter());
        if (data.truncated) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            TextView note = new TextView(context);
            note.setText("Fayl katta, dastlabki 5 MB ko'rsatildi");
            note.setTextSize(13);
            note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            note.setPadding(dp(16), dp(8), dp(16), dp(8));
            box.addView(note);
            box.addView(textList, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f));
            setContent(box);
        } else {
            setContent(textList);
        }
    }

    private SpannableStringBuilder styleCode(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int n = text.length();
        int i = 0;
        final boolean hash = ext.equals("py") || ext.equals("sh") || ext.equals("yaml") || ext.equals("yml") || ext.equals("toml")
                || ext.equals("rb") || ext.equals("ini") || ext.equals("conf") || ext.equals("properties") || ext.equals("env") || ext.equals("gitignore");
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && text.charAt(j) != c && text.charAt(j) != '\n') {
                    if (text.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = Math.min(n, j + 1);
                sb.setSpan(new ForegroundColorSpan(0xFF2E9E5B), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
            } else if ((c == '/' && i + 1 < n && text.charAt(i + 1) == '/') || (hash && c == '#') || (c == '-' && i + 1 < n && text.charAt(i + 1) == '-' && ext.equals("sql"))) {
                int j = text.indexOf('\n', i);
                j = j < 0 ? n : j;
                sb.setSpan(new ForegroundColorSpan(0xFF8A8F98), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
            } else if (Character.isDigit(c) && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '.')) {
                    j++;
                }
                sb.setSpan(new ForegroundColorSpan(0xFFE08A1E), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                    j++;
                }
                if (KEYWORDS.contains(text.substring(i, j))) {
                    sb.setSpan(new ForegroundColorSpan(0xFF7C5CE6), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                i = j;
            } else {
                i++;
            }
        }
        return sb;
    }

    private final class TextAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            tv.setLineSpacing(0, 1.12f);
            tv.setTypeface(monoText ? Typeface.MONOSPACE : Typeface.DEFAULT);
            tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(tv) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TextView tv = (TextView) holder.itemView;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (monoText ? 13 : 16) * textScale);
            String chunk = chunks.get(position);
            SpannableStringBuilder sb = highlightCode ? styleCode(chunk) : new SpannableStringBuilder(chunk);
            highlight(sb, query, !hits.isEmpty() && hits.get(hitIndex) == position);
            tv.setText(sb);
            tv.setOnLongClickListener(v -> {
                AndroidUtilities.addToClipboard(chunks.get(holder.getAdapterPosition()));
                Toast.makeText(v.getContext(), "Bu qism nusxalandi", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return chunks.size();
        }
    }

    private void showDoc(Context context, ParadoksDocParsers.Doc doc) {
        if (destroyed || root == null) {
            return;
        }
        if (doc.blocks.isEmpty()) {
            fail("Hujjatda matn topilmadi");
            return;
        }
        currentDoc = doc;
        int words = 0;
        for (ParadoksDocParsers.Block b : doc.blocks) {
            words += countWords(blockText(b));
        }
        String sub = formatSize(file.length()) + " · " + words + " so'z · ~" + ParadoksTextFormats.readingMinutes(words) + " daq";
        if (ext.equals("pptx") || ext.equals("odp")) {
            int slides = 0;
            for (ParadoksDocParsers.Block b : doc.blocks) {
                if (b.heading == 1 && !b.runs.isEmpty() && b.runs.get(0).text.startsWith("Slayd ")) {
                    slides++;
                }
            }
            sub = formatSize(file.length()) + " · " + slides + " slayd · " + words + " so'z";
        }
        actionBar.setSubtitle(sub);
        infoExtra = "So'zlar: " + words + "\nO'qish vaqti: ~" + ParadoksTextFormats.readingMinutes(words) + " daqiqa";
        RecyclerView list = new RecyclerView(context);
        docList = list;
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
            if (viewType == ParadoksDocParsers.Block.IMAGE) {
                ImageView picture = new ImageView(context);
                picture.setAdjustViewBounds(true);
                picture.setScaleType(ImageView.ScaleType.FIT_START);
                view = picture;
            } else if (viewType == ParadoksDocParsers.Block.TABLE) {
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
            if (block.type == ParadoksDocParsers.Block.IMAGE) {
                loadDocImage((ImageView) holder.itemView, block.imagePath);
                return;
            }
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
                        final String cellText = c < row.size() ? row.get(c) : "";
                        TextView cell = makeCell(scroll.getContext(), cellText, cellWidth, false, false);
                        if (!cellText.isEmpty()) {
                            cell.setOnClickListener(v -> showCellText("Jadval katagi", cellText));
                        }
                        line.addView(cell);
                    }
                    table.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                return;
            }
            TextView tv = (TextView) holder.itemView;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 * textScale);
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
                sb.setSpan(new AbsoluteSizeSpan(Math.round(sizes[Math.min(6, block.heading)] * textScale), true), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setPadding(0, dp(block.heading <= 2 ? 10 : 6), 0, dp(2));
            } else {
                tv.setPadding(0, 0, 0, 0);
            }
            highlight(sb, query, position == hitBlock());
            tv.setText(sb);
        }

        @Override
        public int getItemCount() {
            return doc.blocks.size();
        }
    }

    // ------------------------------------------------------------------ pictures and full cell text

    private void loadDocImage(final ImageView image, final String path) {
        if (imageExecutor == null) {
            imageExecutor = Executors.newFixedThreadPool(2);
            imageCache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
        }
        image.setTag(path);
        Bitmap cached = imageCache.get(path);
        if (cached != null) {
            image.setImageBitmap(cached);
            image.setMinimumHeight(0);
            return;
        }
        image.setImageDrawable(null);
        image.setMinimumHeight(dp(120));
        try {
            imageExecutor.execute(() -> {
                if (destroyed) {
                    return;
                }
                final Bitmap bitmap = decodeDocImage(path);
                if (bitmap != null) {
                    imageCache.put(path, bitmap);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed || !path.equals(image.getTag())) {
                        return;
                    }
                    image.setMinimumHeight(0);
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap);
                    }
                });
            });
        } catch (RejectedExecutionException ignore) {
        }
    }

    /** Reads a picture from inside the .docx archive, scaled down so a huge image cannot exhaust memory. */
    private Bitmap decodeDocImage(String path) {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.getSize() > 25L * 1024 * 1024) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = zip.getInputStream(entry)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 1600) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream in = zip.getInputStream(entry)) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** Long cell text is cut to a few lines in the grid; tapping the cell shows all of it and can copy it. */
    private void showCellText(String title, String text) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setMessage(text);
        builder.setPositiveButton("Nusxa olish", (d, w) -> {
            AndroidUtilities.addToClipboard(text);
            Toast.makeText(context, "Nusxa olindi", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Yopish", null);
        showDialog(builder.create());
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
                final String text = c < data.length && data[c] != null ? data[c] : "";
                cell.setText(text);
                cell.setGravity((looksNumeric(text) ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
                if (text.isEmpty()) {
                    cell.setOnClickListener(null);
                } else {
                    final String title = columnName(c) + position;
                    cell.setOnClickListener(v -> showCellText(title, text));
                }
            }
        }

        @Override
        public int getItemCount() {
            return sheet.rows.size() + 1;
        }
    }
}
