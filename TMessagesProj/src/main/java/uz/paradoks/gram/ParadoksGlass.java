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
 * wallpaper behind a cell is not available, so the bending is imitated with coloured fringes on the rim.
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

    /** Draws the glass with the current settings from ParadoksConfig. */
    public void draw(Canvas canvas, Path path, Rect bounds, float a, Bitmap blurredBackdrop) {
        draw(canvas, path, bounds, a, ParadoksConfig.glassBubbleLevel(), ParadoksConfig.glassTint(), ParadoksConfig.glassEdge(), ParadoksConfig.glassRefract(), blurredBackdrop);
    }

    /**
     * @param a               overall opacity 0..1
     * @param blurredBackdrop a small blurred copy of what is behind the bubble (stretched over {@code bounds}), or null
     */
    public void draw(Canvas canvas, Path path, Rect bounds, float a, int level, int tint, int edgeLevel, boolean refract, Bitmap blurredBackdrop) {
        int tintIndex = Math.max(0, Math.min(TINTS.length - 1, tint));
        float fillAlpha;
        if (tintIndex == 0) {
            fillAlpha = level == 1 ? 0.34f : level == 2 ? 0.22f : 0.12f;
        } else {
            fillAlpha = level == 1 ? 0.62f : level == 2 ? 0.46f : 0.30f;
        }
        float rim = edgeLevel == 0 ? 0f : edgeLevel == 1 ? 0.6f : edgeLevel == 2 ? 1f : 1.4f;

        boolean real = refract && blurredBackdrop != null && !blurredBackdrop.isRecycled();
        if (real) {
            int s = canvas.save();
            canvas.clipPath(path);
            drawLens(canvas, bounds, a, blurredBackdrop);
            canvas.restoreToCount(s);
        }

        fill.setColor(ColorUtils.setAlphaComponent(TINTS[tintIndex], Math.round(255 * fillAlpha * a)));
        canvas.drawPath(path, fill);

        int save = canvas.save();
        canvas.clipPath(path);
        float height = Math.max(1, bounds.height());
        if (rim > 0) {
            matrix.reset();
            matrix.setScale(1f, height * 0.55f);
            matrix.postTranslate(0, bounds.top);
            sheenShader.setLocalMatrix(matrix);
            sheen.setAlpha(Math.round(Math.min(255, 255 * a * rim)));
            canvas.drawRect(bounds, sheen);
        }
        if (refract) {
            // Light bending at the rim: coloured fringes on both sides of the edge (plus an inner glow).
            float shift = AndroidUtilities.dpf2(real ? 0.9f : 1.2f);
            edge.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(real ? 1.5f : 2f)));
            int s = canvas.save();
            canvas.translate(-shift, -shift);
            edge.setColor(0xFF50D8FF);
            edge.setAlpha(Math.round((real ? 90 : 120) * a));
            canvas.drawPath(path, edge);
            canvas.restoreToCount(s);
            s = canvas.save();
            canvas.translate(shift, shift);
            edge.setColor(0xFFFF80B0);
            edge.setAlpha(Math.round((real ? 90 : 120) * a));
            canvas.drawPath(path, edge);
            canvas.restoreToCount(s);
            if (!real) {
                edge.setStrokeWidth(Math.max(2f, AndroidUtilities.dpf2(3f)));
                edge.setColor(0xFFFFFFFF);
                edge.setAlpha(Math.round(40 * a));
                canvas.drawPath(path, edge);
            }
        }
        if (rim > 0) {
            edge.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(1f)));
            edge.setColor(0xFFFFFFFF);
            edge.setAlpha(Math.round(Math.min(255, 120 * a * rim)));
            canvas.drawPath(path, edge);
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
                verts[index++] = cx + (sx - cx) * f;
                verts[index++] = cy + (sy - cy) * f;
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
