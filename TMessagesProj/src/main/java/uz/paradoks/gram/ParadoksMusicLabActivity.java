package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Musiqiy ijodxona: native Android port of the "Music Vibe Lab" web app. Everything runs locally
 * (no Mini App / WebView): playback with live effects, spectrum visualizer, A-B loop, BPM,
 * background image / cover, export of the processed sound (WAV / M4A) and of a visualizer video (MP4).
 */
public class ParadoksMusicLabActivity extends BaseFragment {

    private static final int REQ_AUDIO = 7781;
    private static final int REQ_IMAGE = 7782;
    private static final int BLOCK = ParadoksMusicDsp.Spectrum.SIZE;

    // palette taken from the web version
    private static final int C_BG = 0xFF0B0B14, C_CARD = 0xFF14141F, C_TX = 0xFFECEAF5, C_MUT = 0xFF8B89A0, C_AC = 0xFFA855F7, C_BD = 0xFF262638;
    private static final int[] SWATCHES = {0xFF7C3AED, 0xFF22D3EE, 0xFFF43F5E, 0xFFFBBF24, 0xFF0EA5E9, 0xFF34D399, 0xFFDC2626, 0xFFFACC15, 0xFFFFFFFF, 0xFFEC4899};

    private interface BoolCb {
        void on(boolean value);
    }

    private interface IntCb {
        void on(int value);
    }

    private static final class Track {
        final String path;
        final String name;
        final boolean temp;

        Track(String path, String name, boolean temp) {
            this.path = path;
            this.name = name;
            this.temp = temp;
        }
    }

    // ---- state
    private final ArrayList<Track> list = new ArrayList<>();
    private int cur = -1;
    private final String initialPath, initialName;
    private final float initialFraction;
    private final ParadoksMusicDsp.Params params = new ParadoksMusicDsp.Params();
    private final ParadoksVizRenderer.Settings viz = new ParadoksVizRenderer.Settings();
    private final ParadoksMusicDsp.Spectrum spectrum = new ParadoksMusicDsp.Spectrum();
    private SharedPreferences prefs;

    private volatile ParadoksMusicDsp.Engine engine;
    private volatile ParadoksMusicDsp.Pcm pcm;
    private volatile boolean playing, released, flushRequest, loop, abOn;
    private volatile float volume = 0.8f, markA, markB;
    private volatile int loadToken;
    private Thread audioThread;
    private boolean telegramStopped, seeking, exporting, videoExporting, cancelExport;
    private Uri lastSavedUri;
    private int format; // 0 = WAV, 1 = M4A (AAC)
    private int bpmValue;

    // ---- views
    private Context context;
    private LinearLayout content;
    private VizView vizView;
    private TextView fileName, playButton, timeText, bpmText, statusText, exportButton, videoButton, shareButton, bgClear;
    private TextView chipSlow, chipNight, chipRainbow;
    private SeekBar seekBar;
    private LinearLayout playlistBox;
    private final ArrayList<TextView> styleButtons = new ArrayList<>();
    private final ArrayList<TextView> formatButtons = new ArrayList<>();
    private boolean bigViz;

    public ParadoksMusicLabActivity() {
        this(null, null, 0f);
    }

    public ParadoksMusicLabActivity(String path, String name) {
        this(path, name, 0f);
    }

    public ParadoksMusicLabActivity(String path, String name, float startFraction) {
        this.initialPath = path;
        this.initialName = name;
        this.initialFraction = startFraction;
    }

    // ------------------------------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onFragmentCreate() {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("paradoks_musiclab", Context.MODE_PRIVATE);
        loadPrefs();
        if (initialPath != null) {
            File f = new File(initialPath);
            if (f.exists()) {
                list.add(new Track(initialPath, initialName != null && initialName.length() > 0 ? initialName : f.getName(), false));
            }
        }
        released = false;
        audioThread = new Thread(this::audioLoop, "ParadoksMusicLab");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        released = true;
        playing = false;
        loadToken++;
        cancelExport = true;
        AndroidUtilities.cancelRunOnUIThread(ticker);
        if (audioThread != null) {
            audioThread.interrupt();
        }
        for (Track t : list) {
            if (t.temp) {
                //noinspection ResultOfMethodCallIgnored
                new File(t.path).delete();
            }
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onPause() {
        super.onPause();
        savePrefs();
    }

    @Override
    public View createView(Context context) {
        this.context = context;
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setBackgroundColor(C_BG);
        actionBar.setTitleColor(C_TX);
        actionBar.setItemsColor(C_TX, false);
        actionBar.setItemsBackgroundColor(0x22808080, false);
        actionBar.setTitle("Musiqiy ijodxona");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(C_BG);
        fragmentView = root;

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(12), dp(12), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildPickCard();
        buildVizCard();
        buildPlayerCard();
        buildEffectsCard();
        buildExportCard();
        buildVisualCard();

        TextView footer = new TextView(context);
        footer.setText("Music Vibe Lab — ParadoksGram ichida, lokal ishlaydi");
        footer.setTextColor(C_MUT);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(14), 0, 0);
        content.addView(footer);

        refreshPlaylist();
        updatePlayButton();
        AndroidUtilities.runOnUIThread(ticker, 200);
        if (!list.isEmpty() && cur < 0) {
            loadTrack(0, true, initialFraction);
        }
        return fragmentView;
    }

