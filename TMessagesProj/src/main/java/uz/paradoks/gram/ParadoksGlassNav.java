package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * A floating "liquid glass" bottom navigation pill, in the style of the me20.studio glass-nav demo: a row of icons
 * inside a frosted pill, with the selected one followed by an animated highlight. Six interchangeable looks
 * (see the STYLE_* constants), chosen in Settings → Liquid Glass va ko'rinish → Pastki navigatsiya paneli.
 * <p>
 * The frosted/translucent look of every shape here (the pill itself, the lens, the bubbles, the floating button)
 * is drawn with the same {@link ParadoksGlass} renderer already used for message bubbles and round buttons, so this
 * matches the rest of the app's glass look instead of inventing a second one.
 * <p>
 * The physics (a position that eases toward a target with velocity and damping, run every frame while it has not
 * settled) mirrors the demo's own requestAnimationFrame loop, just translated to a View + postOnAnimation loop that
 * stops itself once things are visually at rest, so it costs nothing while idle.
 */
public class ParadoksGlassNav extends View {

    public static final int STYLE_LENS = 0;       // sliding glass lens behind the icon
    public static final int STYLE_NEON = 1;       // same, with a bright tinted rim/glow
    public static final int STYLE_BOUNCE = 2;     // the tapped icon pops up and settles with a little overshoot
    public static final int STYLE_WAVE = 3;       // the pill's top edge bulges up under the selected icon
    public static final int STYLE_SPOTLIGHT = 4;  // a soft cone of light from above picks out the selected icon
    public static final int STYLE_FAB = 5;        // the selected icon floats in its own circle above the pill

    public static final int STYLES_COUNT = 6;

    public enum Icon { HOME, CALL, PLUS, PROFILE, SAVED, CLOSE, REFRESH, MORE }

    public static final class Item {
        public final Icon icon;
        public final Runnable onClick;

        public Item(Icon icon, Runnable onClick) {
            this.icon = icon;
            this.onClick = onClick;
        }
    }

    private Item[] items = new Item[0];
    private int style = STYLE_LENS;
    private int selected = 0;
    private int tint = 1; // ParadoksGlass.TINTS index used for the highlight/lens/FAB (0 = colorless)

    // Shared "where the highlight currently is" physics, in item-index units (0f .. items.length-1f).
    private float posX, posV;
    private float targetX;
    private boolean dragging;

    // Per-item vertical "lift" springs, used by STYLE_BOUNCE (and, lightly, STYLE_FAB on selection change).
    private float[] lift = new float[0];
    private float[] liftV = new float[0];

    private boolean looping;
    private long lastFrameNs;

    private final ParadoksGlass glass = new ParadoksGlass();
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shapePath = new Path();
    private final Path wavePath = new Path();
    private final Rect tmpRect = new Rect();
    private final RectF pillRect = new RectF();

