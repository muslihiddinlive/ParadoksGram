package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
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
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Musiqiy ijodxona: native Android music studio. Everything runs locally (no Mini App / WebView):
 * playback with live effects, spectrum visualizer, A-B loop, BPM, background image / cover, export of the
 * processed sound (WAV / M4A) and of a visualizer video (MP4).
 * <p>
 * When it is opened from the Telegram player while a track is playing, playback is handed over seamlessly:
 * the studio shows the live position right away, decodes the file in the background and then continues
 * from exactly the same second.
 */
public class ParadoksMusicLabActivity extends BaseFragment {

    private static final int REQ_AUDIO = 7781;
    private static final int REQ_IMAGE = 7782;
    private static final int BLOCK = ParadoksMusicDsp.Spectrum.SIZE;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // palette
    private static final int C_BG = 0xFF0B0B14, C_CARD = 0xFF14141F, C_RAISED = 0xFF1D1D2D, C_TX = 0xFFECEAF5, C_MUT = 0xFF8B89A0,
            C_AC = 0xFFA855F7, C_AC2 = 0xFF22D3EE, C_BD = 0xFF262638, C_ERR = 0xFFF87171;
    private static final int[] SWATCHES = {0xFF7C3AED, 0xFF22D3EE, 0xFFF43F5E, 0xFFFBBF24, 0xFF0EA5E9, 0xFF34D399, 0xFFDC2626, 0xFFFACC15, 0xFFFFFFFF, 0xFFEC4899};

    // icons
    private static final int IC_PLAY = 0, IC_PAUSE = 1, IC_PREV = 2, IC_NEXT = 3, IC_ADD = 4, IC_CLOSE = 5, IC_DOWNLOAD = 6, IC_VIDEO = 7,
            IC_SHARE = 8, IC_EXPAND = 9, IC_NOTE = 10, IC_IMAGE = 11, IC_CHEVRON = 12, IC_VOLUME = 13, IC_MUTE = 14, IC_RETRY = 15, IC_CHECK = 16;

    // effects
    private static final int FX_KARAOKE = 0, FX_BASS = 1, FX_EQ = 2, FX_8D = 3, FX_ECHO = 4, FX_REVERB = 5, FX_SLOW = 6, FX_NIGHT = 7, FX_COUNT = 8;
    private static final String[] FX_NAMES = {"Karaoke", "Bass boost", "Equalizer", "8D", "Echo", "Reverb", "Slow", "Nightcore"};
    private static final int[][] EQ_PRESETS = {{0, 0, 0, 0, 0}, {7, 4, 0, -1, -2}, {-2, 0, 3, 4, 1}, {-2, -1, 0, 4, 7}, {5, 3, -1, 3, 5}, {-1, 2, 4, 2, -1}};
    private static final String[] EQ_PRESET_NAMES = {"Tekis", "Bass+", "Vokal", "Yuqori+", "Rok", "Pop"};

    // load state
    private static final int ST_EMPTY = 0, ST_LOADING = 1, ST_READY = 2, ST_ERROR = 3;

    private interface BoolCb {
        void on(boolean value);
    }

    private interface IntCb {
        void on(int value);
    }

    private interface Fmt {
        String f(int value);
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
    private volatile boolean playing, released, flushRequest, loop, abOn, ducked;
    private volatile float volume = 0.8f, markA, markB;
    private volatile int loadToken;
    private Thread audioThread;
    private boolean telegramStopped, seeking, exporting, videoExporting, cancelExport;
    private Uri lastSavedUri;
    private int format = ParadoksAudioFormats.MP3; // ParadoksAudioFormats.MP3 / ORIGINAL / WAV / M4A / OGG / FLAC
    private int kbps = 256; // bitrate of the lossy formats
    private final ArrayList<TextView> kbpsButtons = new ArrayList<>();
    private static final int[] KBPS = {128, 192, 256, 320};
    private LinearLayout kbpsBox;
    private int bpmValue;
    private int tab;

    private int state = ST_EMPTY;
    private boolean pendingPlay, resumeOnFocus, bypassFx, noisyRegistered, miniShown, playlistOpen = true, bigViz;
    private float loadProgress, retryFraction;
    private String errorText = "";
    private int knownDurationSec;
    private Bitmap coverBitmap;

    // hand-over from the Telegram player
    private MessageObject handoffMessage;
    private boolean handoffActive, handoffWasPlaying;
    private float handoffLast;
    private long handoffLastAt;
    private int handoffDurationSec;

    // ---- views
    private Context context;
    private FrameLayout rootView, vizFrame, playBtn, miniPlay;
    private ScrollView scroll;
    private LinearLayout content, transportCard, playlistBox, miniBar, ovBox, tabBar, fxPanelsBox;
    private VizView vizView;
    private CoverView heroCover, miniCover;
    private ProgressLine ovLine, expLine, miniLine;
    private TextView heroTitle, heroSub, timeNow, timeTotal, volText, btnA, btnB, btnLoop, miniTitle, miniTime, statusPill, ovTitle, ovAction,
            expInfo, expResult, exportButton, videoButton, shareButton, bgClear, fxInfo, bypassChip;
    private SeekBar seekBar, volBar;
    private IconView playIcon, miniPlayIcon, volIcon;
    private final TextView[] tabLabels = new TextView[3];
    private final LinearLayout[] tabViews = new LinearLayout[3];
    private final TextView[] fxChips = new TextView[FX_COUNT];
    private final View[] fxPanels = new View[FX_COUNT];
    private final SliderRow[] eqRows = new SliderRow[5];
    private SliderRow bassRow, d8Row, echoRow, reverbRow, bgXRow, bgYRow;
    private final ArrayList<TextView> styleButtons = new ArrayList<>();
    private final ArrayList<TextView> formatButtons = new ArrayList<>();
    private final View[][] swatchViews = new View[2][];
    private TextView chipRainbow;

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
        if (!released && audioThread != null && audioThread.isAlive()) {
            // the studio that kept playing in the background is opened again: nothing to rebuild
            return super.onFragmentCreate();
        }
        ParadoksMusicBg.stopOthers(this);
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("paradoks_musiclab", Context.MODE_PRIVATE);
        loadPrefs();
        if (initialPath != null) {
            File f = new File(initialPath);
            if (f.exists()) {
                list.add(new Track(initialPath, initialName != null && initialName.length() > 0 ? initialName : f.getName(), false));
                captureHandoff();
            }
        }
        released = false;
        audioThread = new Thread(this::audioLoop, "ParadoksMusicLab");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
        return super.onFragmentCreate();
    }

    /** Remembers what the Telegram player was doing at the moment the studio was opened. */
    private void captureHandoff() {
        handoffActive = true;
        handoffLast = initialFraction;
        handoffLastAt = SystemClock.elapsedRealtime();
        try {
            MediaController mc = MediaController.getInstance();
            MessageObject pm = mc.getPlayingMessageObject();
            handoffMessage = pm;
            handoffWasPlaying = pm != null && !mc.isMessagePaused();
            if (pm != null) {
                if (pm.audioProgress > 0) handoffLast = pm.audioProgress;
                handoffDurationSec = pm.audioPlayerDuration;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (!released && playing) {
            // the screen is closed, but the music goes on; the player bar on top of the chats shows it
            detached = true;
            cancelExport = true;
            AndroidUtilities.cancelRunOnUIThread(ticker);
            AndroidUtilities.cancelRunOnUIThread(hidePill);
            ParadoksMusicBg.register(this);
            applyState();
        } else {
            fullRelease();
        }
        super.onFragmentDestroy();
    }

    // ---- background mode: the studio keeps playing after its screen is closed

    private boolean detached;
    private final Runnable idleStop = () -> {
        if (detached && !playing && !released) {
            fullRelease();
        }
    };

    boolean isBackgroundAlive() {
        return detached && !released;
    }

    boolean isBackgroundPlaying() {
        return detached && !released && playing;
    }

    String backgroundTitle() {
        Track t = cur >= 0 && cur < list.size() ? list.get(cur) : null;
        return t != null ? t.name : "";
    }

    void toggleFromBar() {
        if (!isBackgroundAlive()) return;
        if (state == ST_READY) {
            setPlaying(!playing);
        } else if (state == ST_LOADING) {
            pendingPlay = !pendingPlay;
            applyState();
        }
    }

    void stopBackground() {
        fullRelease();
    }

    boolean revive(BaseFragment from) {
        if (!isBackgroundAlive()) return false;
        AndroidUtilities.cancelRunOnUIThread(idleStop);
        detached = false;
        miniShown = false;
        resetFragment();
        if (!from.presentFragment(this)) {
            detached = true;
            return false;
        }
        ParadoksMusicBg.notifyChanged();
        return true;
    }

    /** Called from {@link #applyState()}: keeps the player bar in sync and frees a forgotten, paused studio. */
    private void syncBackground() {
        if (detached) {
            AndroidUtilities.cancelRunOnUIThread(idleStop);
            if (!playing && !released) {
                AndroidUtilities.runOnUIThread(idleStop, 15 * 60 * 1000L);
            }
        }
        ParadoksMusicBg.notifyChanged();
    }

    private void fullRelease() {
        if (released) return;
        detached = false;
        released = true;
        playing = false;
        loadToken++;
        cancelExport = true;
        AndroidUtilities.cancelRunOnUIThread(ticker);
        AndroidUtilities.cancelRunOnUIThread(hidePill);
        AndroidUtilities.cancelRunOnUIThread(idleStop);
        unregisterNoisy();
        abandonFocus();
        if (audioThread != null) {
            audioThread.interrupt();
        }
        for (Track t : list) {
            if (t.temp) {
                //noinspection ResultOfMethodCallIgnored
                new File(t.path).delete();
            }
        }
        ParadoksMusicBg.unregister(this);
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

        rootView = new FrameLayout(context);
        rootView.setBackgroundColor(C_BG);
        fragmentView = rootView;

        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> updateMini());
        rootView.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        content = vbox();
        content.setPadding(dp(12), dp(8), dp(12), dp(88));
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));

        buildHeroCard();
        buildVizCard();
        buildTransportCard();
        buildTabs();
        buildEffectsTab();
        buildVizTab();
        buildExportTab();

        TextView footer = text("Music Vibe Lab — ParadoksGram ichida, lokal ishlaydi", 12, C_MUT, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(4), 0, 0);
        content.addView(footer, ll(MATCH, WRAP));

        buildMiniPlayer();
        buildStatusPill();

