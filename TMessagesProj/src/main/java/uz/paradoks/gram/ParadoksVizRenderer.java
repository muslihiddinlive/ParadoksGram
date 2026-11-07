package uz.paradoks.gram;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;

/**
 * Canvas port of the web visualizer of "Music Vibe Lab" (bars / mirror / circle / wave, glow, pulse,
 * particles, background image). Used both for the live view and for the exported video.
 */
public final class ParadoksVizRenderer {

    public static final int STYLE_BAR = 0, STYLE_MIRROR = 1, STYLE_CIRCLE = 2, STYLE_WAVE = 3;
    public static final String[] STYLE_NAMES = {"Ustun", "Ko'zgu", "Doira", "To'lqin"};

    public static final class Settings {
        public int color1 = 0xFF7C3AED;
        public int color2 = 0xFF22D3EE;
        public int bars = 64;
        public int style = STYLE_BAR;
        public int height = 100; // percent, 20..200
        public boolean rainbow;
        public boolean glow = true;
        public boolean pulse = true;
        public boolean particles = true;
        public Bitmap background;
        public int bgX; // -100..100
        public int bgY;

        public Settings copy() {
            Settings s = new Settings();
            s.color1 = color1;
            s.color2 = color2;
            s.bars = bars;
            s.style = style;
            s.height = height;
            s.rainbow = rainbow;
            s.glow = glow;
            s.pulse = pulse;
            s.particles = particles;
            s.background = background;
            s.bgX = bgX;
            s.bgY = bgY;
            return s;
        }
    }

    private static final float LO = 40f, HI = 14000f, RANGE = 40f;

    private final Settings s;
    private float[] lvl = new float[0], pk = new float[0], pv = new float[0];
    private float ceil = -38f, bass, prevBass;
    private final ArrayList<float[]> particles = new ArrayList<>(); // x, y, vx, vy, life
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path(), path2 = new Path(), caps = new Path();
    private final RectF rect = new RectF();
    private final java.util.Random random = new java.util.Random();

    public ParadoksVizRenderer(Settings settings) {
        this.s = settings;
    }

    /** Current bass energy 0..1 (used by the screen to tint its background). */
    public float bass() {
        return bass;
    }

    public int accentColor(long nowMs) {
        return s.rainbow ? hsl((nowMs / 30f) % 360f + 70f, 0.9f, 0.6f) : s.color2;
    }

    private static int hsl(float h, float sat, float light) {
        h = ((h % 360f) + 360f) % 360f;
        float c = (1f - Math.abs(2f * light - 1f)) * sat;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = light - c / 2f;
        float r, g, b;
        switch ((int) (h / 60f)) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return Color.rgb(Math.round((r + m) * 255f), Math.round((g + m) * 255f), Math.round((b + m) * 255f));
    }

    /**
     * @param live  false when nothing is playing (flat, idle bars)
     * @param dpr   density used for line widths
     */
    public void draw(Canvas g, int W, int H, float dpr, ParadoksMusicDsp.Spectrum sp, int sampleRate, boolean live, long nowMs) {
        final int N = Math.max(8, s.bars);
        final float HGT = s.height / 100f;
        final float hue = nowMs / 30f % 360f;
        final int k1 = s.rainbow ? hsl(hue, 0.9f, 0.55f) : s.color1;
        final int k2 = s.rainbow ? hsl(hue + 70f, 0.9f, 0.6f) : s.color2;

        g.drawColor(0xFF07070D);
        if (s.background != null && !s.background.isRecycled()) {
            drawBackground(g, W, H);
        }
        if (s.pulse) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(k2);
            paint.setAlpha((int) (255 * Math.min(1f, bass * 0.14f)));
            g.drawRect(0, 0, W, H, paint);
            paint.setAlpha(255);
        }