    // ------------------------------------------------------------------------------------------
    // UI builders
    // ------------------------------------------------------------------------------------------

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            d.setStroke(Math.max(1, dp(1)), strokeColor);
        }
        return d;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(context);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(C_CARD, 16, C_BD));
        c.setPadding(dp(14), dp(14), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        content.addView(c, lp);
        return c;
    }

    private TextView button(String text, View.OnClickListener l) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(C_TX);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(42));
        t.setPadding(dp(13), dp(9), dp(13), dp(9));
        t.setBackground(round(C_BD, 10, 0));
        t.setOnClickListener(l);
        return t;
    }

    private void setActive(TextView t, boolean active) {
        t.setBackground(round(active ? C_AC : C_BD, t.getTag() instanceof Boolean ? 20 : 10, 0));
        t.setTextColor(active ? 0xFFFFFFFF : C_TX);
    }

    private TextView chip(String text, boolean checked, BoolCb cb) {
        final TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(42));
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setTag(Boolean.valueOf(checked));
        setActive(t, checked);
        t.setOnClickListener(v -> {
            boolean on = !(Boolean) t.getTag();
            t.setTag(Boolean.valueOf(on));
            setActive(t, on);
            cb.on(on);
            savePrefs();
        });
        return t;
    }

    private void setChip(TextView t, boolean on) {
        t.setTag(Boolean.valueOf(on));
        setActive(t, on);
    }

    private Flow flow() {
        Flow f = new Flow(context, dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        f.setLayoutParams(lp);
        return f;
    }

    private void tint(SeekBar s) {
        s.getProgressDrawable().setColorFilter(C_AC, PorterDuff.Mode.SRC_IN);
        if (s.getThumb() != null) {
            s.getThumb().setColorFilter(C_AC, PorterDuff.Mode.SRC_IN);
        }
    }

    /** Labelled slider; the value is min..max (integers, the caller scales them). */
    private View slider(String label, int min, int max, int value, IntCb cb) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(4));
        final TextView head = new TextView(context);
        head.setTextColor(C_MUT);
        head.setTextSize(13);
        head.setText(label + ": " + value);
        box.addView(head);
        SeekBar bar = new SeekBar(context);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        tint(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = progress + min;
                head.setText(label + ": " + v);
                if (fromUser) {
                    cb.on(v);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                savePrefs();
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        return box;
    }

    private void buildPickCard() {
        LinearLayout c = card();
        TextView t = new TextView(context);
        t.setText("🎵 Musiqa(lar)ni tanlash");
        t.setTextColor(C_MUT);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(18), 0, dp(10));
        t.setBackground(round(0, 12, C_BD));
        t.setOnClickListener(v -> pickAudio());
        c.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fileName = new TextView(context);
        fileName.setTextColor(C_TX);
        fileName.setTextSize(14);
        fileName.setPadding(0, dp(8), 0, 0);
        fileName.setSingleLine(true);
        fileName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.addView(fileName);
    }

    private void buildVizCard() {
        LinearLayout c = card();
        vizView = new VizView(context);
        c.addView(vizView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
    }

    private void buildPlayerCard() {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = button("⏮", v -> {
            if (cur > 0) loadTrack(cur - 1, true, 0f);
        });
        playButton = button("▶ Play", v -> togglePlay());
        TextView next = button("⏭", v -> {
            if (cur >= 0 && cur < list.size() - 1) loadTrack(cur + 1, true, 0f);
        });
        row.addView(prev, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        pl.leftMargin = pl.rightMargin = dp(8);
        row.addView(playButton, pl);
        row.addView(next, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(row);

        seekBar = new SeekBar(context);
        seekBar.setMax(1000);
        tint(seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                ParadoksMusicDsp.Engine e = engine;
                if (fromUser && e != null) {
                    e.pos = progress / 1000.0 * (e.pcm.frames - 1);
                    flushRequest = true;
                    updateTime();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        sl.topMargin = dp(6);
        c.addView(seekBar, sl);
        timeText = new TextView(context);
        timeText.setTextColor(C_MUT);
        timeText.setTextSize(13);
        timeText.setText("0:00 / 0:00");
        c.addView(timeText);

        c.addView(slider("🔊 Ovoz", 0, 100, Math.round(volume * 100), v -> volume = v / 100f));

        Flow f = flow();
        f.addView(chip("🔁 Loop", loop, on -> loop = on));
        f.addView(button("A", v -> {
            ParadoksMusicDsp.Engine e = engine;
            if (e == null) return;
            markA = (float) (e.pos / e.pcm.sampleRate);
            abOn = markB > markA;
            status("A: " + fmt(markA));
        }));
        f.addView(button("B", v -> {
            ParadoksMusicDsp.Engine e = engine;
            if (e == null) return;
            markB = (float) (e.pos / e.pcm.sampleRate);
            abOn = markB > markA;
            status(abOn ? "A–B takror: " + fmt(markA) + " → " + fmt(markB) : "B nuqta A dan keyin bo'lsin");
        }));
        f.addView(button("✕", v -> {
            abOn = false;
            markA = markB = 0;
            status("A–B o'chirildi");
        }));
        bpmText = new TextView(context);
        bpmText.setTextColor(C_MUT);
        bpmText.setTextSize(13);
        bpmText.setGravity(Gravity.CENTER_VERTICAL);
        bpmText.setMinHeight(dp(42));
        f.addView(bpmText);
        c.addView(f);

        playlistBox = new LinearLayout(context);
        playlistBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(playlistBox);
    }

    private void buildEffectsCard() {
        LinearLayout c = card();
        Flow f = flow();
        f.addView(chip("🎤 Karaoke", params.karaoke, on -> params.karaoke = on));
        f.addView(chip("Bass boost", params.bass, on -> params.bass = on));
        f.addView(chip("Equalizer", params.eq, on -> params.eq = on));
        f.addView(chip("8D", params.d8, on -> params.d8 = on));
        f.addView(chip("Echo", params.echo, on -> params.echo = on));
        f.addView(chip("Reverb", params.reverb, on -> params.reverb = on));
        chipSlow = chip("Slow", params.slow, on -> {
            params.slow = on;
            if (on) {
                params.nightcore = false;
                setChip(chipNight, false);
            }
            onSpeedChanged();
        });
        chipNight = chip("Nightcore", params.nightcore, on -> {
            params.nightcore = on;
            if (on) {
                params.slow = false;
                setChip(chipSlow, false);
            }
            onSpeedChanged();
        });
        f.addView(chipSlow);
        f.addView(chipNight);
        c.addView(f);

        c.addView(slider("Bass dB", 0, 18, Math.round(params.bassDb), v -> params.bassDb = v));
        c.addView(slider("8D tezlik (x0.01 Hz)", 5, 60, Math.round(params.d8Speed * 100), v -> params.d8Speed = v / 100f));
        c.addView(slider("Echo vaqti (x0.01 s)", 10, 100, Math.round(params.echoTime * 100), v -> params.echoTime = v / 100f));
        c.addView(slider("Reverb (%)", 0, 100, Math.round(params.reverbMix * 100), v -> params.reverbMix = v / 100f));
        for (int i = 0; i < 5; i++) {
            final int band = i;
            String hz = ParadoksMusicDsp.EQ_FREQ[i] >= 1000 ? (ParadoksMusicDsp.EQ_FREQ[i] / 1000) + "k" : String.valueOf(ParadoksMusicDsp.EQ_FREQ[i]);
            c.addView(slider("EQ " + hz + " Hz (dB)", -12, 12, Math.round(params.eqGain[i]), v -> params.eqGain[band] = v));
        }
    }

    private void buildExportCard() {
        LinearLayout c = card();
        Flow f = flow();
        formatButtons.clear();
        String[] names = {"WAV", "M4A (AAC)"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView b = button(names[i], null);
            b.setOnClickListener(v -> {
                format = idx;
                updateFormatButtons();
                savePrefs();
            });
            formatButtons.add(b);
            f.addView(b);
        }
        updateFormatButtons();
        exportButton = button("⬇ Yuklab olish", v -> exportAudio());
        exportButton.setBackground(round(C_AC, 10, 0));
        exportButton.setTextColor(0xFFFFFFFF);
        f.addView(exportButton);
        videoButton = button("🎬 Video yaratish", v -> exportVideo());
        f.addView(videoButton);
        shareButton = button("📤 Ulashish", v -> shareLast());
        shareButton.setVisibility(View.GONE);
        f.addView(shareButton);
        c.addView(f);
        statusText = new TextView(context);
        statusText.setTextColor(C_MUT);
        statusText.setTextSize(13);
        statusText.setMinHeight(dp(18));
        c.addView(statusText);
    }

    private void updateFormatButtons() {
        for (int i = 0; i < formatButtons.size(); i++) {
            setActive(formatButtons.get(i), i == format);
        }
    }

    private void buildVisualCard() {
        LinearLayout c = card();
        swatchViews.clear();

        Flow presets = flow();
        String[][] pre = {{"Neon", "#7C3AED", "#22D3EE"}, {"Sunset", "#F43F5E", "#FBBF24"}, {"Ocean", "#0EA5E9", "#34D399"}, {"Fire", "#DC2626", "#FACC15"}};
        for (String[] p : pre) {
            presets.addView(button(p[0], v -> {
                viz.color1 = android.graphics.Color.parseColor(p[1]);
                viz.color2 = android.graphics.Color.parseColor(p[2]);
                viz.rainbow = false;
                setChip(chipRainbow, false);
                savePrefs();
                rebuildVisualCardColors();
            }));
        }
        chipRainbow = chip("🌈 Rainbow", viz.rainbow, on -> viz.rainbow = on);
        presets.addView(chipRainbow);
        c.addView(presets);

        c.addView(label("Past rangi"));
        c.addView(swatches(true));
        c.addView(label("Yuqori rangi"));
        c.addView(swatches(false));

        Flow styles = flow();
        styleButtons.clear();
        for (int i = 0; i < ParadoksVizRenderer.STYLE_NAMES.length; i++) {
            final int idx = i;
            TextView b = button(ParadoksVizRenderer.STYLE_NAMES[i], null);
            b.setOnClickListener(v -> {
                viz.style = idx;
                updateStyleButtons();
                savePrefs();
            });
            styleButtons.add(b);
            styles.addView(b);
        }
        updateStyleButtons();
        c.addView(styles);

        c.addView(slider("Ustunlar", 16, 128, viz.bars, v -> viz.bars = v));
        c.addView(slider("Bo'yi (%)", 20, 200, viz.height, v -> viz.height = v));

        Flow fx = flow();
        fx.addView(chip("Glow", viz.glow, on -> viz.glow = on));
        fx.addView(chip("Pulsatsiya", viz.pulse, on -> viz.pulse = on));
        fx.addView(chip("Zarrachalar", viz.particles, on -> viz.particles = on));
        fx.addView(button("⛶ Katta / kichik", v -> {
            bigViz = !bigViz;
            ViewGroup.LayoutParams lp = vizView.getLayoutParams();
            lp.height = bigViz ? Math.max(dp(320), (int) (AndroidUtilities.displaySize.y * 0.6f)) : dp(220);
            vizView.setLayoutParams(lp);
        }));
        c.addView(fx);

        Flow bg = flow();
        bg.addView(button("🖼 Fon rasm", v -> pickImage()));
        bg.addView(button("🎵 Thumbnail", v -> useThumbnail()));
        bgClear = button("✕ Fon", v -> {
            viz.background = null;
            bgClear.setAlpha(0.4f);
        });
        bgClear.setAlpha(viz.background != null ? 1f : 0.4f);
        bg.addView(bgClear);
        c.addView(bg);
        c.addView(slider("⬅➡ Fon joyi", -100, 100, viz.bgX, v -> viz.bgX = v));
        c.addView(slider("⬆⬇ Fon joyi", -100, 100, viz.bgY, v -> viz.bgY = v));
    }

    private View label(String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(C_MUT);
        t.setTextSize(13);
        t.setPadding(0, dp(4), 0, dp(2));
        return t;
    }

    private final ArrayList<View[]> swatchViews = new ArrayList<>();

    private View swatches(boolean bottom) {
        Flow f = flow();
        final View[] views = new View[SWATCHES.length];
        for (int i = 0; i < SWATCHES.length; i++) {
            final int color = SWATCHES[i];
            View v = new View(context);
            v.setOnClickListener(x -> {
                if (bottom) viz.color1 = color; else viz.color2 = color;
                viz.rainbow = false;
                setChip(chipRainbow, false);
                savePrefs();
                rebuildVisualCardColors();
            });
            views[i] = v;
            f.addView(v, new ViewGroup.LayoutParams(dp(34), dp(34)));
        }
        swatchViews.add(views);
        swatchViews.add(null); // keeps the "bottom?" parity: even index = bottom row
        refreshSwatches();
        return f;
    }

    private void refreshSwatches() {
        for (int row = 0; row < swatchViews.size(); row += 2) {
            View[] views = swatchViews.get(row);
            if (views == null) continue;
            boolean bottom = row == 0;
            int selected = bottom ? viz.color1 : viz.color2;
            for (int i = 0; i < views.length; i++) {
                views[i].setBackground(round(SWATCHES[i], 17, SWATCHES[i] == selected ? 0xFFFFFFFF : C_BD));
            }
        }
    }

    private void rebuildVisualCardColors() {
        refreshSwatches();
    }

    private void updateStyleButtons() {
        for (int i = 0; i < styleButtons.size(); i++) {
            setActive(styleButtons.get(i), i == viz.style);
        }
    }

    // ------------------------------------------------------------------------------------------
    // file / image picking
    // ------------------------------------------------------------------------------------------

    private void pickAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_AUDIO);
        } catch (Throwable e) {
            toast("Fayl tanlash ochilmadi");
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(intent, "Rasm tanlang"), REQ_IMAGE);
        } catch (Throwable e) {
            toast("Rasm tanlash ochilmadi");
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (requestCode == REQ_AUDIO) {
            status("Fayllar nusxalanmoqda...");
            new Thread(() -> {
                final ArrayList<Track> added = new ArrayList<>();
                for (Uri uri : uris) {
                    Track t = copyToCache(uri);
                    if (t != null) added.add(t);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (released) return;
                    if (added.isEmpty()) {
                        status("Audio fayl tanlang.");
                        return;
                    }
                    status("");
                    boolean first = list.isEmpty();
                    list.addAll(added);
                    refreshPlaylist();
                    if (first) loadTrack(0, true, 0f);
                });
            }, "ParadoksMusicLabCopy").start();
        } else if (requestCode == REQ_IMAGE && !uris.isEmpty()) {
            final Uri uri = uris.get(0);
            new Thread(() -> {
                try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                    byte[] bytes = readAll(in);
                    Bitmap bmp = decodeScaled(bytes);
                    AndroidUtilities.runOnUIThread(() -> setBackground(bmp));
                } catch (Throwable e) {
                    AndroidUtilities.runOnUIThread(() -> status("Rasmni ochib bo'lmadi."));
                }
            }, "ParadoksMusicLabImage").start();
        }
    }

    private Track copyToCache(Uri uri) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            String name = "audio";
            try (Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) name = cursor.getString(index);
                }
            }
            File dir = new File(AndroidUtilities.getCacheDir(), "musiclab");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, System.currentTimeMillis() + "_" + name.replaceAll("[^\\p{L}\\p{N}._ -]", "_"));
            try (InputStream in = ctx.getContentResolver().openInputStream(uri); FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return new Track(out.getAbsolutePath(), name, true);
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static Bitmap decodeScaled(byte[] bytes) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 1280) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    private void setBackground(Bitmap bmp) {
        if (bmp == null) {
            status("Rasm topilmadi.");
            return;
        }
        viz.background = bmp;
        if (bgClear != null) bgClear.setAlpha(1f);
    }

    private void useThumbnail() {
        if (cur < 0 || cur >= list.size()) {
            status("Avval musiqa tanlang.");
            return;
        }
        final String path = list.get(cur).path;
        status("Thumbnail qidirilmoqda...");
        new Thread(() -> {
            Bitmap bmp = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(path);
                byte[] pic = r.getEmbeddedPicture();
                if (pic != null) bmp = decodeScaled(pic);
            } catch (Throwable ignore) {
            } finally {
                try {
                    r.release();
                } catch (Throwable ignore) {
                }
            }
            final Bitmap result = bmp;
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null) {
                    setBackground(result);
                    status("Thumbnail topildi ✅");
                } else {
                    status("Bu fayldan rasm topilmadi — \"Fon rasm\" orqali o'zingiz yuklang.");
                }
            });
        }, "ParadoksMusicLabThumb").start();
    }

    // ------------------------------------------------------------------------------------------
    // playlist / playback control
    // ------------------------------------------------------------------------------------------

    private void refreshPlaylist() {
        if (playlistBox == null) return;
        playlistBox.removeAllViews();
        if (list.size() >= 2) {
            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                TextView b = button((i + 1) + ". " + list.get(i).name, v -> loadTrack(idx, true, 0f));
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setTextSize(13);
                b.setSingleLine(true);
                b.setEllipsize(android.text.TextUtils.TruncateAt.END);
                setActive(b, i == cur);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(4);
                playlistBox.addView(b, lp);
            }
        }
        if (fileName != null) {
            fileName.setText(cur >= 0 && cur < list.size() ? list.get(cur).name : "");
        }
    }

    private void loadTrack(int index, boolean autoplay, float startFraction) {
        if (index < 0 || index >= list.size()) return;
        cur = index;
        final Track track = list.get(index);
        playing = false;
        engine = null;
        pcm = null;
        abOn = false;
        markA = markB = 0;
        bpmValue = 0;
        bpmText.setText("");
        final int token = ++loadToken;
        refreshPlaylist();
        updatePlayButton();
        status("Yuklanmoqda...");
        new Thread(() -> {
            try {
                final ParadoksMusicDsp.Pcm decoded = ParadoksMusicDsp.decode(track.path, f -> AndroidUtilities.runOnUIThread(() -> {
                    if (token == loadToken && !released) status("Yuklanmoqda... " + Math.round(f * 100) + "%");
                }));
                if (token != loadToken) return;
                final int bpm = ParadoksMusicDsp.estimateBpm(decoded);
                AndroidUtilities.runOnUIThread(() -> {
                    if (token != loadToken || released) return;
                    ParadoksMusicDsp.Engine e = new ParadoksMusicDsp.Engine(decoded, params);
                    e.pos = Math.max(0f, Math.min(0.999f, startFraction)) * (decoded.frames - 1);
                    pcm = decoded;
                    bpmValue = bpm;
                    bpmText.setText(bpm > 0 ? "BPM ≈ " + bpm : "");
                    flushRequest = true;
                    engine = e;
                    status("");
                    if (autoplay) setPlaying(true);
                    updateTime();
                });
            } catch (Throwable e) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (token == loadToken && !released) status("Faylni ochib bo'lmadi: " + e.getMessage());
                });
            }
        }, "ParadoksMusicLabDecode").start();
    }

    private void togglePlay() {
        if (engine == null) {
            if (list.isEmpty()) pickAudio();
            return;
        }
        setPlaying(!playing);
    }

    private void setPlaying(boolean value) {
        if (value && !telegramStopped) {
            telegramStopped = true;
            try {
                MediaController.getInstance().cleanup();
            } catch (Throwable ignore) {
            }
        }
        playing = value;
        updatePlayButton();
    }

    private void updatePlayButton() {
        if (playButton != null) playButton.setText(playing ? "⏸ Pause" : "▶ Play");
    }

    private void onSpeedChanged() {
        // speed is read by the engine on every block (pitch changes with it, like the web version)
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            updateTime();
            AndroidUtilities.runOnUIThread(this, 200);
        }
    };

    private void updateTime() {
        ParadoksMusicDsp.Engine e = engine;
        if (timeText == null) return;
        if (e == null) {
            timeText.setText("0:00 / 0:00");
            return;
        }
        float rate = e.pcm.sampleRate;
        timeText.setText(fmt((float) (e.pos / rate)) + " / " + fmt(e.pcm.frames / rate));
        if (!seeking) seekBar.setProgress((int) (e.pos / Math.max(1, e.pcm.frames - 1) * 1000));
        updatePlayButton();
    }

    private static String fmt(float s) {
        if (Float.isNaN(s) || Float.isInfinite(s) || s < 0) return "0:00";
        int t = (int) s;
        return (t / 60) + ":" + (t % 60 < 10 ? "0" : "") + (t % 60);
    }

    private void status(String text) {
        if (statusText != null) statusText.setText(text == null ? "" : text);
    }

    private void toast(String text) {
        Toast.makeText(context != null ? context : ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------------------------------
    // audio thread
    // ------------------------------------------------------------------------------------------

    private void audioLoop() {
        AudioTrack track = null;
        int trackRate = 0;
        final float[] l = new float[BLOCK], r = new float[BLOCK];
        final short[] out = new short[BLOCK * 2];
        try {
            while (!released) {
                ParadoksMusicDsp.Engine e = engine;
                if (e == null || !playing) {
                    if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
                    Thread.sleep(25);
                    continue;
                }
                if (track == null || trackRate != e.pcm.sampleRate) {
                    if (track != null) {
                        track.release();
                    }
                    int min = AudioTrack.getMinBufferSize(e.pcm.sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                    track = new AudioTrack(AudioManager.STREAM_MUSIC, e.pcm.sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 16384), AudioTrack.MODE_STREAM);
                    trackRate = e.pcm.sampleRate;
                }
                if (flushRequest) {
                    flushRequest = false;
                    track.pause();
                    track.flush();
                }
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                track.setVolume(volume);

                if (abOn && e.pos / e.pcm.sampleRate >= markB) {
                    e.pos = markA * (double) e.pcm.sampleRate;
                }
                int n = e.fill(l, r, BLOCK);
                if (n <= 0) {
                    if (loop) {
                        e.pos = 0;
                        continue;
                    }
                    playing = false;
                    e.pos = 0;
                    final int token = loadToken;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (released || token != loadToken) return;
                        updatePlayButton();
                        if (cur >= 0 && cur < list.size() - 1) loadTrack(cur + 1, true, 0f);
                    });
                    continue;
                }
                spectrum.analyze(l, r, n);
                ParadoksMusicDsp.toShorts(l, r, n, out);
                track.write(out, 0, n * 2);
            }
        } catch (InterruptedException ignore) {
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignore) {
                }
                track.release();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // export
    // ------------------------------------------------------------------------------------------

    private String baseName() {
        String n = cur >= 0 && cur < list.size() ? list.get(cur).name : "musiqa";
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 5) n = n.substring(0, dot);
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private File workFile(String name) {
        File dir = new File(AndroidUtilities.getCacheDir(), "musiclab_out");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, name);
    }

    private void exportAudio() {
        if (exporting) {
            cancelExport = true;
            return;
        }
        final ParadoksMusicDsp.Pcm data = pcm;
        if (data == null || cur < 0) {
            status("Avval musiqa tanlang.");
            return;
        }
        if (getParentActivity() == null) return;
        if (!params.anyEffect()) {
            status("Effekt tanlanmagan — asl fayl saqlanmoqda...");
            final Track t = list.get(cur);
            final String ext = t.path.contains(".") ? t.path.substring(t.path.lastIndexOf('.')) : ".mp3";
            final File copy = workFile(baseName() + ext);
            new Thread(() -> {
                String error = null;
                try (InputStream in = new java.io.FileInputStream(t.path); FileOutputStream os = new FileOutputStream(copy)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } catch (Throwable e) {
                    error = e.getMessage();
                }
                final String err = error;
                AndroidUtilities.runOnUIThread(() -> {
                    if (released) return;
                    if (err != null) status("Xatolik: " + err); else saveToDevice(copy, 3, "audio/*");
                });
            }, "ParadoksMusicLabCopyOut").start();
            return;
        }
        exporting = true;
        cancelExport = false;
        exportButton.setText("✕ Bekor qilish");
        shareButton.setVisibility(View.GONE);
        final ParadoksMusicDsp.Params snapshot = params.copy();
        final boolean wav = format == 0;
        final File file = workFile(baseName() + "_fx" + (wav ? ".wav" : ".m4a"));
        status("Tayyorlanmoqda...");
        new Thread(() -> {
            boolean ok = false;
            String error = null;
            ParadoksMusicDsp.Sink sink = null;
            try {
                sink = wav ? new ParadoksMusicDsp.WavSink(file, data.sampleRate) : new ParadoksMusicDsp.M4aSink(file, data.sampleRate, 192000);
                ok = ParadoksMusicDsp.render(data, snapshot, sink, f -> AndroidUtilities.runOnUIThread(() -> status("Tayyorlanmoqda... " + Math.round(f * 100) + "%")), () -> cancelExport || released);
                if (ok) sink.finish(); else sink.abort();
            } catch (Throwable e) {
                error = e.getMessage();
                if (sink != null) sink.abort();
                ok = false;
            }
            final boolean done = ok;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> {
                exporting = false;
                if (exportButton != null) exportButton.setText("⬇ Yuklab olish");
                if (released) return;
                if (done) {
                    saveToDevice(file, 3, wav ? "audio/wav" : "audio/mp4");
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    status(err != null ? "Xatolik: " + err : "Bekor qilindi");
                }
            });
        }, "ParadoksMusicLabExport").start();
    }

    private void exportVideo() {
        if (videoExporting) {
            cancelExport = true;
            return;
        }
        final ParadoksMusicDsp.Pcm data = pcm;
        if (data == null || cur < 0) {
            status("Avval musiqa tanlang.");
            return;
        }
        if (data.frames / (float) data.sampleRate > 10 * 60) {
            status("Video uchun trek 10 daqiqadan oshmasin.");
            return;
        }
        videoExporting = true;
        cancelExport = false;
        playing = false;
        updatePlayButton();
        videoButton.setText("✕ Bekor qilish");
        shareButton.setVisibility(View.GONE);
        final ParadoksMusicDsp.Params snapshot = params.copy();
        final ParadoksVizRenderer.Settings settings = viz.copy();
        final File file = workFile(baseName() + "_video.mp4");
        status("Video tayyorlanmoqda...");
        new Thread(() -> {
            boolean ok;
            String error = null;
            try {
                ok = ParadoksMusicVideo.render(data, snapshot, settings, file,
                        f -> AndroidUtilities.runOnUIThread(() -> status("Video tayyorlanmoqda... " + Math.round(f * 100) + "%")),
                        () -> cancelExport || released);
            } catch (Throwable e) {
                error = e.getMessage();
                ok = false;
            }
            final boolean done = ok;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> {
                videoExporting = false;
                if (videoButton != null) videoButton.setText("🎬 Video yaratish");
                if (released) return;
                if (done) {
                    saveToDevice(file, 1, "video/mp4");
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    status(err != null ? "Videoni yaratib bo'lmadi: " + err : "Bekor qilindi");
                }
            });
        }, "ParadoksMusicLabVideo").start();
    }

    /** type: 1 = Movies, 3 = Music (same codes as {@link MediaController#saveFile}). */
    private void saveToDevice(File file, int type, String mime) {
        if (getParentActivity() == null) return;
        MediaController.saveFile(file.getAbsolutePath(), getParentActivity(), type, file.getName(), mime, uri -> {
            lastSavedUri = uri;
            status("Tayyor! ✅ " + (type == 1 ? "Movies/Telegram" : "Music/Telegram") + " papkasiga saqlandi");
            if (shareButton != null) {
                shareButton.setTag(mime);
                shareButton.setVisibility(View.VISIBLE);
            }
        });
    }

    private void shareLast() {
        if (lastSavedUri == null || getParentActivity() == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            Object mime = shareButton.getTag();
            intent.setType(mime instanceof String ? (String) mime : "*/*");
            intent.putExtra(Intent.EXTRA_STREAM, lastSavedUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, "Ulashish"));
        } catch (Throwable e) {
            toast("Ulashib bo'lmadi");
        }
    }

    // ------------------------------------------------------------------------------------------
    // preferences
    // ------------------------------------------------------------------------------------------

    private void loadPrefs() {
        SharedPreferences p = prefs;
        params.karaoke = p.getBoolean("karaoke", false);
        params.bass = p.getBoolean("bass", false);
        params.eq = p.getBoolean("eq", false);
        params.d8 = p.getBoolean("d8", false);
        params.echo = p.getBoolean("echo", false);
        params.reverb = p.getBoolean("reverb", false);
        params.slow = p.getBoolean("slow", false);
        params.nightcore = p.getBoolean("nightcore", false) && !params.slow;
        params.bassDb = p.getInt("bassDb", 10);
        params.d8Speed = p.getInt("d8Speed", 15) / 100f;
        params.echoTime = p.getInt("echoTime", 38) / 100f;
        params.reverbMix = p.getInt("reverbMix", 40) / 100f;
        for (int i = 0; i < 5; i++) params.eqGain[i] = p.getInt("eq" + i, 0);
        format = p.getInt("format", 0);
        viz.color1 = p.getInt("c1", 0xFF7C3AED);
        viz.color2 = p.getInt("c2", 0xFF22D3EE);
        viz.style = p.getInt("style", 0);
        viz.bars = p.getInt("bars", 64);
        viz.height = p.getInt("height", 100);
        viz.rainbow = p.getBoolean("rainbow", false);
        viz.glow = p.getBoolean("glow", true);
        viz.pulse = p.getBoolean("pulse", true);
        viz.particles = p.getBoolean("particles", true);
        viz.bgX = p.getInt("bgx", 0);
        viz.bgY = p.getInt("bgy", 0);
        volume = p.getInt("volume", 80) / 100f;
        loop = p.getBoolean("loop", false);
    }

    private void savePrefs() {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("karaoke", params.karaoke).putBoolean("bass", params.bass).putBoolean("eq", params.eq).putBoolean("d8", params.d8)
                .putBoolean("echo", params.echo).putBoolean("reverb", params.reverb).putBoolean("slow", params.slow).putBoolean("nightcore", params.nightcore)
                .putInt("bassDb", Math.round(params.bassDb)).putInt("d8Speed", Math.round(params.d8Speed * 100))
                .putInt("echoTime", Math.round(params.echoTime * 100)).putInt("reverbMix", Math.round(params.reverbMix * 100));
        for (int i = 0; i < 5; i++) e.putInt("eq" + i, Math.round(params.eqGain[i]));
        e.putInt("format", format).putInt("c1", viz.color1).putInt("c2", viz.color2).putInt("style", viz.style).putInt("bars", viz.bars)
                .putInt("height", viz.height).putBoolean("rainbow", viz.rainbow).putBoolean("glow", viz.glow).putBoolean("pulse", viz.pulse)
                .putBoolean("particles", viz.particles).putInt("bgx", viz.bgX).putInt("bgy", viz.bgY)
                .putInt("volume", Math.round(volume * 100)).putBoolean("loop", loop);
        e.apply();
    }

    // ------------------------------------------------------------------------------------------
    // views
    // ------------------------------------------------------------------------------------------

    /** Live visualizer; redraws itself every frame while attached. */
    private final class VizView extends View {
        private final ParadoksVizRenderer renderer = new ParadoksVizRenderer(viz);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private long lastTap;

        VizView(Context context) {
            super(context);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // gradients + paths: software keeps old devices consistent
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            clip.reset();
            rect.set(0, 0, w, h);
            clip.addRoundRect(rect, dp(12), dp(12), Path.Direction.CW);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            canvas.save();
            canvas.clipPath(clip);
            ParadoksMusicDsp.Engine e = engine;
            int rate = e != null ? e.pcm.sampleRate : 44100;
            renderer.draw(canvas, w, h, getResources().getDisplayMetrics().density, spectrum, rate, playing && e != null, System.currentTimeMillis());
            canvas.restore();
            postInvalidateOnAnimation();
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                long now = System.currentTimeMillis();
                if (now - lastTap < 350) {
                    bigViz = !bigViz;
                    ViewGroup.LayoutParams lp = getLayoutParams();
                    lp.height = bigViz ? Math.max(dp(320), (int) (AndroidUtilities.displaySize.y * 0.6f)) : dp(220);
                    setLayoutParams(lp);
                }
                lastTap = now;
            }
            return true;
        }
    }

    /** Minimal wrapping row (chips / buttons). */
    private static final class Flow extends ViewGroup {
        private final int hGap, vGap;

        Flow(Context context, int hGap, int vGap) {
            super(context);
            this.hGap = hGap;
            this.vGap = vGap;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                int cw = lp != null && lp.width > 0 ? MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST);
                int ch = lp != null && lp.height > 0 ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                c.measure(cw, ch);
                if (x > 0 && x + c.getMeasuredWidth() > width) {
                    x = 0;
                    y += rowH + vGap;
                    rowH = 0;
                }
                x += c.getMeasuredWidth() + hGap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + rowH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && x + cw > width) {
                    x = 0;
                    y += rowH + vGap;
                    rowH = 0;
                }
                c.layout(x, y, x + cw, y + ch);
                x += cw + hGap;
                rowH = Math.max(rowH, ch);
            }
        }
    }
}
