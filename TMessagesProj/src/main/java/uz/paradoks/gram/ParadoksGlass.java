package uz.paradoks.gram;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;

/**
 * The liquid glass look of a message bubble: translucent tinted fill, top sheen, bright rim and (optionally) light
 * bending at the rim. Shared by the real chat (MessageDrawable) and the settings preview so both look the same.
 *
 * When a blurred copy of what is behind the bubble is available (only in the settings preview, where the wallpaper is
 * known) it is drawn through a lens-shaped mesh: a real blur + displacement like the web sample. In the real chat the
 * wallpaper behind a cell is not available, so the water effect is imitated with neutral light on the rim (never coloured).
 */
public final class ParadoksGlass {

    public static final int[] TINTS = {
            0xFFFFFFFF, // colorless
            0xFF5AA7FF, // blue
            0xFFAA78FF, // purple
            0xFF3CDC96, // green
            0xFFFF6EAA, // pink
            0xFFFFBE46  // gold
    };

    private static final int MESH_W = 16;
    private static final int MESH_H = 10;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient sheenShader = new LinearGradient(0, 0, 0, 1, new int[]{0x55FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
    private final Matrix matrix = new Matrix();
    private final float[] verts = new float[(MESH_W + 1) * (MESH_H + 1) * 2];

    public ParadoksGlass() {
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(1f)));
        sheen.setShader(sheenShader);
        lens.setFilterBitmap(true);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.7f);
        lens.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    public static final int ROLE_OUT = 0;
    public static final int ROLE_IN = 1;
    public static final int ROLE_DELETED = 2;

    /** Set by ChatMessageCell right before it draws a bubble: the message was deleted by its sender (UI thread only). */
    public static boolean drawingDeleted;

    private static final int DELETED_TINT = 0xFFFF4D4D;

    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient rimShader;
    private int rimShaderColor = 1;
    private final Matrix rimMatrix = new Matrix();