        if (lvl.length != N) {
            lvl = new float[N];
            pk = new float[N];
            pv = new float[N];
        }
        // ---- spectrum -> band levels (log spaced 40 Hz .. 14 kHz)
        final float hz = sampleRate / (float) ParadoksMusicDsp.Spectrum.SIZE;
        final float ratio = HI / LO;
        final float[] db = new float[N];
        float top = -200f;
        for (int i = 0; i < N; i++) {
            float d = -200f;
            if (live) {
                float f1 = (float) (LO * Math.pow(ratio, i / (double) N));
                float f2 = (float) (LO * Math.pow(ratio, (i + 1) / (double) N));
                float a = f1 / hz, b = f2 / hz;
                if (b - a < 1) {
                    float p = (a + b) / 2;
                    int k = (int) p;
                    float t = p - k;
                    d = bin(sp, k) * (1 - t) + bin(sp, k + 1) * t;
                } else {
                    for (int k = (int) Math.ceil(a); k <= (int) b; k++) {
                        float x = bin(sp, k);
                        if (x > d) d = x;
                    }
                }
                d += 4f * (float) (Math.log(Math.sqrt(f1 * f2) / 1000.0) / Math.log(2));
            }
            db[i] = d;
            if (d > top) top = d;
        }
        ceil = top > ceil ? ceil + (top - ceil) * 0.5f : ceil - 0.1f;
        final float eff = Math.max(ceil, -38f);
        float bs = 0;
        final int nb = Math.max(2, (int) (N * 0.1f));
        for (int i = 0; i < N; i++) {
            float v = Math.max(0f, Math.min(1f, (db[i] - (eff - RANGE)) / RANGE));
            v = (float) Math.pow(v, 1.3);
            lvl[i] += (v - lvl[i]) * (v > lvl[i] ? 0.7f : 0.2f);
            if (lvl[i] >= pk[i]) {
                pk[i] = lvl[i];
                pv[i] = 0;
            } else {
                pv[i] += 0.0014f;
                pk[i] = Math.max(0f, pk[i] - pv[i]);
            }
            if (i < nb) bs += lvl[i];
        }
        bass = bass * 0.6f + (bs / nb) * 0.4f;

        final float glowW = s.glow ? (4 + bass * 26) * dpr : 0;
        paint.setShader(null);

