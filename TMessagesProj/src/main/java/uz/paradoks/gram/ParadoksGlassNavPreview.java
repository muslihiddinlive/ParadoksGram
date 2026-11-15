package uz.paradoks.gram;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;

/**
 * Settings → Liquid Glass va ko'rinish → "Pastki navigatsiya paneli": a horizontally scrollable strip with a tiny
 * live mockup of each of the 6 styles (tap one to pick it), and a row of colour swatches underneath (tap one to
 * change the tint). Both read/write {@link ParadoksConfig} directly and redraw themselves at once, the same way
 * {@link ParadoksGlassPreview} already does for the chat preview above it. The real Chatlar/Kontaktlar/Sozlamalar/
 * Profil bar is drawn by {@link org.telegram.ui.Components.glass.GlassTabView}, which reads the same two
 * settings, so this strip is a faithful, if pocket-sized, stand-in for it.
 */
public class ParadoksGlassNavPreview extends LinearLayout {

    private static final String[] STYLE_NAMES = {"Linza", "Neon linza", "Sakrash", "To'lqin", "Projektor", "Suzuvchi"};

    private final MiniBarCell[] cells = new MiniBarCell[STYLE_NAMES.length];
    private final Swatch[] swatches = new Swatch[ParadoksGlass.TINTS.length];

