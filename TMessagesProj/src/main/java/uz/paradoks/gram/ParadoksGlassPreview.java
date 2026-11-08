package uz.paradoks.gram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

/**
 * Live test field for the liquid glass settings: a small chat on a colourful wallpaper. Every option is read from
 * ParadoksConfig while drawing, so calling invalidate() after a change shows the result at once. The bubbles are
 * drawn by the same ParadoksGlass code the real chat uses. Refraction is shown with a really blurred and lens-bent
 * copy of the wallpaper (it is known here); the real chat has no access to the wallpaper and imitates it.
 */
public class ParadoksGlassPreview extends View {

    private static final String[] TEXTS = {
            "Bro, ParadoksGram'dagi Liquid Glass juda zo'r chiqibdi!",
            "Rostmi? Menga ham ko'rsatchi",
            "Xabarlar shishadek yaltirab turibdi, chekkasida nur o'ynayapti",
            "Sinish effekti-chi, bormi?",
            "Bor! Sozlamani o'zgartir, natija shu yerda darrov ko'rinadi"
    };
    private static final boolean[] OUT = {false, true, false, true, false};
    private static final String SENDER = "Muslihiddin";

    private final ParadoksGlass glass = new ParadoksGlass();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint();
    private final Paint wallpaperPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rectF = new RectF();
    private final float[] radii = new float[8];

    private final StaticLayout[] layouts = new StaticLayout[TEXTS.length];
    private final Rect[] rects = new Rect[TEXTS.length];
    private final Bitmap[] blurred = new Bitmap[TEXTS.length];
    private Bitmap wallpaper;
    private int headerHeight;
    private int nameHeight;