        if (s.style == STYLE_CIRCLE) {
            float cx = W / 2f, cy = H / 2f, m = Math.min(W, H);
            float r0 = m * 0.2f * (1 + bass * 0.1f), len = m * 0.3f * HGT;
            Shader sh = new RadialGradient(cx, cy, r0 + len, new int[]{k1, k1, k2}, new float[]{0f, r0 / (r0 + len), 1f}, Shader.TileMode.CLAMP);
            path.reset();
            for (int i = 0; i < N; i++) {
                double a = Math.PI * (i + 0.5) / N;
                float l = 3 * dpr + lvl[i] * len;
                for (int side = -1; side <= 1; side += 2) {
                    float x = (float) Math.sin(a) * side, y = (float) Math.cos(a);
                    path.moveTo(cx + x * r0, cy + y * r0);
                    path.lineTo(cx + x * (r0 + l), cy + y * (r0 + l));
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float w = Math.max(2f, (float) Math.PI * r0 / N * 0.8f);
            if (glowW > 0) {
                paint.setShader(null);
                paint.setColor(k2);
                paint.setAlpha(46);
                paint.setStrokeWidth(w + glowW);
                g.drawPath(path, paint);
                paint.setAlpha(255);
            }
            paint.setShader(sh);
            paint.setStrokeWidth(w);
            g.drawPath(path, paint);
        } else if (s.style == STYLE_WAVE) {
            Shader sh = new LinearGradient(0, 0, W, 0, k1, k2, Shader.TileMode.CLAMP);
            path.reset();
            float step = Math.max(1f, W / 400f);
            boolean first = true;
            for (float x = 0; x <= W; x += step) {
                float v = live ? sp.wave[Math.min(ParadoksMusicDsp.Spectrum.SIZE - 1, (int) (x / W * ParadoksMusicDsp.Spectrum.SIZE))] * 2.2f : 0f;
                float y = H / 2f + v * H * 0.45f * HGT * (1 + bass * 0.5f);
                if (first) {
                    path.moveTo(x, y);
                    first = false;
                } else {
                    path.lineTo(x, y);
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            if (glowW > 0) {
                paint.setShader(null);
                paint.setColor(k2);
                paint.setAlpha(46);
                paint.setStrokeWidth(3 * dpr + glowW);
                g.drawPath(path, paint);
                paint.setAlpha(255);
            }
            paint.setShader(sh);
            paint.setStrokeWidth(3 * dpr);
            g.drawPath(path, paint);
        } else {
            final boolean mir = s.style == STYLE_MIRROR;
            final float base = mir ? H / 2f : H, mh = base, bw = W / (float) N;
            Shader sh = mir ? new LinearGradient(0, base, 0, 0, k1, k2, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, H, 0, 0, k1, k2, Shader.TileMode.CLAMP);
            path.reset();
            path2.reset();
            caps.reset();
            for (int i = 0; i < N; i++) {
                float hh = Math.max(3 * dpr, lvl[i] * mh * 0.96f * HGT);
                float x = i * bw + bw * 0.15f, w = bw * 0.7f;
                path.addRect(x, base - hh, x + w, base, Path.Direction.CW);
                if (mir) path2.addRect(x, base, x + w, base + hh, Path.Direction.CW);
                float py = base - pk[i] * mh * 0.96f - 4 * dpr;
                caps.addRect(x, py, x + w, py + 2.5f * dpr, Path.Direction.CW);
            }
            paint.setStyle(Paint.Style.FILL);
            if (glowW > 0) {
                paint.setShader(null);
                paint.setColor(k2);
                paint.setAlpha(40);
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(glowW * 0.6f);
                g.drawPath(path, paint);
                if (mir) g.drawPath(path2, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setAlpha(255);
            }
            paint.setShader(sh);
            g.drawPath(path, paint);
            if (mir) {
                paint.setAlpha(128);
                g.drawPath(path2, paint);
                paint.setAlpha(255);
            }
            paint.setShader(null);
            paint.setColor(k2);
            g.drawPath(caps, paint);
        }

        // ---- particles
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        if (s.particles) {
            if (bass > 0.45f && bass - prevBass > 0.05f && particles.size() < 220) {
                for (int i = 0; i < 10; i++) {
                    particles.add(new float[]{random.nextFloat() * W, s.style == STYLE_MIRROR ? H / 2f : H,
                            (random.nextFloat() - 0.5f) * 2 * dpr, -(2 + random.nextFloat() * 4) * dpr, 1f});
                }
            }
            for (int i = particles.size() - 1; i >= 0; i--) {
                float[] p = particles.get(i);
                p[0] += p[2];
                p[1] += p[3];
                p[4] -= 0.012f;
                if (p[4] <= 0) {
                    particles.remove(i);
                    continue;
                }
                paint.setColor(k2);
                paint.setAlpha((int) (255 * p[4]));
                g.drawCircle(p[0], p[1], 2.5f * dpr * p[4] + 1, paint);
            }
            paint.setAlpha(255);
        } else {
            particles.clear();
        }
        prevBass = bass;
    }

    private static float bin(ParadoksMusicDsp.Spectrum sp, int k) {
        if (k < 0) k = 0;
        if (k >= sp.db.length) k = sp.db.length - 1;
        float v = sp.db[k];
        return v > -140f ? v : -140f;
    }

    /** "cover"-like fit, dimmed so the bars stay readable; can be nudged horizontally / vertically. */
    private void drawBackground(Canvas g, int W, int H) {
        Bitmap bmp = s.background;
        float ir = bmp.getWidth() / (float) bmp.getHeight(), cr = W / (float) H;
        float dw, dh, dx, dy;
        if (ir > cr) {
            dh = H;
            dw = H * ir;
            dx = (W - dw) / 2f;
            dy = 0;
        } else {
            dw = W;
            dh = W / ir;
            dx = 0;
            dy = (H - dh) / 2f;
        }
        dx *= 1 + s.bgX / 100f;
        dy *= 1 + s.bgY / 100f;
        rect.set(dx, dy, dx + dw, dy + dh);
        bgPaint.setAlpha(90);
        g.drawBitmap(bmp, null, rect, bgPaint);
    }
}
