package uz.paradoks.gram;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Round liquid glass background for floating buttons (used when the "Tugmalar uchun liquid glass" setting is on).
 * The icon on top is white, so a colourless setting uses a blue tint here to keep it readable on light screens.
 */
public final class ParadoksGlassButton extends Drawable {

    private final ParadoksGlass glass = new ParadoksGlass();
    private final Path path = new Path();
    private final Rect rect = new Rect();
    private boolean pressed;

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        path.reset();
        path.addCircle(b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()) / 2f, Path.Direction.CW);
        rect.set(b);
        int tint = ParadoksConfig.glassTint();
        if (tint == 0) {
            tint = 1;
        }
        int level = ParadoksConfig.glassBubbleLevel();
        if (level == 0) {
            level = 2;
        }
        glass.draw(canvas, path, rect, pressed ? 0.8f : 1f, ParadoksGlass.ROLE_OUT, Math.max(1, level - 1), tint, Math.max(2, ParadoksConfig.glassEdge()), false, null);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean now = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) {
                now = true;
            }
        }
        if (now != pressed) {
            pressed = now;
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