    public ParadoksGlassNavPreview(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(12));

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        for (int i = 0; i < STYLE_NAMES.length; i++) {
            final int index = i;
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            MiniBarCell cell = new MiniBarCell(context, index);
            cell.setOnClickListener(v -> {
                ParadoksConfig.setGlassNavStyle(index);
                refreshSelection();
            });
            cells[index] = cell;
            TextView label = new TextView(context);
            label.setText(STYLE_NAMES[index]);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setPadding(0, AndroidUtilities.dp(4), 0, 0);
            column.addView(cell, new LinearLayout.LayoutParams(AndroidUtilities.dp(116), AndroidUtilities.dp(56)));
            column.addView(label, new LinearLayout.LayoutParams(AndroidUtilities.dp(116), LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            columnParams.rightMargin = AndroidUtilities.dp(10);
            row.addView(column, columnParams);
        }
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout swatchRow = new LinearLayout(context);
        swatchRow.setOrientation(HORIZONTAL);
        swatchRow.setGravity(Gravity.CENTER_HORIZONTAL);
        swatchRow.setPadding(0, AndroidUtilities.dp(10), 0, 0);
        for (int i = 0; i < ParadoksGlass.TINTS.length; i++) {
            final int index = i;
            Swatch swatch = new Swatch(context, index);
            swatch.setOnClickListener(v -> {
                ParadoksConfig.setGlassNavTint(index);
                refreshSelection();
            });
            swatches[index] = swatch;
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(AndroidUtilities.dp(30), AndroidUtilities.dp(30));
            swatchParams.leftMargin = swatchParams.rightMargin = AndroidUtilities.dp(6);
            swatchRow.addView(swatch, swatchParams);
        }
        addView(swatchRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void refreshSelection() {
        for (MiniBarCell cell : cells) {
            cell.invalidate();
        }
        for (Swatch swatch : swatches) {
            swatch.invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (MiniBarCell cell : cells) {
            cell.stopLoop();
        }
    }

    /** A pocket mockup of the real bar: a dark pill, 4 dots, and the chosen style's highlight behind dot 0. */
    private static final class MiniBarCell extends View {

        private final int style;
        private final ParadoksGlass glass = new ParadoksGlass();
        private final Path path = new Path();
        private final Rect rect = new Rect();
        private final RectF bounds = new RectF();
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // A free-running 0..1..0 loop (no interaction needed) so bounce/FAB/spotlight motion is visible at a
        // glance, the same idea as the real bar's own selectedFactor but looping on its own in a settings row.
        private ValueAnimator loop;
        private float phase;

        MiniBarCell(Context context, int style) {
            super(context);
            this.style = style;
            pillPaint.setColor(0xFF14161C);
            dotPaint.setColor(0x80FFFFFF);
            selectedDotPaint.setColor(0xFFFFFFFF);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(AndroidUtilities.dpf2(2));
            ringPaint.setColor(0xFF5AA7FF);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (loop == null) {
                loop = ValueAnimator.ofFloat(0f, 1f);
                loop.setDuration(1400);
                loop.setRepeatCount(ValueAnimator.INFINITE);
                loop.setRepeatMode(ValueAnimator.RESTART);
                loop.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
                loop.start();
            }
        }

        void stopLoop() {
            if (loop != null) {
                loop.cancel();
                loop = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopLoop();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            float pad = AndroidUtilities.dp(4);
            float barH = h - pad * 2 - AndroidUtilities.dp(4);
            bounds.set(pad, pad, w - pad, pad + barH);
            path.reset();
            path.addRoundRect(bounds, barH / 2f, barH / 2f, Path.Direction.CW);
            canvas.drawPath(path, pillPaint);
            if (ParadoksConfig.glassNavStyle() == style) {
                canvas.drawPath(path, ringPaint);
            }

            int n = 4;
            float cy = bounds.centerY();
            float lift = 0f;
            if (style == 2 || style == 5) {
                lift = (float) Math.sin(Math.min(1f, phase) * Math.PI) * AndroidUtilities.dp(style == 5 ? 7 : 5);
            }
            for (int i = 0; i < n; i++) {
                float cx = bounds.left + bounds.width() * (i + 0.5f) / n;
                if (i == 0) {
                    drawHighlight(canvas, cx, cy, barH);
                }
                float dy = i == 0 ? cy - lift : cy;
                canvas.drawCircle(cx, dy, AndroidUtilities.dpf2(i == 0 ? 4.5f : 3.5f), i == 0 ? selectedDotPaint : dotPaint);
            }
        }

        private void drawHighlight(Canvas canvas, float cx, float cy, float barH) {
            int tint = Math.max(0, Math.min(ParadoksGlass.TINTS.length - 1, ParadoksConfig.glassNavTint()));
            float r = barH * 0.4f;
            switch (style) {
                case 3: { // wave
                    bounds.set(cx - r * 1.3f, cy - r, cx + r * 1.3f, cy + r);
                    path.reset();
                    path.addRoundRect(bounds, r * 0.55f, r * 0.55f, Path.Direction.CW);
                    rect.set((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
                    glass.draw(canvas, path, rect, 1f, ParadoksGlass.ROLE_OUT, 2, tint, 2, false, null);
                    break;
                }
                case 4: { // spotlight
                    bounds.set(cx - r, cy - r, cx + r, cy + r);
                    path.reset();
                    path.addCircle(cx, cy, r, Path.Direction.CW);
                    rect.set((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
                    glass.draw(canvas, path, rect, 1f, ParadoksGlass.ROLE_IN, 2, 0, 1, false, null);
                    LinearGradient gradient = new LinearGradient(cx, cy - r - AndroidUtilities.dp(10), cx, cy - r,
                            0x00FFFFFF, 0x77FFFFFF, Shader.TileMode.CLAMP);
                    glowPaint.setShader(gradient);
                    canvas.drawRect(cx - r * 0.9f, cy - r - AndroidUtilities.dp(10), cx + r * 0.9f, cy - r, glowPaint);
                    glowPaint.setShader(null);
                    break;
                }
                case 5: { // floating button
                    float fr = r * 1.1f;
                    float fcy = cy - lift;
                    bounds.set(cx - fr, fcy - fr, cx + fr, fcy + fr);
                    path.reset();
                    path.addCircle(cx, fcy, fr, Path.Direction.CW);
                    rect.set((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
                    glass.draw(canvas, path, rect, 1f, ParadoksGlass.ROLE_OUT, 3, tint, 3, false, null);
                    break;
                }
                case 1: { // neon lens
                    bounds.set(cx - r, cy - r, cx + r, cy + r);
                    path.reset();
                    path.addCircle(cx, cy, r, Path.Direction.CW);
                    rect.set((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
                    glass.draw(canvas, path, rect, 1f, ParadoksGlass.ROLE_OUT, 2, tint, 3, false, null);
                    glowPaint.setShader(null);
                    glowPaint.setStyle(Paint.Style.STROKE);
                    glowPaint.setStrokeWidth(AndroidUtilities.dpf2(1.4f));
                    glowPaint.setColor(ParadoksGlass.TINTS[tint]);
                    canvas.drawCircle(cx, cy, r, glowPaint);
                    glowPaint.setStyle(Paint.Style.FILL);
                    break;
                }
                case 2: // bounce (plain lens pill; the dot itself lifts, handled above)
                case 0: // lens
                default: {
                    bounds.set(cx - r, cy - r, cx + r, cy + r);
                    path.reset();
                    path.addCircle(cx, cy, r, Path.Direction.CW);
                    rect.set((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
                    glass.draw(canvas, path, rect, 1f, ParadoksGlass.ROLE_OUT, 2, tint, 1, false, null);
                    break;
                }
            }
        }
    }

    /** A single tappable colour dot, with a ring around whichever one is the current nav tint. */
    private static final class Swatch extends View {

        private final int index;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Swatch(Context context, int index) {
            super(context);
            this.index = index;
            fillPaint.setColor(ParadoksGlass.TINTS[index]);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(AndroidUtilities.dpf2(2));
            ringPaint.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - AndroidUtilities.dpf2(2);
            canvas.drawCircle(cx, cy, r, fillPaint);
            if (index == 0) {
                ringPaint.setColor(0x40FFFFFF);
                canvas.drawCircle(cx, cy, r, ringPaint);
            }
            if (ParadoksConfig.glassNavTint() == index) {
                ringPaint.setColor(0xFFFFFFFF);
                canvas.drawCircle(cx, cy, r + AndroidUtilities.dp(3), ringPaint);
            }
        }
    }
}