    public ParadoksGlassNav(Context context) {
        super(context);
        setWillNotDraw(false);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setColor(0xFFFFFFFF);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFFFFFFFF);
    }

    public void setItems(Item[] items) {
        this.items = items == null ? new Item[0] : items;
        lift = new float[this.items.length];
        liftV = new float[this.items.length];
        selected = Math.max(0, Math.min(selected, this.items.length - 1));
        posX = targetX = selected;
        invalidate();
    }

    public void setStyle(int style) {
        this.style = Math.max(0, Math.min(STYLES_COUNT - 1, style));
        invalidate();
    }

    public void setTint(int tint) {
        this.tint = tint;
        invalidate();
    }

    public void select(int index, boolean animate) {
        if (items.length == 0) {
            return;
        }
        index = Math.max(0, Math.min(items.length - 1, index));
        selected = index;
        targetX = index;
        if (!animate) {
            posX = index;
            posV = 0;
        }
        kick(index);
        startLoop();
    }

    private void kick(int index) {
        if (index >= 0 && index < liftV.length) {
            liftV[index] -= 2.6f; // a little upward "pop" spring kick, settles back to 0 on its own
        }
    }

    // ---- layout ----

    private static final int BAR_H_DP = 58;
    private static final int FAB_EXTRA_DP = 30;

    public static int preferredHeightDp(int style) {
        return BAR_H_DP + (style == STYLE_FAB ? FAB_EXTRA_DP : 0) + (style == STYLE_BOUNCE ? 14 : 0);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = dp(preferredHeightDp(style));
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h);
    }

    private int indexAt(float x) {
        if (items.length == 0) {
            return -1;
        }
        float pad = dp(10);
        float w = getWidth() - pad * 2;
        int idx = (int) ((x - pad) / Math.max(1f, w) * items.length);
        return Math.max(0, Math.min(items.length - 1, idx));
    }

    // ---- touch: drag to preview (like pointermove in the web demo), release to commit ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (items.length == 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                dragging = true;
                int idx = indexAt(event.getX());
                targetX = idx;
                startLoop();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int idx = indexAt(event.getX());
                dragging = false;
                select(idx, true);
                if (items[idx].onClick != null) {
                    items[idx].onClick.run();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                targetX = selected;
                startLoop();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    // ---- physics loop ----

    private void startLoop() {
        if (looping) {
            return;
        }
        looping = true;
        lastFrameNs = System.nanoTime();
        postOnAnimation(this::frame);
    }

    private void frame() {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, Math.max(0f, (now - lastFrameNs) / 1_000_000_000f));
        lastFrameNs = now;
        boolean settled = step(dt);
        invalidate();
        if (settled) {
            looping = false;
        } else {
            postOnAnimation(this::frame);
        }
    }

    private boolean step(float dt) {
        boolean settled = true;
        final int sub = 3;
        final float stepDt = dt / sub;
        for (int s = 0; s < sub; s++) {
            posV += (targetX - posX) * 230f * stepDt;
            posV *= Math.pow(0.80f, stepDt * 60f);
            posX += posV * stepDt;
        }
        if (Math.abs(targetX - posX) > 0.003f || Math.abs(posV) > 0.02f) {
            settled = false;
        }
        for (int i = 0; i < lift.length; i++) {
            final float stepDt2 = dt / sub;
            for (int s = 0; s < sub; s++) {
                liftV[i] += (0f - lift[i]) * 170f * stepDt2;
                liftV[i] *= Math.pow(0.78f, stepDt2 * 60f);
                lift[i] += liftV[i] * stepDt2;
            }
            if (Math.abs(lift[i]) > 0.003f || Math.abs(liftV[i]) > 0.02f) {
                settled = false;
            }
        }
        return settled;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (items.length > 0) {
            posX = targetX = selected;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        looping = false;
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        if (items.length == 0 || getWidth() == 0) {
            return;
        }
        float pad = dp(6);
        float barH = dp(BAR_H_DP);
        float barTop = getHeight() - barH - pad - (style == STYLE_BOUNCE ? dp(10) : 0);
        pillRect.set(pad, barTop, getWidth() - pad, barTop + barH);

        // 1) the pill itself: a glass capsule.
        shapePath.reset();
        shapePath.addRoundRect(pillRect, barH / 2f, barH / 2f, Path.Direction.CW);
        if (style == STYLE_WAVE) {
            addWaveBulge(shapePath, pillRect);
        }
        tmpRect.set((int) pillRect.left, (int) pillRect.top, (int) pillRect.right, (int) pillRect.bottom);
        glass.draw(canvas, shapePath, tmpRect, 1f, ParadoksGlass.ROLE_IN, /* level */ 3, 0, /* edge */ 2, false, null);

        // 2) the highlight behind the followed/selected icon.
        float hx = centerForPos(posX);
        float hy = pillRect.centerY();
        switch (style) {
            case STYLE_LENS:
                drawLens(canvas, hx, hy, barH, 0.6f, false);
                break;
            case STYLE_NEON:
                drawLens(canvas, hx, hy, barH, 1f, true);
                break;
            case STYLE_SPOTLIGHT:
                drawSpotlight(canvas, hx, pillRect);
                break;
            case STYLE_FAB:
                // the pill itself has no extra highlight; the floating circle below carries it.
                break;
            default:
                break; // WAVE's highlight is the bulge already drawn into the pill path; BOUNCE needs none.
        }

        // 3) icons.
        for (int i = 0; i < items.length; i++) {
            float cx = centerForPos(i);
            float cy = pillRect.centerY();
            float liftPx = -lift[i] * dp(14);
            boolean isSelected = i == selected;
            float closeness = Math.max(0f, 1f - Math.abs(posX - i));

            if (style == STYLE_FAB && isSelected) {
                drawFab(canvas, cx, pillRect.top, barH);
                cy = pillRect.top - dp(FAB_EXTRA_DP) * 0.55f;
            } else if (style == STYLE_BOUNCE) {
                cy += liftPx;
            }

            int save = canvas.save();
            canvas.translate(cx, cy);
            if (style == STYLE_BOUNCE) {
                canvas.scale(1f + Math.max(0f, -lift[i]) * 0.22f, 1f + Math.max(0f, -lift[i]) * 0.22f);
            }
            float alpha = 0.55f + 0.45f * (style == STYLE_FAB ? (isSelected ? 1f : 0f) : closeness);
            iconPaint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
            iconPaint.setStrokeWidth(dpf2(1.8f + (style == STYLE_NEON ? closeness * 0.6f : 0f)));
            drawIcon(canvas, items[i].icon, dp(10));
            canvas.restoreToCount(save);
        }
    }

    private float centerForPos(float pos) {
        float pad = dp(10);
        float w = getWidth() - pad * 2;
        int n = Math.max(1, items.length);
        return pad + w * (pos + 0.5f) / n;
    }

    private void addWaveBulge(Path path, RectF bounds) {
        float cx = centerForPos(posX);
        float bumpW = Math.min(bounds.width() * 0.32f, dp(74));
        float bumpH = dp(14);
        path.reset();
        float top = bounds.top;
        float r = bounds.height() / 2f;
        path.moveTo(bounds.left + r, top);
        path.lineTo(cx - bumpW, top);
        path.cubicTo(
                cx - bumpW * 0.42f, top,
                cx - bumpW * 0.42f, top - bumpH,
                cx, top - bumpH);
        path.cubicTo(
                cx + bumpW * 0.42f, top - bumpH,
                cx + bumpW * 0.42f, top,
                cx + bumpW, top);
        path.lineTo(bounds.right - r, top);
        path.arcTo(bounds.right - 2 * r, bounds.top, bounds.right, bounds.top + 2 * r, -90, 180, false);
        path.lineTo(bounds.left + r, bounds.bottom);
        path.arcTo(bounds.left, bounds.top, bounds.left + 2 * r, bounds.top + 2 * r, 90, 180, false);
        path.close();
    }

    private void drawLens(Canvas canvas, float cx, float cy, float barH, float intensity, boolean neon) {
        float r = barH * 0.38f;
        shapePath.reset();
        shapePath.addCircle(cx, cy, r, Path.Direction.CW);
        tmpRect.set((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));
        glass.draw(canvas, shapePath, tmpRect, 1f, ParadoksGlass.ROLE_OUT, 2, tint, neon ? 3 : 1, false, null);
        if (neon) {
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setColor(ParadoksGlass.TINTS[Math.max(0, Math.min(ParadoksGlass.TINTS.length - 1, tint))]);
            glowPaint.setStrokeWidth(dpf2(2f));
            glowPaint.setAlpha(Math.round(180 * intensity));
            canvas.drawCircle(cx, cy, r, glowPaint);
        }
    }

    private void drawSpotlight(Canvas canvas, float cx, RectF bar) {
        float topY = bar.top - dp(26);
        float coneHalf = dp(26);
        android.graphics.LinearGradient gradient = new android.graphics.LinearGradient(
                cx, topY, cx, bar.top,
                0x00FFFFFF, 0x55FFFFFF, android.graphics.Shader.TileMode.CLAMP);
        glowPaint.setShader(gradient);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setAlpha(255);
        shapePath.reset();
        shapePath.moveTo(cx - 2, topY);
        shapePath.lineTo(cx - coneHalf, bar.top);
        shapePath.lineTo(cx + coneHalf, bar.top);
        shapePath.lineTo(cx + 2, topY);
        shapePath.close();
        int save = canvas.save();
        canvas.clipPath(shapePath);
        canvas.drawRect(cx - coneHalf, topY, cx + coneHalf, bar.top, glowPaint);
        canvas.restoreToCount(save);
        glowPaint.setShader(null);
    }

    private void drawFab(Canvas canvas, float cx, float barTop, float barH) {
        float r = dp((BAR_H_DP + FAB_EXTRA_DP) / 2f) * 0.42f;
        float cy = barTop - r * 0.35f;
        shapePath.reset();
        shapePath.addCircle(cx, cy, r, Path.Direction.CW);
        tmpRect.set((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));
        glass.draw(canvas, shapePath, tmpRect, 1f, ParadoksGlass.ROLE_OUT, 3, tint, 3, false, null);
    }

    // ---- tiny hand-drawn line icons, consistent weight, drawn around (0,0) in a 2*r box ----

    private void drawIcon(Canvas canvas, Icon icon, float r) {
        switch (icon) {
            case HOME:
                canvas.drawLines(new float[]{
                        -r, 0.1f * r, 0, -r,
                        0, -r, r, 0.1f * r,
                }, iconPaint);
                canvas.drawRect(-0.62f * r, -0.05f * r, 0.62f * r, r, iconPaint);
                break;
            case CALL:
                shapePath.reset();
                shapePath.moveTo(-0.7f * r, -r);
                shapePath.cubicTo(-r, -0.2f * r, 0.2f * r, r, r, 0.7f * r);
                shapePath.cubicTo(r, 0.1f * r, 0.4f * r, 0.4f * r, 0.1f * r, 0.1f * r);
                shapePath.cubicTo(-0.2f * r, -0.2f * r, 0.2f * r, -0.4f * r, -0.1f * r, -0.9f * r);
                shapePath.close();
                canvas.drawPath(shapePath, iconPaint);
                break;
            case PLUS:
                canvas.drawLine(-r, 0, r, 0, iconPaint);
                canvas.drawLine(0, -r, 0, r, iconPaint);
                break;
            case PROFILE:
                canvas.drawCircle(0, -0.45f * r, 0.42f * r, iconPaint);
                shapePath.reset();
                shapePath.addArc(-r, 0.1f * r, r, 2.1f * r, 200, 140);
                canvas.drawPath(shapePath, iconPaint);
                break;
            case SAVED:
                shapePath.reset();
                shapePath.moveTo(-0.6f * r, -r);
                shapePath.lineTo(-0.6f * r, r);
                shapePath.lineTo(0, 0.25f * r);
                shapePath.lineTo(0.6f * r, r);
                shapePath.lineTo(0.6f * r, -r);
                shapePath.close();
                canvas.drawPath(shapePath, iconPaint);
                break;
            case CLOSE:
                canvas.drawLine(-0.7f * r, -0.7f * r, 0.7f * r, 0.7f * r, iconPaint);
                canvas.drawLine(-0.7f * r, 0.7f * r, 0.7f * r, -0.7f * r, iconPaint);
                break;
            case REFRESH:
                shapePath.reset();
                shapePath.addArc(-r, -r, r, r, -40, 260);
                canvas.drawPath(shapePath, iconPaint);
                canvas.drawLine(r * 0.75f, -r * 0.75f, r, -r * 0.2f, iconPaint);
                canvas.drawLine(r * 0.75f, -r * 0.75f, r * 0.2f, -r, iconPaint);
                break;
            case MORE:
                dotPaint.setAlpha(iconPaint.getAlpha());
                canvas.drawCircle(-0.55f * r, 0, dpf2(1.6f), dotPaint);
                canvas.drawCircle(0, 0, dpf2(1.6f), dotPaint);
                canvas.drawCircle(0.55f * r, 0, dpf2(1.6f), dotPaint);
                break;
        }
    }
}