        selectTab(tab, false);
        refreshPlaylist();
        refreshAB();
        updateExportInfo();
        applyState();
        if (coverBitmap != null) setCover(coverBitmap);
        AndroidUtilities.runOnUIThread(ticker, 100);
        if (!list.isEmpty() && cur < 0) {
            loadTrack(0, handoffActive ? handoffWasPlaying : true, initialFraction);
        }
        return fragmentView;
    }

    // ------------------------------------------------------------------------------------------
    // small view helpers
    // ------------------------------------------------------------------------------------------

    private static LinearLayout.LayoutParams ll(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private static LinearLayout.LayoutParams ll(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private static LinearLayout.LayoutParams llm(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            d.setStroke(Math.max(1, dp(1)), strokeColor);
        }
        return d;
    }

    private Drawable ripple(Drawable content, int radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    private Drawable ripple(int color, int radiusDp, int strokeColor) {
        return ripple(round(color, radiusDp, strokeColor), radiusDp);
    }

    private GradientDrawable accentGradient(int radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{C_AC, 0xFF6366F1});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void pressable(View v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) {
                x.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                x.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
            }
            return false;
        });
    }

    private void tick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private LinearLayout newCard() {
        LinearLayout c = vbox();
        c.setBackground(round(C_CARD, 18, C_BD));
        c.setPadding(dp(14), dp(14), dp(14), dp(12));
        return c;
    }

    private void attach(LinearLayout parent, View v) {
        LinearLayout.LayoutParams p = ll(MATCH, WRAP);
        p.bottomMargin = dp(12);
        parent.addView(v, p);
    }

    /** Round button with a drawn icon. */
    private FrameLayout iconBtn(int icon, int sizeDp, int bg, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(context);
        f.setBackground(ripple(bg, 999, 0));
        f.setContentDescription(desc);
        f.setClickable(true);
        int is = Math.round(sizeDp * 0.5f);
        f.addView(new IconView(context, icon, C_TX), new FrameLayout.LayoutParams(dp(is), dp(is), Gravity.CENTER));
        if (l != null) f.setOnClickListener(l);
        pressable(f);
        return f;
    }

    private IconView iconOf(FrameLayout f) {
        return (IconView) f.getChildAt(0);
    }

    private TextView button(String text, View.OnClickListener l) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(C_TX);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(42));
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(ripple(C_RAISED, 12, C_BD));
        t.setOnClickListener(l);
        pressable(t);
        return t;
    }

    private TextView primaryButton(String text, View.OnClickListener l) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(46));
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setBackground(ripple(accentGradient(14), 14));
        t.setOnClickListener(l);
        pressable(t);
        return t;
    }

    /** Selected / unselected look for segmented buttons. */
    private void setSel(TextView t, boolean on) {
        t.setBackground(ripple(on ? C_AC : C_RAISED, 12, on ? 0 : C_BD));
        t.setTextColor(on ? 0xFFFFFFFF : C_TX);
    }

    private TextView chip(String label, boolean checked, BoolCb cb) {
        final TextView t = new TextView(context);
        t.setText(label);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(40));
        t.setPadding(dp(15), dp(8), dp(15), dp(8));
        t.setTag(Boolean.valueOf(checked));
        styleChip(t, checked);
        t.setOnClickListener(v -> {
            boolean on = !(Boolean) t.getTag();
            setChip(t, on);
            tick(v);
            cb.on(on);
            savePrefs();
        });
        pressable(t);
        return t;
    }

    private void styleChip(TextView t, boolean on) {
        t.setBackground(ripple(on ? C_AC : C_RAISED, 20, on ? 0 : C_BD));
        t.setTextColor(on ? 0xFFFFFFFF : C_TX);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void setChip(TextView t, boolean on) {
        if (t == null) return;
        t.setTag(Boolean.valueOf(on));
        styleChip(t, on);
    }

    private Flow flow() {
        Flow f = new Flow(context, dp(8), dp(8));
        LinearLayout.LayoutParams p = ll(MATCH, WRAP);
        p.bottomMargin = dp(8);
        f.setLayoutParams(p);
        return f;
    }

    private TextView caption(String s) {
        TextView t = text(s, 12.5f, C_MUT, false);
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 13, C_MUT, true);
        t.setPadding(0, dp(2), 0, dp(8));
        return t;
    }

    private void styleSeek(SeekBar s) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2A40);
        bg.setCornerRadius(dp(3));
        bg.setSize(0, dp(5));
        GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{C_AC, C_AC2});
        fg.setCornerRadius(dp(3));
        fg.setSize(0, dp(5));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{bg, new ClipDrawable(fg, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        s.setProgressDrawable(layers);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(0xFFFFFFFF);
        thumb.setSize(dp(18), dp(18));
        s.setThumb(thumb);
        s.setSplitTrack(false);
        s.setPadding(dp(10), 0, dp(10), 0);
    }

    /** Labelled slider with its value on the right; the value is min..max (integers, the caller scales them). */
    private final class SliderRow {
        final LinearLayout view = vbox();
        final SeekBar bar;
        final TextView valueText;
        final int min;
        final Fmt fmt;

        SliderRow(String label, int min, int max, int value, Fmt fmt, IntCb cb) {
            this.min = min;
            this.fmt = fmt;
            LinearLayout head = hbox();
            TextView l = text(label, 13, C_MUT, false);
            valueText = text(fmt.f(value), 13, C_TX, true);
            head.addView(l, ll(0, WRAP, 1f));
            head.addView(valueText, ll(WRAP, WRAP));
            view.addView(head, ll(MATCH, WRAP));
            bar = new SeekBar(context);
            bar.setMax(max - min);
            bar.setProgress(value - min);
            styleSeek(bar);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int v = progress + SliderRow.this.min;
                    valueText.setText(SliderRow.this.fmt.f(v));
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
            view.addView(bar, llm(MATCH, dp(34), -dp(10), 0, -dp(10), dp(2)));
        }

        void set(int v) {
            bar.setProgress(v - min);
            valueText.setText(fmt.f(v));
        }
    }

    // ------------------------------------------------------------------------------------------
    // UI builders
    // ------------------------------------------------------------------------------------------

    private void buildHeroCard() {
        LinearLayout c = newCard();
        c.setPadding(dp(12), dp(12), dp(10), dp(12));
        LinearLayout row = hbox();
        heroCover = new CoverView(context, 14);
        row.addView(heroCover, ll(dp(64), dp(64)));
        LinearLayout col = vbox();
        col.setPadding(dp(12), 0, dp(8), 0);
        heroTitle = text("Musiqiy ijodxona", 16, C_TX, true);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroSub = text("", 13, C_MUT, false);
        heroSub.setSingleLine(true);
        heroSub.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(heroTitle, ll(MATCH, WRAP));
        col.addView(heroSub, llm(MATCH, WRAP, 0, dp(3), 0, 0));
        row.addView(col, ll(0, WRAP, 1f));
        row.addView(iconBtn(IC_ADD, 46, C_RAISED, "Musiqa qo'shish", v -> pickAudio()), ll(dp(46), dp(46)));
        c.addView(row, ll(MATCH, WRAP));
        attach(content, c);
    }

    private void buildVizCard() {
        LinearLayout c = newCard();
        c.setPadding(dp(6), dp(6), dp(6), dp(6));
        vizFrame = new FrameLayout(context);
        vizView = new VizView(context);
        vizFrame.addView(vizView, new FrameLayout.LayoutParams(MATCH, MATCH));

        ovBox = vbox();
        ovBox.setGravity(Gravity.CENTER);
        ovBox.setPadding(dp(20), dp(10), dp(20), dp(10));
        ovBox.setBackground(round(0xD0090912, 12, 0));
        ovBox.setClickable(true);
        ovTitle = text("", 15, C_TX, true);
        ovTitle.setGravity(Gravity.CENTER);
        ovLine = new ProgressLine(context);
        ovAction = primaryButton("", null);
        ovBox.addView(ovTitle, ll(WRAP, WRAP));
        ovBox.addView(ovLine, llm(dp(180), dp(4), 0, dp(14), 0, 0));
        ovBox.addView(ovAction, llm(WRAP, WRAP, 0, dp(14), 0, 0));
        vizFrame.addView(ovBox, new FrameLayout.LayoutParams(MATCH, MATCH));

        FrameLayout expand = iconBtn(IC_EXPAND, 38, 0x66000000, "Kattalashtirish", v -> toggleBigViz());
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP | Gravity.END);
        ep.topMargin = dp(6);
        ep.rightMargin = dp(6);
        vizFrame.addView(expand, ep);
        c.addView(vizFrame, ll(MATCH, dp(196)));
        attach(content, c);
    }

    private void toggleBigViz() {
        bigViz = !bigViz;
        ViewGroup.LayoutParams p = vizFrame.getLayoutParams();
        p.height = bigViz ? Math.max(dp(320), (int) (AndroidUtilities.displaySize.y * 0.6f)) : dp(196);
        vizFrame.setLayoutParams(p);
    }

    private void buildTransportCard() {
        transportCard = newCard();
        LinearLayout c = transportCard;

        seekBar = new SeekBar(context);
        seekBar.setMax(1000);
        styleSeek(seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                ParadoksMusicDsp.Engine e = engine;
                if (fromUser && e != null && state == ST_READY) {
                    float sec = (float) (progress / 1000.0 * (e.pcm.frames - 1) / e.pcm.sampleRate);
                    timeNow.setText(fmt(sec));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                ParadoksMusicDsp.Engine e = engine;
                if (e != null && state == ST_READY) {
                    e.pos = s.getProgress() / 1000.0 * (e.pcm.frames - 1);
                    flushRequest = true;
                }
                seeking = false;
                updateTime();
            }
        });
        c.addView(seekBar, llm(MATCH, dp(34), -dp(10), 0, -dp(10), 0));

        LinearLayout times = hbox();
        timeNow = text("0:00", 13, C_TX, true);
        timeTotal = text("--:--", 13, C_MUT, false);
        times.addView(timeNow, ll(0, WRAP, 1f));
        times.addView(timeTotal, ll(WRAP, WRAP));
        c.addView(times, ll(MATCH, WRAP));

        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER);
        FrameLayout prev = iconBtn(IC_PREV, 52, 0, "Oldingi trek", v -> onPrev());
        TextView back = circleText("−10", v -> skip(-10));
        playBtn = new FrameLayout(context);
        playBtn.setBackground(ripple(circleGradient(), 999));
        playBtn.setClickable(true);
        playBtn.setContentDescription("Ijro etish / pauza");
        playIcon = new IconView(context, IC_PLAY, 0xFFFFFFFF);
        playBtn.addView(playIcon, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        playBtn.setOnClickListener(v -> {
            tick(v);
            togglePlay();
        });
        pressable(playBtn);
        TextView fwd = circleText("+10", v -> skip(10));
        FrameLayout next = iconBtn(IC_NEXT, 52, 0, "Keyingi trek", v -> onNext());
        row.addView(prev, ll(dp(52), dp(52)));
        row.addView(back, llm(dp(48), dp(48), dp(4), 0, dp(4), 0));
        row.addView(playBtn, llm(dp(70), dp(70), dp(6), 0, dp(6), 0));
        row.addView(fwd, llm(dp(48), dp(48), dp(4), 0, dp(4), 0));
        row.addView(next, ll(dp(52), dp(52)));
        c.addView(row, llm(MATCH, WRAP, 0, dp(4), 0, dp(8)));

        Flow f = flow();
        btnLoop = chip("Takror", loop, on -> loop = on);
        f.addView(btnLoop);
        btnA = button("A", v -> {
            ParadoksMusicDsp.Engine e = engine;
            if (e == null) return;
            markA = (float) (e.pos / e.pcm.sampleRate);
            abOn = markB > markA;
            refreshAB();
            flash("A nuqta: " + fmt(markA));
        });
        btnB = button("B", v -> {
            ParadoksMusicDsp.Engine e = engine;
            if (e == null) return;
            markB = (float) (e.pos / e.pcm.sampleRate);
            abOn = markB > markA;
            refreshAB();
            flash(abOn ? "A–B takror: " + fmt(markA) + " → " + fmt(markB) : "B nuqta A dan keyin bo'lsin");
        });
        TextView clear = button("✕", v -> {
            abOn = false;
            markA = markB = 0;
            refreshAB();
            flash("A–B o'chirildi");
        });
        f.addView(btnA);
        f.addView(btnB);
        f.addView(clear);
        c.addView(f);

        LinearLayout vol = hbox();
        volIcon = new IconView(context, IC_VOLUME, C_MUT);
        vol.addView(volIcon, ll(dp(22), dp(22)));
        volBar = new SeekBar(context);
        volBar.setMax(100);
        volBar.setProgress(Math.round(volume * 100));
        styleSeek(volBar);
        volText = text(Math.round(volume * 100) + "%", 13, C_TX, true);
        volText.setGravity(Gravity.END);
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                volume = progress / 100f;
                volText.setText(progress + "%");
                volIcon.setIcon(progress == 0 ? IC_MUTE : IC_VOLUME);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                savePrefs();
            }
        });
        vol.addView(volBar, ll(0, dp(34), 1f));
        vol.addView(volText, ll(dp(44), WRAP));
        c.addView(vol, ll(MATCH, WRAP));

        playlistBox = vbox();
        c.addView(playlistBox, ll(MATCH, WRAP));
        attach(content, c);
    }

    private GradientDrawable circleGradient() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{C_AC, 0xFF6366F1});
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    private TextView circleText(String s, View.OnClickListener l) {
        TextView t = text(s, 13, C_TX, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(C_RAISED, 999, C_BD));
        t.setOnClickListener(l);
        t.setContentDescription(s + " soniya");
        pressable(t);
        return t;
    }

    private void buildTabs() {
        tabBar = hbox();
        tabBar.setBackground(round(C_CARD, 16, C_BD));
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] names = {"Effektlar", "Vizualizator", "Yuklab olish"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 14, C_MUT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(11), 0, dp(11));
            t.setOnClickListener(v -> {
                tick(v);
                selectTab(idx, true);
            });
            tabLabels[i] = t;
            tabBar.addView(t, ll(0, WRAP, 1f));
        }
        attach(content, tabBar);
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i] = vbox();
            content.addView(tabViews[i], ll(MATCH, WRAP));
        }
    }

    private void selectTab(int idx, boolean animate) {
        tab = Math.max(0, Math.min(2, idx));
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == tab;
            tabLabels[i].setBackground(on ? ripple(accentGradient(12), 12) : null);
            tabLabels[i].setTextColor(on ? 0xFFFFFFFF : C_MUT);
            if (on) {
                tabViews[i].setVisibility(View.VISIBLE);
                if (animate) {
                    tabViews[i].setAlpha(0f);
                    tabViews[i].animate().alpha(1f).setDuration(180).start();
                }
            } else {
                tabViews[i].setVisibility(View.GONE);
            }
        }
        if (animate) savePrefs();
    }

    // ---- effects tab

    private boolean fxGet(int i) {
        switch (i) {
            case FX_KARAOKE: return params.karaoke;
            case FX_BASS: return params.bass;
            case FX_EQ: return params.eq;
            case FX_8D: return params.d8;
            case FX_ECHO: return params.echo;
            case FX_REVERB: return params.reverb;
            case FX_SLOW: return params.slow;
            default: return params.nightcore;
        }
    }

    private void fxSet(int i, boolean v) {
        switch (i) {
            case FX_KARAOKE: params.karaoke = v; break;
            case FX_BASS: params.bass = v; break;
            case FX_EQ: params.eq = v; break;
            case FX_8D: params.d8 = v; break;
            case FX_ECHO: params.echo = v; break;
            case FX_REVERB: params.reverb = v; break;
            case FX_SLOW: params.slow = v; break;
            default: params.nightcore = v; break;
        }
    }

    private void onFxToggled(int i, boolean on) {
        fxSet(i, on);
        if (on && i == FX_SLOW) {
            params.nightcore = false;
            setChip(fxChips[FX_NIGHT], false);
            fxPanels[FX_NIGHT].setVisibility(View.GONE);
        } else if (on && i == FX_NIGHT) {
            params.slow = false;
            setChip(fxChips[FX_SLOW], false);
            fxPanels[FX_SLOW].setVisibility(View.GONE);
        }
        fxPanels[i].setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            fxPanels[i].setAlpha(0f);
            fxPanels[i].animate().alpha(1f).setDuration(180).start();
        }
        updateExportInfo();
    }

    private LinearLayout panel(String title, View... children) {
        LinearLayout p = vbox();
        p.setBackground(round(C_CARD, 16, C_BD));
        p.setPadding(dp(14), dp(12), dp(14), dp(8));
        p.addView(text(title, 14, C_TX, true), llm(MATCH, WRAP, 0, 0, 0, dp(6)));
        for (View v : children) {
            p.addView(v, ll(MATCH, WRAP));
        }
        return p;
    }

    private void buildEffectsTab() {
        LinearLayout tabLayout = tabViews[0];

        LinearLayout top = newCard();
        top.addView(sectionTitle("Effektlar"));
        Flow chips = flow();
        for (int i = 0; i < FX_COUNT; i++) {
            final int idx = i;
            fxChips[i] = chip(FX_NAMES[i], fxGet(i), on -> onFxToggled(idx, on));
            chips.addView(fxChips[i]);
        }
        top.addView(chips);
        Flow quick = flow();
        bypassChip = chip("Asl tovush (solishtirish)", bypassFx, on -> {
            bypassFx = on;
            ParadoksMusicDsp.Engine e = engine;
            if (e != null) e.bypass = on;
            flushRequest = true;
            fxInfo.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        quick.addView(bypassChip);
        quick.addView(button("Hammasini o'chirish", v -> resetEffects()));
        top.addView(quick);
        fxInfo = text("Asl tovush eshitilmoqda — effektlar vaqtincha o'chirilgan.", 12.5f, C_AC2, false);
        fxInfo.setVisibility(bypassFx ? View.VISIBLE : View.GONE);
        top.addView(fxInfo, ll(MATCH, WRAP));
        attach(tabLayout, top);

        fxPanelsBox = vbox();

        fxPanels[FX_KARAOKE] = panel("Karaoke", caption("Markaziy kanaldagi ovoz (vokal) susaytiriladi — minus-versiya effekti."));
        bassRow = new SliderRow("Bass kuchi", 0, 18, Math.round(params.bassDb), v -> v + " dB", v -> params.bassDb = v);
        fxPanels[FX_BASS] = panel("Bass boost", bassRow.view);

        LinearLayout[] eqParts = new LinearLayout[7];
        Flow presets = flow();
        for (int p = 0; p < EQ_PRESETS.length; p++) {
            final int[] gains = EQ_PRESETS[p];
            presets.addView(button(EQ_PRESET_NAMES[p], v -> {
                tick(v);
                for (int b = 0; b < 5; b++) {
                    params.eqGain[b] = gains[b];
                    eqRows[b].set(gains[b]);
                }
                savePrefs();
            }));
        }
        View[] eqChildren = new View[6];
        eqChildren[0] = presets;
        for (int i = 0; i < 5; i++) {
            final int band = i;
            String hz = ParadoksMusicDsp.EQ_FREQ[i] >= 1000 ? (ParadoksMusicDsp.EQ_FREQ[i] / 1000) + " kHz" : ParadoksMusicDsp.EQ_FREQ[i] + " Hz";
            eqRows[i] = new SliderRow(hz, -12, 12, Math.round(params.eqGain[i]), v -> (v > 0 ? "+" : "") + v + " dB", v -> params.eqGain[band] = v);
            eqChildren[i + 1] = eqRows[i].view;
        }
        fxPanels[FX_EQ] = panel("Equalizer", eqChildren);

        d8Row = new SliderRow("Aylanish tezligi", 5, 60, Math.round(params.d8Speed * 100), v -> String.format(Locale.US, "%.2f Hz", v / 100f), v -> params.d8Speed = v / 100f);
        fxPanels[FX_8D] = panel("8D (aylanuvchi ovoz)", caption("Naushnik bilan eshiting — ovoz boshingiz atrofida aylanadi."), d8Row.view);
        echoRow = new SliderRow("Kechikish", 10, 100, Math.round(params.echoTime * 100), v -> String.format(Locale.US, "%.2f s", v / 100f), v -> params.echoTime = v / 100f);
        fxPanels[FX_ECHO] = panel("Echo", echoRow.view);
        reverbRow = new SliderRow("Aralashma", 0, 100, Math.round(params.reverbMix * 100), v -> v + "%", v -> params.reverbMix = v / 100f);
        fxPanels[FX_REVERB] = panel("Reverb", reverbRow.view);
        fxPanels[FX_SLOW] = panel("Slow", caption("Tezlik ×0.80: trek sekinlashadi, ohang ham pastroq bo'ladi."));
        fxPanels[FX_NIGHT] = panel("Nightcore", caption("Tezlik ×1.25: trek tezlashadi, ohang ham balandroq bo'ladi."));

        for (int i = 0; i < FX_COUNT; i++) {
            fxPanels[i].setVisibility(fxGet(i) ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams p = ll(MATCH, WRAP);
            p.bottomMargin = dp(10);
            fxPanelsBox.addView(fxPanels[i], p);
        }
        tabLayout.addView(fxPanelsBox, ll(MATCH, WRAP));
    }

    private void resetEffects() {
        for (int i = 0; i < FX_COUNT; i++) {
            fxSet(i, false);
            setChip(fxChips[i], false);
            fxPanels[i].setVisibility(View.GONE);
        }
        updateExportInfo();
        savePrefs();
        flash("Barcha effektlar o'chirildi");
    }

    // ---- visualizer tab

    private void buildVizTab() {
        LinearLayout tabLayout = tabViews[1];
        LinearLayout c = newCard();

        c.addView(sectionTitle("Ranglar"));
        Flow presets = flow();
        String[][] pre = {{"Neon", "#7C3AED", "#22D3EE"}, {"Sunset", "#F43F5E", "#FBBF24"}, {"Ocean", "#0EA5E9", "#34D399"}, {"Fire", "#DC2626", "#FACC15"}};
        for (String[] p : pre) {
            final int c1 = android.graphics.Color.parseColor(p[1]), c2 = android.graphics.Color.parseColor(p[2]);
            TextView b = new TextView(context);
            b.setText(p[0]);
            b.setTextColor(0xFFFFFFFF);
            b.setTextSize(14);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(dp(42));
            b.setPadding(dp(18), dp(9), dp(18), dp(9));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{c1, c2});
            g.setCornerRadius(dp(12));
            b.setBackground(ripple(g, 12));
            b.setOnClickListener(v -> {
                viz.color1 = c1;
                viz.color2 = c2;
                viz.rainbow = false;
                setChip(chipRainbow, false);
                refreshSwatches();
                savePrefs();
            });
            pressable(b);
            presets.addView(b);
        }
        chipRainbow = chip("Rainbow", viz.rainbow, on -> viz.rainbow = on);
        presets.addView(chipRainbow);
        c.addView(presets);

        c.addView(caption("Past rangi"));
        c.addView(swatches(0));
        c.addView(caption("Yuqori rangi"));
        c.addView(swatches(1));
        attach(tabLayout, c);

        LinearLayout c2 = newCard();
        c2.addView(sectionTitle("Uslub"));
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
        c2.addView(styles);
        c2.addView(new SliderRow("Ustunlar soni", 16, 128, viz.bars, v -> String.valueOf(v), v -> viz.bars = v).view);
        c2.addView(new SliderRow("Balandlik", 20, 200, viz.height, v -> v + "%", v -> viz.height = v).view);
        Flow fx = flow();
        fx.addView(chip("Glow", viz.glow, on -> viz.glow = on));
        fx.addView(chip("Pulsatsiya", viz.pulse, on -> viz.pulse = on));
        fx.addView(chip("Zarrachalar", viz.particles, on -> viz.particles = on));
        c2.addView(fx);
        attach(tabLayout, c2);

        LinearLayout c3 = newCard();
        c3.addView(sectionTitle("Fon rasm"));
        Flow bg = flow();
        bg.addView(button("Rasm tanlash", v -> pickImage()));
        bg.addView(button("Muqova", v -> useThumbnail()));
        bgClear = button("Fonni olib tashlash", v -> {
            viz.background = null;
            refreshBackgroundUi();
        });
        bg.addView(bgClear);
        c3.addView(bg);
        bgXRow = new SliderRow("Gorizontal joy", -100, 100, viz.bgX, v -> String.valueOf(v), v -> viz.bgX = v);
        bgYRow = new SliderRow("Vertikal joy", -100, 100, viz.bgY, v -> String.valueOf(v), v -> viz.bgY = v);
        c3.addView(bgXRow.view);
        c3.addView(bgYRow.view);
        attach(tabLayout, c3);
        refreshBackgroundUi();
    }

    private void refreshBackgroundUi() {
        boolean has = viz.background != null;
        if (bgClear != null) {
            bgClear.setAlpha(has ? 1f : 0.4f);
            bgClear.setEnabled(has);
        }
        if (bgXRow != null) bgXRow.view.setVisibility(has ? View.VISIBLE : View.GONE);
        if (bgYRow != null) bgYRow.view.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private View swatches(final int row) {
        Flow f = flow();
        final View[] views = new View[SWATCHES.length];
        for (int i = 0; i < SWATCHES.length; i++) {
            final int color = SWATCHES[i];
            View v = new View(context);
            v.setOnClickListener(x -> {
                if (row == 0) viz.color1 = color; else viz.color2 = color;
                viz.rainbow = false;
                setChip(chipRainbow, false);
                refreshSwatches();
                tick(x);
                savePrefs();
            });
            pressable(v);
            views[i] = v;
            f.addView(v, new ViewGroup.LayoutParams(dp(36), dp(36)));
        }
        swatchViews[row] = views;
        refreshSwatches();
        return f;
    }

    private void refreshSwatches() {
        for (int row = 0; row < 2; row++) {
            View[] views = swatchViews[row];
            if (views == null) continue;
            int selected = row == 0 ? viz.color1 : viz.color2;
            for (int i = 0; i < views.length; i++) {
                boolean on = SWATCHES[i] == selected && !viz.rainbow;
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(SWATCHES[i]);
                d.setStroke(dp(on ? 3 : 1), on ? 0xFFFFFFFF : C_BD);
                views[i].setBackground(d);
            }
        }
    }

    private void updateStyleButtons() {
        for (int i = 0; i < styleButtons.size(); i++) {
            setSel(styleButtons.get(i), i == viz.style);
        }
    }

    // ---- export tab

    private void buildExportTab() {
        LinearLayout tabLayout = tabViews[2];
        LinearLayout c = newCard();
        c.addView(sectionTitle("Format"));
        Flow f = flow();
        formatButtons.clear();
        for (int i = 0; i < ParadoksAudioFormats.COUNT; i++) {
            if (i == ParadoksAudioFormats.OGG && !ParadoksAudioFormats.oggSupported()) continue;
            final int idx = i;
            TextView b = button(formatLabel(i), null);
            b.setTag(idx);
            b.setOnClickListener(v -> {
                format = idx;
                updateFormatButtons();
                updateExportInfo();
                savePrefs();
            });
            formatButtons.add(b);
            f.addView(b);
        }
        updateFormatButtons();
        c.addView(f);
        kbpsBox = vbox();
        kbpsBox.addView(sectionTitle("Sifat (bitrate)"));
        Flow kf = flow();
        kbpsButtons.clear();
        for (int i = 0; i < KBPS.length; i++) {
            final int v = KBPS[i];
            TextView b = button(v + " kbps", null);
            b.setTag(v);
            b.setOnClickListener(x -> {
                kbps = v;
                updateFormatButtons();
                updateExportInfo();
                savePrefs();
            });
            kbpsButtons.add(b);
            kf.addView(b);
        }
        kbpsBox.addView(kf);
        c.addView(kbpsBox);
        updateFormatButtons();
        expInfo = caption("");
        c.addView(expInfo, ll(MATCH, WRAP));

        exportButton = primaryButton("Yuklab olish", v -> exportAudio());
        c.addView(exportButton, llm(MATCH, WRAP, 0, dp(6), 0, 0));
        videoButton = button("Vizualizatorli video yaratish", v -> exportVideo());
        c.addView(videoButton, llm(MATCH, WRAP, 0, dp(8), 0, 0));

        expLine = new ProgressLine(context);
        expLine.setVisibility(View.GONE);
        c.addView(expLine, llm(MATCH, dp(4), 0, dp(14), 0, 0));
        expResult = text("", 13, C_MUT, false);
        expResult.setPadding(0, dp(8), 0, 0);
        c.addView(expResult, ll(MATCH, WRAP));
        shareButton = button("Ulashish", v -> shareLast());
        shareButton.setVisibility(View.GONE);
        c.addView(shareButton, llm(MATCH, WRAP, 0, dp(8), 0, 0));
        attach(tabLayout, c);
    }

    private String srcExt() {
        return cur >= 0 && cur < list.size() ? ParadoksAudioFormats.extOf(list.get(cur).path) : ".mp3";
    }

    private String formatLabel(int f) {
        switch (f) {
            case ParadoksAudioFormats.MP3:
                return "MP3 (tavsiya)";
            case ParadoksAudioFormats.ORIGINAL:
                return "Asl (" + srcExt() + ")";
            case ParadoksAudioFormats.WAV:
                return "WAV (siqilmagan)";
            case ParadoksAudioFormats.M4A:
                return "M4A (AAC)";
            case ParadoksAudioFormats.OGG:
                return "OGG (Opus)";
            default:
                return "FLAC (yo'qotishsiz)";
        }
    }

    /** The concrete format that will be written for the current selection. */
    private int effectiveFormat() {
        return format == ParadoksAudioFormats.ORIGINAL ? ParadoksAudioFormats.resolveOriginal(srcExt()) : format;
    }

    private void updateFormatButtons() {
        for (TextView b : formatButtons) {
            int idx = (Integer) b.getTag();
            if (idx == ParadoksAudioFormats.ORIGINAL) b.setText(formatLabel(idx));
            setSel(b, idx == format);
        }
        for (TextView b : kbpsButtons) {
            setSel(b, (Integer) b.getTag() == kbps);
        }
        if (kbpsBox != null) {
            boolean show = ParadoksAudioFormats.lossy(effectiveFormat()) && !sameAsSource();
            kbpsBox.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /** True when nothing has to be re-encoded: no effects and the target is the source file's own format. */
    private boolean sameAsSource() {
        return !params.anyEffect() && (format == ParadoksAudioFormats.ORIGINAL
                || ParadoksAudioFormats.extFor(format).equals(srcExt()));
    }

    private void updateExportInfo() {
        if (expInfo == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FX_COUNT; i++) {
            if (fxGet(i)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(FX_NAMES[i]);
            }
        }
        String text;
        if (sameAsSource()) {
            text = "Effekt tanlanmagan — asl fayl o'zgarishsiz saqlanadi.";
        } else {
            int eff = effectiveFormat();
            text = (sb.length() == 0 ? "Effektsiz, " : "Effektlar: " + sb + ". ") + "Natija: " + ParadoksAudioFormats.nameFor(eff)
                    + (ParadoksAudioFormats.lossy(eff) ? " · " + kbps + " kbps" : "") + ".";
        }
        expInfo.setText(text);
        updateFormatButtons();
    }

    // ---- mini player + status pill

    private void buildMiniPlayer() {
        miniBar = vbox();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF181826);
        float r = dp(18);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(Math.max(1, dp(1)), C_BD);
        miniBar.setBackground(bg);
        miniBar.setElevation(dp(10));
        miniLine = new ProgressLine(context);
        miniBar.addView(miniLine, llm(MATCH, dp(2), dp(18), 0, dp(18), 0));
        LinearLayout row = hbox();
        row.setPadding(dp(12), dp(8), dp(12), dp(10));
        miniCover = new CoverView(context, 10);
        row.addView(miniCover, ll(dp(44), dp(44)));
        LinearLayout col = vbox();
        col.setPadding(dp(10), 0, dp(8), 0);
        miniTitle = text("", 14, C_TX, true);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniTime = text("0:00", 12, C_MUT, false);
        col.addView(miniTitle, ll(MATCH, WRAP));
        col.addView(miniTime, ll(MATCH, WRAP));
        row.addView(col, ll(0, WRAP, 1f));
        miniPlay = new FrameLayout(context);
        miniPlay.setBackground(ripple(circleGradient(), 999));
        miniPlay.setClickable(true);
        miniPlay.setContentDescription("Ijro etish / pauza");
        miniPlayIcon = new IconView(context, IC_PLAY, 0xFFFFFFFF);
        miniPlay.addView(miniPlayIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        miniPlay.setOnClickListener(v -> {
            tick(v);
            togglePlay();
        });
        pressable(miniPlay);
        row.addView(miniPlay, ll(dp(46), dp(46)));
        miniBar.addView(row, ll(MATCH, WRAP));
        miniBar.setOnClickListener(v -> scroll.smoothScrollTo(0, 0));
        miniBar.setVisibility(View.GONE);
        rootView.addView(miniBar, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
    }

    private void buildStatusPill() {
        statusPill = text("", 13, C_TX, false);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(dp(18), dp(11), dp(18), dp(11));
        statusPill.setBackground(round(0xF2222236, 22, C_BD));
        statusPill.setElevation(dp(12));
        statusPill.setMaxWidth(dp(330));
        statusPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        p.bottomMargin = dp(18);
        rootView.addView(statusPill, p);
    }

    private void updateMini() {
        if (miniBar == null || scroll == null || transportCard == null || statusPill == null) return;
        boolean show = state != ST_EMPTY && scroll.getScrollY() > transportCard.getBottom() - dp(30);
        if (show == miniShown) return;
        miniShown = show;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) statusPill.getLayoutParams();
        p.bottomMargin = dp(18) + (show ? dp(76) : 0);
        statusPill.setLayoutParams(p);
        if (show) {
            miniBar.setVisibility(View.VISIBLE);
            miniBar.setTranslationY(dp(90));
            miniBar.animate().translationY(0).setDuration(200).start();
        } else {
            miniBar.animate().translationY(dp(90)).setDuration(180).withEndAction(() -> {
                if (!miniShown && miniBar != null) miniBar.setVisibility(View.GONE);
            }).start();
        }
    }

    // ------------------------------------------------------------------------------------------
    // status / messages
    // ------------------------------------------------------------------------------------------

    private final Runnable hidePill = new Runnable() {
        @Override
        public void run() {
            if (statusPill == null) return;
            statusPill.animate().alpha(0f).translationY(dp(8)).setDuration(180).withEndAction(() -> {
                if (statusPill != null && statusPill.getAlpha() == 0f) statusPill.setVisibility(View.GONE);
            }).start();
        }
    };

    private void showPill(String text) {
        if (statusPill == null) return;
        AndroidUtilities.cancelRunOnUIThread(hidePill);
        statusPill.setText(text);
        if (statusPill.getVisibility() != View.VISIBLE || statusPill.getAlpha() < 1f) {
            statusPill.setVisibility(View.VISIBLE);
            statusPill.setTranslationY(dp(8));
            statusPill.animate().alpha(1f).translationY(0).setDuration(180).start();
        }
    }

    /** Persistent status (loading / export progress); an empty text hides it. */
    private void status(String text) {
        if (text == null || text.length() == 0) {
            AndroidUtilities.cancelRunOnUIThread(hidePill);
            AndroidUtilities.runOnUIThread(hidePill);
        } else {
            showPill(text);
        }
    }

    /** Short message that goes away by itself. */
    private void flash(String text) {
        if (statusPill == null || released) return;
        showPill(text);
        AndroidUtilities.runOnUIThread(hidePill, 2800);
    }

    private void toast(String text) {
        Toast.makeText(context != null ? context : ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
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
                    status("");
                    if (added.isEmpty()) {
                        flash("Audio fayl tanlang.");
                        return;
                    }
                    boolean first = list.isEmpty() || state == ST_EMPTY;
                    list.addAll(added);
                    refreshPlaylist();
                    if (first) {
                        loadTrack(list.size() - added.size(), true, 0f);
                    } else {
                        flash(added.size() + " ta musiqa pleylistga qo'shildi");
                    }
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
                    AndroidUtilities.runOnUIThread(() -> flash("Rasmni ochib bo'lmadi."));
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
            flash("Rasm topilmadi.");
            return;
        }
        viz.background = bmp;
        refreshBackgroundUi();
    }

    private void useThumbnail() {
        if (cur < 0 || cur >= list.size()) {
            flash("Avval musiqa tanlang.");
            return;
        }
        if (coverBitmap != null) {
            setBackground(coverBitmap);
            flash("Muqova fon sifatida qo'yildi");
            return;
        }
        flash("Bu fayldan muqova rasmi topilmadi — \"Rasm tanlash\" orqali o'zingiz yuklang.");
    }

    /** Pulls the embedded cover out of the file (shown in the header, usable as the visualizer background). */
    private void loadCover(final Track track, final int token) {
        new Thread(() -> {
            Bitmap bmp = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(track.path);
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
                if (released || token != loadToken) return;
                coverBitmap = result;
                if (heroCover != null) heroCover.setBitmap(result);
                if (miniCover != null) miniCover.setBitmap(result);
            });
        }, "ParadoksMusicLabCover").start();
    }

    // ------------------------------------------------------------------------------------------
    // playlist / loading / playback control
    // ------------------------------------------------------------------------------------------

    private void refreshPlaylist() {
        if (playlistBox == null) return;
        playlistBox.removeAllViews();
        if (list.size() >= 2) {
            LinearLayout head = hbox();
            head.setPadding(0, dp(12), 0, dp(6));
            head.addView(text("Pleylist · " + list.size() + " ta", 13, C_MUT, true), ll(0, WRAP, 1f));
            IconView chev = new IconView(context, IC_CHEVRON, C_MUT);
            chev.setRotation(playlistOpen ? 180f : 0f);
            head.addView(chev, ll(dp(22), dp(22)));
            head.setOnClickListener(v -> {
                playlistOpen = !playlistOpen;
                refreshPlaylist();
            });
            playlistBox.addView(head, ll(MATCH, WRAP));
            if (playlistOpen) {
                for (int i = 0; i < list.size(); i++) {
                    final int idx = i;
                    final boolean now = i == cur;
                    LinearLayout row = hbox();
                    row.setPadding(dp(12), dp(4), dp(4), dp(4));
                    row.setBackground(ripple(now ? 0x33A855F7 : C_RAISED, 12, now ? C_AC : 0));
                    row.setOnClickListener(v -> {
                        if (idx != cur) loadTrack(idx, true, 0f);
                    });
                    TextView n = text(String.valueOf(i + 1), 13, now ? C_AC : C_MUT, true);
                    row.addView(n, ll(dp(26), WRAP));
                    TextView name = text(list.get(i).name, 13.5f, now ? C_TX : 0xFFC9C7DA, now);
                    name.setSingleLine(true);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(name, ll(0, WRAP, 1f));
                    FrameLayout del = iconBtn(IC_CLOSE, 40, 0, "Olib tashlash", v -> removeTrack(idx));
                    iconOf(del).setColor(C_MUT);
                    row.addView(del, ll(dp(40), dp(40)));
                    playlistBox.addView(row, llm(MATCH, WRAP, 0, dp(4), 0, 0));
                }
            }
        }
    }

    private void removeTrack(int idx) {
        if (idx < 0 || idx >= list.size()) return;
        Track t = list.remove(idx);
        if (t.temp) {
            //noinspection ResultOfMethodCallIgnored
            new File(t.path).delete();
        }
        if (idx < cur) {
            cur--;
        } else if (idx == cur) {
            boolean was = playing || pendingPlay;
            loadToken++;
            playing = false;
            engine = null;
            pcm = null;
            if (list.isEmpty()) {
                cur = -1;
                state = ST_EMPTY;
                setCover(null);
                applyState();
            } else {
                loadTrack(Math.min(idx, list.size() - 1), was, 0f);
                return;
            }
        }
        refreshPlaylist();
        applyState();
    }

    private void setCover(Bitmap b) {
        coverBitmap = b;
        if (heroCover != null) heroCover.setBitmap(b);
        if (miniCover != null) miniCover.setBitmap(b);
    }

    private void loadTrack(int index, boolean autoplay, float startFraction) {
        if (index < 0 || index >= list.size()) return;
        cur = index;
        final Track track = list.get(index);
        final boolean useHandoff = handoffActive && track.path.equals(initialPath);
        if (!useHandoff) handoffActive = false;
        playing = false;
        unregisterNoisy();
        engine = null;
        pcm = null;
        abOn = false;
        markA = markB = 0;
        bpmValue = 0;
        knownDurationSec = useHandoff ? handoffDurationSec : 0;
        state = ST_LOADING;
        loadProgress = 0f;
        errorText = "";
        pendingPlay = autoplay;
        setCover(null);
        final int token = ++loadToken;
        refreshPlaylist();
        refreshAB();
        applyState();
        updateTime();
        loadCover(track, token);
        new Thread(() -> {
            // the exact length lets the studio show "1:23 / 3:45" while the file is still being decoded
            try {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(track.path);
                    String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) {
                        final int sec = Math.round(Long.parseLong(d) / 1000f);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (token == loadToken && !released && sec > 0) knownDurationSec = sec;
                        });
                    }
                } finally {
                    r.release();
                }
            } catch (Throwable ignore) {
            }

            ParadoksMusicDsp.Pcm decoded = null;
            Throwable error = null;
            final ParadoksMusicDsp.Progress progress = f -> AndroidUtilities.runOnUIThread(() -> {
                if (token == loadToken && !released && state == ST_LOADING) onLoadProgress(f);
            });
            final ParadoksMusicDsp.Cancel cancel = () -> token != loadToken || released;
            for (int attempt = 0; attempt < 2 && decoded == null; attempt++) {
                File retry = null;
                try {
                    String path = track.path;
                    if (attempt == 1) {
                        retry = copyForRetry(track.path);
                        if (retry == null) break;
                        path = retry.getAbsolutePath();
                    }
                    decoded = ParadoksMusicDsp.decode(path, progress, cancel);
                } catch (Throwable e) {
                    error = e;
                    if (token != loadToken || released) return;
                    if (e instanceof OutOfMemoryError) break;
                } finally {
                    if (retry != null) {
                        //noinspection ResultOfMethodCallIgnored
                        retry.delete();
                    }
                }
            }
            if (token != loadToken || released) return;
            if (decoded == null) {
                final Throwable err = error;
                FileLog.e(err != null ? err : new RuntimeException("decode failed"));
                AndroidUtilities.runOnUIThread(() -> {
                    if (token != loadToken || released) return;
                    onLoadFailed(err);
                });
                return;
            }
            final ParadoksMusicDsp.Pcm ready = decoded;
            final int bpm = ParadoksMusicDsp.estimateBpm(ready);
            AndroidUtilities.runOnUIThread(() -> {
                if (token != loadToken || released) return;
                onLoaded(ready, bpm, useHandoff, startFraction);
            });
        }, "ParadoksMusicLabDecode").start();
    }

    private File copyForRetry(String path) {
        try {
            File dir = new File(AndroidUtilities.getCacheDir(), "musiclab");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String ext = path.contains(".") ? path.substring(path.lastIndexOf('.')) : ".mp3";
            if (ext.length() > 6 || !ext.matches("\\.[A-Za-z0-9]+")) ext = ".mp3";
            File out = new File(dir, "retry_" + System.currentTimeMillis() + ext);
            try (InputStream in = new java.io.FileInputStream(path); FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    private void onLoadProgress(float f) {
        loadProgress = f;
        ovLine.setProgress(f);
        ovTitle.setText(loadingTitle() + " " + Math.round(f * 100) + "%");
        heroSub.setText(buildSub());
    }

    private String loadingTitle() {
        return handoffActive ? "Telegramdan davom ettirilmoqda…" : "Yuklanmoqda…";
    }

    private void onLoadFailed(Throwable e) {
        if (handoffActive) {
            retryFraction = liveHandoffFraction();
            handoffActive = false;
        }
        state = ST_ERROR;
        pendingPlay = false;
        String msg = e != null && e.getMessage() != null ? e.getMessage() : "";
        if (e instanceof OutOfMemoryError || msg.contains("Xotira")) {
            errorText = "Xotira yetarli emas — trek juda uzun";
        } else if (msg.contains("Audio trek topilmadi")) {
            errorText = "Bu faylda audio trek topilmadi";
        } else if (msg.length() > 0 && msg.length() < 70) {
            errorText = "Faylni ochib bo'lmadi: " + msg;
        } else {
            errorText = "Faylni ochib bo'lmadi";
        }
        applyState();
        updateTime();
    }

    private void onLoaded(ParadoksMusicDsp.Pcm decoded, int bpm, boolean useHandoff, float startFraction) {
        ParadoksMusicDsp.Engine e = new ParadoksMusicDsp.Engine(decoded, params);
        float frac = startFraction;
        boolean resumed = false;
        if (useHandoff) {
            frac = liveHandoffFraction();
            if (frac > 0.97f) frac = 0f; // Telegram has just finished this track: start over
            handoffActive = false;
            resumed = frac > 0f;
        } else if (retryFraction > 0f) {
            frac = retryFraction;
        }
        retryFraction = 0f;
        e.pos = Math.max(0f, Math.min(0.999f, frac)) * (decoded.frames - 1);
        e.bypass = bypassFx;
        pcm = decoded;
        bpmValue = bpm;
        knownDurationSec = Math.round(decoded.frames / (float) decoded.sampleRate);
        flushRequest = true;
        engine = e;
        state = ST_READY;
        loadProgress = 1f;
        applyState();
        updateTime();
        if (pendingPlay) {
            setPlaying(true);
            if (useHandoff && resumed && playing) {
                flash("Telegramdan davom etmoqda — " + fmt((float) (e.pos / decoded.sampleRate)) + " dan");
            }
        }
    }

    /** Live position (0..1) of the track that the Telegram player handed over. */
    private float liveHandoffFraction() {
        long now = SystemClock.elapsedRealtime();
        try {
            MessageObject pm = MediaController.getInstance().getPlayingMessageObject();
            if (handoffMessage != null && pm == handoffMessage && pm.audioProgress >= 0f) {
                handoffLast = pm.audioProgress;
                handoffLastAt = now;
                if (pm.audioPlayerDuration > 0) handoffDurationSec = pm.audioPlayerDuration;
                return Math.max(0f, Math.min(0.999f, handoffLast));
            }
        } catch (Throwable ignore) {
        }
        // the Telegram player is gone (finished / closed): carry on from the last known position
        float f = handoffLast;
        float total = knownDurationSec > 0 ? knownDurationSec : handoffDurationSec;
        if (handoffWasPlaying && total > 0) {
            f += (now - handoffLastAt) / 1000f / total;
        }
        return Math.max(0f, Math.min(0.999f, f));
    }

    private void stopTelegram() {
        if (telegramStopped) return;
        telegramStopped = true;
        try {
            MediaController mc = MediaController.getInstance();
            if (mc.getPlayingMessageObject() != null) {
                mc.cleanupPlayer(true, true);
            }
        } catch (Throwable ignore) {
        }
    }

    private void togglePlay() {
        switch (state) {
            case ST_EMPTY:
                pickAudio();
                break;
            case ST_ERROR:
                retryCurrent();
                break;
            case ST_LOADING:
                pendingPlay = !pendingPlay;
                if (handoffActive && handoffMessage != null) {
                    try {
                        MediaController mc = MediaController.getInstance();
                        if (mc.getPlayingMessageObject() == handoffMessage) {
                            if (pendingPlay) mc.playMessage(handoffMessage); else mc.pauseMessage(handoffMessage);
                        }
                    } catch (Throwable ignore) {
                    }
                    handoffWasPlaying = pendingPlay;
                } else {
                    flash(pendingPlay ? "Tayyor bo'lgach avtomatik boshlanadi" : "Avtomatik boshlash bekor qilindi");
                }
                updatePlayButton();
                break;
            default:
                setPlaying(!playing);
                break;
        }
    }

    private void retryCurrent() {
        if (cur >= 0 && cur < list.size()) {
            loadTrack(cur, true, retryFraction);
        } else {
            pickAudio();
        }
    }

    private void setPlaying(boolean value) {
        if (value) {
            ParadoksMusicDsp.Engine e = engine;
            if (state != ST_READY || e == null) return;
            if (!requestFocus()) {
                flash("Boshqa ilova ovoz chiqarmoqda (qo'ng'iroq?) — keyinroq urinib ko'ring");
                return;
            }
            stopTelegram();
            if (e.pos >= e.pcm.frames - 2) {
                e.pos = 0;
                flushRequest = true;
            }
            registerNoisy();
        } else {
            unregisterNoisy();
            resumeOnFocus = false;
            abandonFocus();
        }
        playing = value;
        applyState();
    }

    private void onPrev() {
        ParadoksMusicDsp.Engine e = engine;
        if (state == ST_READY && e != null && e.pos / e.pcm.sampleRate > 3.0) {
            e.pos = 0;
            flushRequest = true;
            updateTime();
        } else if (cur > 0) {
            loadTrack(cur - 1, true, 0f);
        } else if (e != null) {
            e.pos = 0;
            flushRequest = true;
            updateTime();
        }
    }

    private void onNext() {
        if (cur >= 0 && cur < list.size() - 1) {
            loadTrack(cur + 1, true, 0f);
        } else {
            flash("Bu oxirgi trek");
        }
    }

    private void skip(int seconds) {
        ParadoksMusicDsp.Engine e = engine;
        if (state != ST_READY || e == null) return;
        double p = e.pos + seconds * (double) e.pcm.sampleRate;
        e.pos = Math.max(0, Math.min(e.pcm.frames - 2, p));
        flushRequest = true;
        updateTime();
    }

    private void refreshAB() {
        if (btnA == null || btnB == null) return;
        boolean hasA = markA > 0 || abOn;
        boolean hasB = markB > 0;
        btnA.setText(hasA ? "A  " + fmt(markA) : "A");
        btnB.setText(hasB ? "B  " + fmt(markB) : "B");
        setSel(btnA, hasA);
        setSel(btnB, hasB);
    }

    // ------------------------------------------------------------------------------------------
    // audio focus / headphones
    // ------------------------------------------------------------------------------------------

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (released) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocus = false;
                if (playing) {
                    playing = false;
                    unregisterNoisy();
                    applyState();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (playing) {
                    resumeOnFocus = true;
                    playing = false;
                    applyState();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                ducked = true;
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                ducked = false;
                if (resumeOnFocus && state == ST_READY) {
                    resumeOnFocus = false;
                    playing = true;
                    applyState();
                }
                break;
            default:
                break;
        }
    };

    @SuppressWarnings("deprecation")
    private boolean requestFocus() {
        try {
            AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return true;
            return am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Throwable e) {
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        try {
            AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocus(focusListener);
        } catch (Throwable ignore) {
        }
        ducked = false;
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (playing && !released) {
                setPlaying(false);
                flash("Quloqchin uzildi — pauza");
            }
        }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        try {
            ApplicationLoader.applicationContext.registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        } catch (Throwable ignore) {
        }
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        noisyRegistered = false;
        try {
            ApplicationLoader.applicationContext.unregisterReceiver(noisyReceiver);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------------------------------
    // state -> UI
    // ------------------------------------------------------------------------------------------

    private String buildSub() {
        switch (state) {
            case ST_EMPTY:
                return "Qurilmadan musiqa tanlang";
            case ST_LOADING:
                return loadingTitle().replace("…", "") + (loadProgress > 0f ? " · " + Math.round(loadProgress * 100) + "%" : "…");
            case ST_ERROR:
                return errorText;
            default:
                ParadoksMusicDsp.Pcm p = pcm;
                StringBuilder sb = new StringBuilder();
                if (p != null) {
                    sb.append(fmt(p.frames / (float) p.sampleRate));
                    if (bpmValue > 0) sb.append(" · BPM ≈ ").append(bpmValue);
                    sb.append(" · ").append(String.format(Locale.US, "%.1f kHz", p.sampleRate / 1000f));
                }
                return sb.toString();
        }
    }

    private boolean playingUi() {
        return playing || (state == ST_LOADING && pendingPlay);
    }

    private void updatePlayButton() {
        if (playIcon == null) return;
        boolean on = playingUi();
        playIcon.setIcon(on ? IC_PAUSE : IC_PLAY);
        if (miniPlayIcon != null) miniPlayIcon.setIcon(on ? IC_PAUSE : IC_PLAY);
        float a = state == ST_LOADING && !handoffActive ? 0.75f : 1f;
        playBtn.setAlpha(a);
    }

    /** Central refresh: header, overlay on the visualizer, buttons. */
    private void applyState() {
        syncBackground();
        if (heroTitle == null) return;
        Track t = cur >= 0 && cur < list.size() ? list.get(cur) : null;
        String title = t != null ? t.name : "Musiqiy ijodxona";
        heroTitle.setText(title);
        miniTitle.setText(title);
        heroSub.setText(buildSub());
        switch (state) {
            case ST_EMPTY:
                ovBox.setVisibility(View.VISIBLE);
                ovTitle.setText("Musiqa tanlanmagan");
                ovLine.setVisibility(View.GONE);
                ovAction.setVisibility(View.VISIBLE);
                ovAction.setText("Musiqa tanlash");
                ovAction.setOnClickListener(v -> pickAudio());
                break;
            case ST_LOADING:
                ovBox.setVisibility(View.VISIBLE);
                ovTitle.setText(loadingTitle() + (loadProgress > 0f ? " " + Math.round(loadProgress * 100) + "%" : ""));
                ovLine.setVisibility(View.VISIBLE);
                ovLine.setIndeterminate(loadProgress <= 0f);
                ovLine.setProgress(loadProgress);
                ovAction.setVisibility(View.GONE);
                break;
            case ST_ERROR:
                ovBox.setVisibility(View.VISIBLE);
                ovTitle.setText(errorText);
                ovLine.setVisibility(View.GONE);
                ovAction.setVisibility(View.VISIBLE);
                ovAction.setText("Qayta urinish");
                ovAction.setOnClickListener(v -> retryCurrent());
                break;
            default:
                ovBox.setVisibility(View.GONE);
                break;
        }
        seekBar.setEnabled(state == ST_READY);
        updatePlayButton();
        if (fragmentView != null && !detached) fragmentView.setKeepScreenOn(playing);
        if (!detached) updateMini();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            updateTime();
            AndroidUtilities.runOnUIThread(this, playing || state == ST_LOADING ? 100 : 300);
        }
    };

    private void updateTime() {
        if (timeNow == null) return;
        ParadoksMusicDsp.Engine e = engine;
        float curSec, totalSec, frac;
        if (state == ST_READY && e != null) {
            float rate = e.pcm.sampleRate;
            totalSec = e.pcm.frames / rate;
            curSec = Math.min(totalSec, (float) (e.pos / rate));
            frac = (float) (e.pos / Math.max(1, e.pcm.frames - 1));
        } else if (state == ST_LOADING && handoffActive) {
            frac = liveHandoffFraction();
            totalSec = knownDurationSec > 0 ? knownDurationSec : handoffDurationSec;
            curSec = frac * totalSec;
        } else {
            frac = 0f;
            curSec = 0f;
            totalSec = knownDurationSec;
        }
        frac = Math.max(0f, Math.min(1f, frac));
        if (!seeking) {
            timeNow.setText(fmt(curSec));
            seekBar.setProgress(Math.round(frac * 1000));
        }
        timeTotal.setText(totalSec > 0 ? fmt(totalSec) : "--:--");
        if (miniShown) {
            miniTime.setText(fmt(curSec) + (totalSec > 0 ? " / " + fmt(totalSec) : ""));
            miniLine.setProgress(frac);
        }
        updatePlayButton();
    }

    private static String fmt(float s) {
        if (Float.isNaN(s) || Float.isInfinite(s) || s < 0) return "0:00";
        int t = (int) s;
        return (t / 60) + ":" + (t % 60 < 10 ? "0" : "") + (t % 60);
    }

    // ------------------------------------------------------------------------------------------
    // audio thread
    // ------------------------------------------------------------------------------------------

    private AudioTrack createTrack(int rate) {
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min * 2, 16384);
        try {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Throwable e) {
            //noinspection deprecation
            return new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        }
    }

    private void audioLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        } catch (Throwable ignore) {
        }
        AudioTrack track = null;
        int trackRate = 0;
        final float[] l = new float[BLOCK], r = new float[BLOCK];
        final short[] out = new short[BLOCK * 2];
        try {
            while (!released) {
                try {
                    ParadoksMusicDsp.Engine e = engine;
                    if (e == null || !playing) {
                        if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
                        Thread.sleep(25);
                        continue;
                    }
                    if (track == null || trackRate != e.pcm.sampleRate) {
                        if (track != null) {
                            track.release();
                            track = null;
                        }
                        track = createTrack(e.pcm.sampleRate);
                        trackRate = e.pcm.sampleRate;
                    }
                    if (flushRequest) {
                        flushRequest = false;
                        track.pause();
                        track.flush();
                    }
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                    track.setVolume(ducked ? volume * 0.3f : volume);

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
                            unregisterNoisy();
                            applyState();
                            if (cur >= 0 && cur < list.size() - 1) loadTrack(cur + 1, true, 0f);
                        });
                        continue;
                    }
                    spectrum.analyze(l, r, n);
                    ParadoksMusicDsp.toShorts(l, r, n, out);
                    track.write(out, 0, n * 2);
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    // never let the audio thread die silently: report it and keep the studio usable
                    FileLog.e(t);
                    if (track != null) {
                        try {
                            track.release();
                        } catch (Throwable ignore) {
                        }
                        track = null;
                    }
                    playing = false;
                    final String msg = t.getMessage();
                    AndroidUtilities.runOnUIThread(() -> {
                        if (released) return;
                        applyState();
                        flash("Ovozni chiqarib bo'lmadi" + (msg != null && msg.length() < 60 ? ": " + msg : ""));
                    });
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
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

    private void exportProgress(String label, float fraction) {
        status(label + (fraction >= 0 ? " " + Math.round(fraction * 100) + "%" : ""));
        if (expLine != null) {
            expLine.setVisibility(View.VISIBLE);
            expLine.setIndeterminate(fraction < 0);
            expLine.setProgress(Math.max(0f, fraction));
        }
    }

    private void exportFinished(String result) {
        status("");
        if (expLine != null) expLine.setVisibility(View.GONE);
        if (expResult != null) expResult.setText(result == null ? "" : result);
    }

    private void exportAudio() {
        if (exporting) {
            cancelExport = true;
            return;
        }
        final ParadoksMusicDsp.Pcm data = pcm;
        if (data == null || cur < 0 || state != ST_READY) {
            flash("Avval musiqa tanlang va yuklanishini kuting.");
            return;
        }
        if (getParentActivity() == null) return;
        shareButton.setVisibility(View.GONE);
        expResult.setText("");
        if (sameAsSource()) {
            exportProgress("Asl fayl saqlanmoqda...", -1f);
            final Track t = list.get(cur);
            final String ext = ParadoksAudioFormats.extOf(t.path);
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
                    if (err != null) exportFinished("Xatolik: " + err); else saveToDevice(copy, 3, "audio/*");
                });
            }, "ParadoksMusicLabCopyOut").start();
            return;
        }
        exporting = true;
        cancelExport = false;
        exportButton.setText("Bekor qilish");
        final ParadoksMusicDsp.Params snapshot = params.copy();
        final int outFormat = effectiveFormat();
        final int outKbps = kbps;
        final String outTitle = baseName();
        final String outMime = ParadoksAudioFormats.mimeFor(outFormat);
        final File file = workFile(outTitle + (snapshot.anyEffect() ? "_fx" : "") + ParadoksAudioFormats.extFor(outFormat));
        exportProgress("Tayyorlanmoqda...", 0f);
        new Thread(() -> {
            boolean ok = false;
            String error = null;
            ParadoksMusicDsp.Sink sink = null;
            try {
                sink = ParadoksAudioFormats.open(outFormat, file, data.sampleRate, outKbps, outTitle);
                ok = ParadoksMusicDsp.render(data, snapshot, sink, f -> AndroidUtilities.runOnUIThread(() -> {
                    if (exporting) exportProgress("Tayyorlanmoqda...", f);
                }), () -> cancelExport || released);
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
                if (exportButton != null) exportButton.setText("Yuklab olish");
                if (released) return;
                if (done) {
                    saveToDevice(file, 3, outMime);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    exportFinished(err != null ? "Xatolik: " + err : "Bekor qilindi");
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
        if (data == null || cur < 0 || state != ST_READY) {
            flash("Avval musiqa tanlang va yuklanishini kuting.");
            return;
        }
        if (data.frames / (float) data.sampleRate > 10 * 60) {
            flash("Video uchun trek 10 daqiqadan oshmasin.");
            return;
        }
        videoExporting = true;
        cancelExport = false;
        if (playing) setPlaying(false);
        videoButton.setText("Bekor qilish");
        shareButton.setVisibility(View.GONE);
        expResult.setText("");
        final ParadoksMusicDsp.Params snapshot = params.copy();
        final ParadoksVizRenderer.Settings settings = viz.copy();
        final File file = workFile(baseName() + "_video.mp4");
        exportProgress("Video tayyorlanmoqda...", 0f);
        new Thread(() -> {
            boolean ok;
            String error = null;
            try {
                ok = ParadoksMusicVideo.render(data, snapshot, settings, file,
                        f -> AndroidUtilities.runOnUIThread(() -> {
                            if (videoExporting) exportProgress("Video tayyorlanmoqda...", f);
                        }),
                        () -> cancelExport || released);
            } catch (Throwable e) {
                error = e.getMessage();
                ok = false;
            }
            final boolean done = ok;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> {
                videoExporting = false;
                if (videoButton != null) videoButton.setText("Vizualizatorli video yaratish");
                if (released) return;
                if (done) {
                    saveToDevice(file, 1, "video/mp4");
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    exportFinished(err != null ? "Videoni yaratib bo'lmadi: " + err : "Bekor qilindi");
                }
            });
        }, "ParadoksMusicLabVideo").start();
    }

    /** type: 1 = Movies, 3 = Music (same codes as {@link MediaController#saveFile}). */
    private void saveToDevice(File file, int type, String mime) {
        if (getParentActivity() == null) return;
        MediaController.saveFile(file.getAbsolutePath(), getParentActivity(), type, file.getName(), mime, uri -> {
            lastSavedUri = uri;
            exportFinished("Tayyor! " + (type == 1 ? "Movies/Telegram" : "Music/Telegram") + " papkasiga va Saved Messages'ga saqlandi"
                    + (type == 1 && ParadoksMusicVideo.lastStats != null ? "\n" + ParadoksMusicVideo.lastStats : ""));
            flash("Saqlandi ✅");
            sendToSavedMessages(file, type, mime);
            if (shareButton != null) {
                shareButton.setTag(mime);
                shareButton.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Every finished export also goes to the user's Saved Messages (a video as a video, audio as a file). */
    private void sendToSavedMessages(File file, int type, String mime) {
        try {
            final int account = org.telegram.messenger.UserConfig.selectedAccount;
            final org.telegram.messenger.AccountInstance instance = org.telegram.messenger.AccountInstance.getInstance(account);
            final long self = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
            if (self == 0 || file == null || !file.exists()) return;
            Track t = cur >= 0 && cur < list.size() ? list.get(cur) : null;
            final String caption = "🎧 Musiqiy ijodxona" + (t != null && t.name != null ? ": " + t.name : "");
            final String path = file.getAbsolutePath();
            if (type == 1) {
                org.telegram.messenger.SendMessagesHelper.SendingMediaInfo info = new org.telegram.messenger.SendMessagesHelper.SendingMediaInfo();
                info.path = path;
                info.caption = caption;
                info.isVideo = true;
                ArrayList<org.telegram.messenger.SendMessagesHelper.SendingMediaInfo> infos = new ArrayList<>();
                infos.add(info);
                org.telegram.messenger.SendMessagesHelper.prepareSendingMedia(instance, infos, self, null, null, null, null, false, false, null, true, 0, 0, 0, false, null, null, 0L, false, 0L, 0L, null);
            } else {
                org.telegram.messenger.SendMessagesHelper.prepareSendingDocument(instance, path, path, null, caption, mime, self, null, null, null, null, null, true, 0, null, null, false);
            }
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
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
        format = p.getInt("fmt2", ParadoksAudioFormats.MP3);
        if (format < 0 || format >= ParadoksAudioFormats.COUNT || (format == ParadoksAudioFormats.OGG && !ParadoksAudioFormats.oggSupported())) {
            format = ParadoksAudioFormats.MP3;
        }
        kbps = p.getInt("kbps", 256);
        tab = p.getInt("tab", 0);
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
        e.putInt("fmt2", format).putInt("kbps", kbps).putInt("tab", tab).putInt("c1", viz.color1).putInt("c2", viz.color2).putInt("style", viz.style).putInt("bars", viz.bars)
                .putInt("height", viz.height).putBoolean("rainbow", viz.rainbow).putBoolean("glow", viz.glow).putBoolean("pulse", viz.pulse)
                .putBoolean("particles", viz.particles).putInt("bgx", viz.bgX).putInt("bgy", viz.bgY)
                .putInt("volume", Math.round(volume * 100)).putBoolean("loop", loop);
        e.apply();
    }

    // ------------------------------------------------------------------------------------------
    // custom views
    // ------------------------------------------------------------------------------------------

    /** Vector icons drawn on a 24 x 24 grid. */
    private static void drawIcon(Canvas c, int icon, Paint p, Path path, RectF rf, int color) {
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(2f);
        path.reset();
        switch (icon) {
            case IC_PLAY:
                path.moveTo(8f, 5.5f);
                path.lineTo(8f, 18.5f);
                path.lineTo(19f, 12f);
                path.close();
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                p.setStrokeWidth(1.6f);
                c.drawPath(path, p);
                break;
            case IC_PAUSE:
                p.setStyle(Paint.Style.FILL);
                rf.set(6.5f, 5f, 10f, 19f);
                c.drawRoundRect(rf, 1.2f, 1.2f, p);
                rf.set(14f, 5f, 17.5f, 19f);
                c.drawRoundRect(rf, 1.2f, 1.2f, p);
                break;
            case IC_PREV:
                p.setStyle(Paint.Style.FILL);
                rf.set(5.5f, 5f, 8f, 19f);
                c.drawRoundRect(rf, 1.2f, 1.2f, p);
                path.moveTo(19f, 5.5f);
                path.lineTo(19f, 18.5f);
                path.lineTo(9.5f, 12f);
                path.close();
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                p.setStrokeWidth(1.4f);
                c.drawPath(path, p);
                break;
            case IC_NEXT:
                p.setStyle(Paint.Style.FILL);
                rf.set(16f, 5f, 18.5f, 19f);
                c.drawRoundRect(rf, 1.2f, 1.2f, p);
                path.moveTo(5f, 5.5f);
                path.lineTo(5f, 18.5f);
                path.lineTo(14.5f, 12f);
                path.close();
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                p.setStrokeWidth(1.4f);
                c.drawPath(path, p);
                break;
            case IC_ADD:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(12f, 5f);
                path.lineTo(12f, 19f);
                path.moveTo(5f, 12f);
                path.lineTo(19f, 12f);
                c.drawPath(path, p);
                break;
            case IC_CLOSE:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(6f, 6f);
                path.lineTo(18f, 18f);
                path.moveTo(18f, 6f);
                path.lineTo(6f, 18f);
                c.drawPath(path, p);
                break;
            case IC_DOWNLOAD:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(12f, 4f);
                path.lineTo(12f, 15f);
                path.moveTo(7.5f, 10.5f);
                path.lineTo(12f, 15f);
                path.lineTo(16.5f, 10.5f);
                path.moveTo(5f, 19.5f);
                path.lineTo(19f, 19.5f);
                c.drawPath(path, p);
                break;
            case IC_VIDEO:
                p.setStyle(Paint.Style.STROKE);
                rf.set(3f, 6.5f, 16f, 17.5f);
                c.drawRoundRect(rf, 3f, 3f, p);
                path.moveTo(16f, 10.5f);
                path.lineTo(21f, 7.5f);
                path.lineTo(21f, 16.5f);
                path.lineTo(16f, 13.5f);
                path.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            case IC_SHARE:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(12f, 15f);
                path.lineTo(12f, 4f);
                path.moveTo(8f, 8f);
                path.lineTo(12f, 4f);
                path.lineTo(16f, 8f);
                path.moveTo(5f, 12f);
                path.lineTo(5f, 19f);
                path.quadTo(5f, 20f, 6f, 20f);
                path.lineTo(18f, 20f);
                path.quadTo(19f, 20f, 19f, 19f);
                path.lineTo(19f, 12f);
                c.drawPath(path, p);
                break;
            case IC_EXPAND:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(4f, 9f);
                path.lineTo(4f, 4f);
                path.lineTo(9f, 4f);
                path.moveTo(15f, 4f);
                path.lineTo(20f, 4f);
                path.lineTo(20f, 9f);
                path.moveTo(20f, 15f);
                path.lineTo(20f, 20f);
                path.lineTo(15f, 20f);
                path.moveTo(9f, 20f);
                path.lineTo(4f, 20f);
                path.lineTo(4f, 15f);
                c.drawPath(path, p);
                break;
            case IC_NOTE:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(8f, 17.5f, 3f, p);
                c.drawCircle(17f, 15.5f, 3f, p);
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(11f, 17.5f);
                path.lineTo(11f, 6f);
                path.lineTo(20f, 4f);
                path.lineTo(20f, 15.5f);
                c.drawPath(path, p);
                break;
            case IC_IMAGE:
                p.setStyle(Paint.Style.STROKE);
                rf.set(3.5f, 4.5f, 20.5f, 19.5f);
                c.drawRoundRect(rf, 3f, 3f, p);
                path.moveTo(4f, 18f);
                path.lineTo(9.5f, 13f);
                path.lineTo(13f, 16f);
                path.lineTo(16f, 12.5f);
                path.lineTo(20f, 17f);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(9f, 9.5f, 1.6f, p);
                break;
            case IC_CHEVRON:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(6f, 9.5f);
                path.lineTo(12f, 15.5f);
                path.lineTo(18f, 9.5f);
                c.drawPath(path, p);
                break;
            case IC_VOLUME:
            case IC_MUTE:
                p.setStyle(Paint.Style.FILL);
                path.moveTo(4f, 9.5f);
                path.lineTo(8f, 9.5f);
                path.lineTo(13f, 5.5f);
                path.lineTo(13f, 18.5f);
                path.lineTo(8f, 14.5f);
                path.lineTo(4f, 14.5f);
                path.close();
                c.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                path.reset();
                if (icon == IC_VOLUME) {
                    rf.set(9f, 8f, 17f, 16f);
                    path.arcTo(rf, -50f, 100f);
                    c.drawPath(path, p);
                    path.reset();
                    rf.set(8f, 5.5f, 20f, 18.5f);
                    path.arcTo(rf, -50f, 100f);
                    c.drawPath(path, p);
                } else {
                    path.moveTo(16f, 9.5f);
                    path.lineTo(21f, 14.5f);
                    path.moveTo(21f, 9.5f);
                    path.lineTo(16f, 14.5f);
                    c.drawPath(path, p);
                }
                break;
            case IC_RETRY:
                p.setStyle(Paint.Style.STROKE);
                rf.set(5f, 5f, 19f, 19f);
                path.arcTo(rf, -45f, 270f);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(7.05f, 3.6f);
                path.lineTo(7.05f, 7.05f);
                path.lineTo(10.5f, 7.05f);
                c.drawPath(path, p);
                break;
            case IC_CHECK:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(5f, 12.5f);
                path.lineTo(10f, 17.5f);
                path.lineTo(19f, 7.5f);
                c.drawPath(path, p);
                break;
            default:
                break;
        }
    }

    private static final class IconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rf = new RectF();
        private int icon;
        private int color;

        IconView(Context c, int icon, int color) {
            super(c);
            this.icon = icon;
            this.color = color;
        }

        void setIcon(int i) {
            if (icon != i) {
                icon = i;
                invalidate();
            }
        }

        void setColor(int c) {
            if (color != c) {
                color = c;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h) / 24f;
            canvas.save();
            canvas.translate((w - 24 * s) / 2f, (h - 24 * s) / 2f);
            canvas.scale(s, s);
            drawIcon(canvas, icon, p, path, rf, color);
            canvas.restore();
        }
    }

    /** Cover art (rounded) or a gradient placeholder with a music note. */
    private static final class CoverView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path iconPath = new Path();
        private final RectF rect = new RectF(), iconRect = new RectF();
        private final Matrix matrix = new Matrix();
        private final float radius;
        private Bitmap bmp;
        private BitmapShader shader;
        private LinearGradient gradient;

        CoverView(Context c, int radiusDp) {
            super(c);
            radius = dp(radiusDp);
        }

        void setBitmap(Bitmap b) {
            bmp = b;
            shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            gradient = new LinearGradient(0, 0, w, h, new int[]{0xFF7C3AED, 0xFF22D3EE}, null, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            rect.set(0, 0, w, h);
            if (bmp != null && shader != null && !bmp.isRecycled()) {
                float s = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
                matrix.setScale(s, s);
                matrix.postTranslate((w - bmp.getWidth() * s) / 2f, (h - bmp.getHeight() * s) / 2f);
                shader.setLocalMatrix(matrix);
                paint.setShader(shader);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.setShader(null);
            } else {
                paint.setShader(gradient);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.setShader(null);
                float s = Math.min(w, h) * 0.5f / 24f;
                canvas.save();
                canvas.translate((w - 24 * s) / 2f, (h - 24 * s) / 2f);
                canvas.scale(s, s);
                drawIcon(canvas, IC_NOTE, iconPaint, iconPath, iconRect, 0xFFFFFFFF);
                canvas.restore();
            }
        }
    }

    /** Thin rounded progress bar with an indeterminate (sliding) mode. */
    private static final class ProgressLine extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private LinearGradient gradient;
        private float value;
        private boolean indeterminate;

        ProgressLine(Context c) {
            super(c);
        }

        void setProgress(float v) {
            v = Math.max(0f, Math.min(1f, v));
            if (v != value) {
                value = v;
                invalidate();
            }
        }

        void setIndeterminate(boolean on) {
            if (indeterminate != on) {
                indeterminate = on;
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            gradient = new LinearGradient(0, 0, Math.max(1, w), 0, C_AC, C_AC2, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            paint.setShader(null);
            paint.setColor(0xFF2A2A40);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setShader(gradient);
            if (indeterminate) {
                float seg = w * 0.32f;
                float t = (SystemClock.elapsedRealtime() % 1200L) / 1200f;
                float x = -seg + t * (w + seg);
                rect.set(Math.max(0, x), 0, Math.min(w, x + seg), h);
                if (rect.width() > 0) canvas.drawRoundRect(rect, r, r, paint);
                postInvalidateOnAnimation();
            } else if (value > 0f) {
                rect.set(0, 0, Math.max(h, w * value), h);
                canvas.drawRoundRect(rect, r, r, paint);
            }
            paint.setShader(null);
        }
    }

    /** Live visualizer; redraws itself every frame while it is on screen. */
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
            if (playing) {
                postInvalidateOnAnimation();
            } else {
                postInvalidateDelayed(66);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                long now = System.currentTimeMillis();
                if (now - lastTap < 350) {
                    toggleBigViz();
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