    private void ensureRimShader(int rgb) {
        if (rimShader != null && rimShaderColor == rgb) {
            return;
        }
        rimShaderColor = rgb;
        int c = rgb & 0x00FFFFFF;
        // bright on the top-left and bottom-right corners, faint along the straight sides (like the reference glass)
        rimShader = new LinearGradient(0, 0, 1, 1,
                new int[]{0xFF000000 | c, 0x38000000 | c, 0x38000000 | c, 0xB0000000 | c},
                new float[]{0f, 0.32f, 0.68f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Draws the glass with the current settings from ParadoksConfig. */
    public void draw(Canvas canvas, Path path, Rect bounds, float a, Bitmap blurredBackdrop) {
        draw(canvas, path, bounds, a, ROLE_OUT, blurredBackdrop);
    }

    public void draw(Canvas canvas, Path path, Rect bounds, float a, int role, Bitmap blurredBackdrop) {
        draw(canvas, path, bounds, a, role, ParadoksConfig.glassBubbleLevel(), ParadoksConfig.glassTint(), ParadoksConfig.glassEdge(), ParadoksConfig.glassRefract(), blurredBackdrop);
    }

    /**
     * @param a               overall opacity 0..1
     * @param role            ROLE_OUT (tinted with the chosen colour), ROLE_IN (clear, colourless) or ROLE_DELETED (red)
     * @param blurredBackdrop a small blurred copy of what is behind the bubble (stretched over {@code bounds}), or null
     */
    public void draw(Canvas canvas, Path path, Rect bounds, float a, int role, int level, int tint, int edgeLevel, boolean refract, Bitmap blurredBackdrop) {
        int tintIndex = Math.max(0, Math.min(TINTS.length - 1, tint));
        int tintColor;
        if (role == ROLE_DELETED) {
            tintColor = DELETED_TINT;
        } else if (role == ROLE_IN) {
            tintColor = TINTS[0];
        } else {
            tintColor = TINTS[tintIndex];
        }
        boolean colored = tintColor != TINTS[0];
        float fillAlpha;
        if (!colored) {
            fillAlpha = level == 1 ? 0.30f : level == 2 ? 0.18f : 0.09f;
        } else {
            fillAlpha = level == 1 ? 0.58f : level == 2 ? 0.42f : 0.26f;
        }
        float rim = edgeLevel == 0 ? 0f : edgeLevel == 1 ? 0.6f : edgeLevel == 2 ? 1f : 1.4f;
        if (role == ROLE_IN) {
            rim *= 0.8f; // incoming glass: a lighter, thinner rim
        }
        // rim colour: white, slightly pulled towards the tint (outgoing / deleted); never a rainbow
        int rimRgb = colored ? ColorUtils.blendARGB(0xFFFFFFFF, tintColor, 0.35f) : 0xFFFFFFFF;

        boolean real = refract && blurredBackdrop != null && !blurredBackdrop.isRecycled();
        if (real) {
            int s = canvas.save();
            canvas.clipPath(path);
            drawLens(canvas, bounds, a, blurredBackdrop);
            canvas.restoreToCount(s);
        }

        fill.setColor(ColorUtils.setAlphaComponent(tintColor, Math.round(255 * fillAlpha * a)));
        canvas.drawPath(path, fill);

        int save = canvas.save();
        canvas.clipPath(path);
        float height = Math.max(1, bounds.height());
        float width = Math.max(1, bounds.width());
        if (rim > 0) {
            // soft sheen across the top
            matrix.reset();
            matrix.setScale(1f, height * 0.55f);
            matrix.postTranslate(0, bounds.top);
            sheenShader.setLocalMatrix(matrix);
            sheen.setAlpha(Math.round(Math.min(255, 200 * a * rim)));
            canvas.drawRect(bounds, sheen);

            // soft inner shadow just inside the rim (two strokes, the wider one fainter)
            inner.setStyle(Paint.Style.STROKE);
            inner.setColor(0xFF000000);
            inner.setStrokeWidth(Math.max(3f, AndroidUtilities.dpf2(5f)));
            inner.setAlpha(Math.round(16 * a * rim));
            canvas.drawPath(path, inner);
            inner.setStrokeWidth(Math.max(2f, AndroidUtilities.dpf2(2.5f)));
            inner.setAlpha(Math.round(22 * a * rim));
            canvas.drawPath(path, inner);
        }
        if (refract) {
            // Water over the picture, imitated with neutral light only: a soft glow inside the rim and a displaced
            // second highlight (no colours). With a known backdrop the lens above does the real work.
            edge.setShader(null);
            edge.setColor(0xFFFFFFFF);
            edge.setStrokeWidth(Math.max(3f, AndroidUtilities.dpf2(real ? 3f : 4f)));
            edge.setAlpha(Math.round((real ? 26 : 38) * a));
            canvas.drawPath(path, edge);
            float shift = AndroidUtilities.dpf2(real ? 1.5f : 2f);
            edge.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(1.2f)));
            int s = canvas.save();
            canvas.translate(shift, shift);
            edge.setAlpha(Math.round(70 * a));
            canvas.drawPath(path, edge);
            canvas.restoreToCount(s);
        }
        if (rim > 0) {
            ensureRimShader(rimRgb);
            rimMatrix.reset();
            rimMatrix.setScale(width, height);
            rimMatrix.postTranslate(bounds.left, bounds.top);
            rimShader.setLocalMatrix(rimMatrix);
            edge.setShader(rimShader);
            edge.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(1.4f)));
            edge.setAlpha(Math.round(Math.min(255, 235 * a * Math.min(1f, rim))));
            canvas.drawPath(path, edge);
            edge.setShader(null);
        }
        canvas.restoreToCount(save);
    }

    /** The blurred backdrop drawn through a convex lens: the middle is magnified a little, the rim stays put. */
    private void drawLens(Canvas canvas, Rect bounds, float a, Bitmap backdrop) {
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        float hw = Math.max(1f, bounds.width() / 2f);
        float hh = Math.max(1f, bounds.height() / 2f);
        float k = 0.16f;
        int index = 0;
        for (int j = 0; j <= MESH_H; j++) {
            for (int i = 0; i <= MESH_W; i++) {
                float sx = bounds.left + bounds.width() * i / (float) MESH_W;
                float sy = bounds.top + bounds.height() * j / (float) MESH_H;
                float nx = (sx - cx) / hw;
                float ny = (sy - cy) / hh;
                float r = Math.min(1f, Math.max(Math.abs(nx), Math.abs(ny)));
                float f = 1f + k * (1f - r * r * r);
                float ripple = AndroidUtilities.dpf2(1.3f) * (1f - r * r) * (float) Math.sin(nx * 4.2 + ny * 2.6);
                verts[index++] = cx + (sx - cx) * f + ripple;
                verts[index++] = cy + (sy - cy) * f - ripple;
            }
        }
        lens.setAlpha(Math.round(255 * a));
        canvas.drawBitmapMesh(backdrop, MESH_W, MESH_H, verts, 0, null, 0, lens);
    }

    /** Cheap blur of the part of {@code source} under {@code area}: crop, then shrink twice (the upscale smooths it). */
    public static Bitmap blurRegion(Bitmap source, Rect area) {
        try {
            if (source == null || source.isRecycled()) {
                return null;
            }
            int x = Math.max(0, area.left);
            int y = Math.max(0, area.top);
            int w = Math.min(source.getWidth(), area.right) - x;
            int h = Math.min(source.getHeight(), area.bottom) - y;
            if (w < 4 || h < 4) {
                return null;
            }
            Bitmap crop = Bitmap.createBitmap(source, x, y, w, h);
            int sw = Math.max(2, w / 10);
            int sh = Math.max(2, h / 10);
            Bitmap small = Bitmap.createScaledBitmap(crop, sw, sh, true);
            return Bitmap.createScaledBitmap(small, Math.max(2, sw / 2), Math.max(2, sh / 2), true);
        } catch (Throwable e) {
            return null;
        }
    }
}