    public ParadoksGlassPreview(Context context) {
        super(context);
        textPaint.setTextSize(AndroidUtilities.dp(15));
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setShadowLayer(AndroidUtilities.dp(1.5f), 0, AndroidUtilities.dp(0.5f), 0x99000000);
        namePaint.setTextSize(AndroidUtilities.dp(13));
        namePaint.setTypeface(Typeface.DEFAULT_BOLD);
        namePaint.setColor(0xFF8FDCFF);
        namePaint.setShadowLayer(AndroidUtilities.dp(1.5f), 0, AndroidUtilities.dp(0.5f), 0x99000000);
        titlePaint.setTextSize(AndroidUtilities.dp(16));
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        titlePaint.setColor(0xFFFFFFFF);
        subtitlePaint.setTextSize(AndroidUtilities.dp(12));
        subtitlePaint.setColor(0xFFB8C4D6);
        headerPaint.setColor(0xB3101826);
        headerHeight = AndroidUtilities.dp(48);
        nameHeight = AndroidUtilities.dp(17);
        for (int i = 0; i < rects.length; i++) {
            rects[i] = new Rect();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = layoutBubbles(width);
        setMeasuredDimension(width, height);
    }

    private int layoutBubbles(int width) {
        int gap = AndroidUtilities.dp(8);
        int side = AndroidUtilities.dp(10);
        int padX = AndroidUtilities.dp(12);
        int padY = AndroidUtilities.dp(8);
        int maxText = Math.max(AndroidUtilities.dp(80), (int) (width * 0.74f) - padX * 2);
        int y = headerHeight + AndroidUtilities.dp(12);
        for (int i = 0; i < TEXTS.length; i++) {
            StaticLayout layout = new StaticLayout(TEXTS[i], textPaint, maxText, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
            layouts[i] = layout;
            float widest = 0;
            for (int line = 0; line < layout.getLineCount(); line++) {
                widest = Math.max(widest, layout.getLineWidth(line));
            }
            int extra = OUT[i] ? 0 : nameHeight;
            int bw = (int) Math.ceil(widest) + padX * 2;
            if (!OUT[i]) {
                bw = Math.max(bw, (int) Math.ceil(namePaint.measureText(SENDER)) + padX * 2);
            }
            int bh = layout.getHeight() + padY * 2 + extra;
            int left = OUT[i] ? width - side - bw : side;
            rects[i].set(left, y, left + bw, y + bh);
            blurred[i] = null;
            y += bh + gap;
        }
        return y + AndroidUtilities.dp(4);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        for (int i = 0; i < blurred.length; i++) {
            blurred[i] = null;
        }
        wallpaper = null;
        if (w > 0 && h > 0) {
            try {
                wallpaper = makeWallpaper(w, h);
            } catch (Throwable e) {
                wallpaper = null;
            }
        }
    }

    /** Colourful backdrop with a few diagonal lines, so blur and bending of the glass are easy to see. */
    private Bitmap makeWallpaper(int w, int h) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, 0xFF1C2B52, 0xFF4A2160, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        float[][] spots = {
                {0.12f, 0.22f, 0.55f}, {0.88f, 0.18f, 0.50f}, {0.50f, 0.50f, 0.45f},
                {0.14f, 0.82f, 0.50f}, {0.86f, 0.78f, 0.55f}
        };
        int[] colors = {0xFFFF6B6B, 0xFF4ECDC4, 0xFFFFE66D, 0xFF5B8DEF, 0xFFB65CFF};
        float base = Math.max(w, h);
        for (int i = 0; i < spots.length; i++) {
            float cx = w * spots[i][0];
            float cy = h * spots[i][1];
            float r = base * spots[i][2];
            p.setShader(new RadialGradient(cx, cy, r, colors[i], colors[i] & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, p);
        }
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(AndroidUtilities.dp(2));
        p.setColor(0x38FFFFFF);
        int step = AndroidUtilities.dp(34);
        for (int x = -h; x < w; x += step) {
            canvas.drawLine(x, h, x + h, 0, p);
        }
        return bitmap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (wallpaper != null && !wallpaper.isRecycled()) {
            canvas.drawBitmap(wallpaper, 0, 0, wallpaperPaint);
        } else {
            canvas.drawColor(0xFF22304F);
        }
        canvas.drawRect(0, 0, w, headerHeight, headerPaint);
        canvas.drawText("ParadoksGram", AndroidUtilities.dp(16), AndroidUtilities.dp(21), titlePaint);
        canvas.drawText(SENDER + " yozmoqda...", AndroidUtilities.dp(16), AndroidUtilities.dp(39), subtitlePaint);

        int level = ParadoksConfig.glassBubbleLevel();
        boolean incomingGlass = ParadoksConfig.glassIncoming();
        boolean refract = ParadoksConfig.glassRefract();
        float corner = AndroidUtilities.dp(16);
        float small = AndroidUtilities.dp(5);
        for (int i = 0; i < TEXTS.length; i++) {
            Rect r = rects[i];
            if (r.isEmpty()) {
                continue;
            }
            rectF.set(r);
            // top-left, top-right, bottom-right, bottom-left (x and y each); the "tail" corner is the small one
            float brx = OUT[i] ? small : corner;
            float blx = OUT[i] ? corner : small;
            radii[0] = corner; radii[1] = corner;
            radii[2] = corner; radii[3] = corner;
            radii[4] = brx; radii[5] = brx;
            radii[6] = blx; radii[7] = blx;
            path.reset();
            path.addRoundRect(rectF, radii, Path.Direction.CW);

            boolean glassOn = level > 0 && (OUT[i] || incomingGlass);
            if (glassOn) {
                Bitmap backdrop = null;
                if (refract) {
                    if (blurred[i] == null) {
                        blurred[i] = ParadoksGlass.blurRegion(wallpaper, r);
                    }
                    backdrop = blurred[i];
                }
                glass.draw(canvas, path, r, 1f, backdrop);
            } else {
                solid.setColor(OUT[i] ? 0xFF3E8DE3 : 0xFF2B3544);
                canvas.drawPath(path, solid);
            }

            float tx = r.left + AndroidUtilities.dp(12);
            float ty = r.top + AndroidUtilities.dp(8);
            if (!OUT[i]) {
                canvas.drawText(SENDER, tx, ty + AndroidUtilities.dp(12), namePaint);
                ty += nameHeight;
            }
            canvas.save();
            canvas.translate(tx, ty);
            layouts[i].draw(canvas);
            canvas.restore();
        }
    }
}
