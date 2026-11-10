package uz.paradoks.gram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** A small vinyl disc that spins while the music studio is playing. */
public class ParadoksDiscView extends View {

    private static final float TURN_MS = 2200f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean spinning;
    private float angle;
    private long last;

    public ParadoksDiscView(Context context) {
        super(context);
    }

    public void setSpinning(boolean value) {
        if (spinning == value) {
            return;
        }
        spinning = value;
        last = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.uptimeMillis();
        if (spinning) {
            angle = (angle + (now - last) * 360f / TURN_MS) % 360f;
        }
        last = now;
        final float w = getWidth(), h = getHeight();
        final float r = Math.min(w, h) / 2f - 1f;
        final float cx = w / 2f, cy = h / 2f;

        canvas.save();
        canvas.rotate(angle, cx, cy);

        // the record
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xFF15151F);
        canvas.drawCircle(cx, cy, r, paint);

        // grooves
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, r * 0.04f));
        paint.setColor(0x33FFFFFF);
        canvas.drawCircle(cx, cy, r * 0.88f, paint);
        canvas.drawCircle(cx, cy, r * 0.74f, paint);
        canvas.drawCircle(cx, cy, r * 0.60f, paint);

        // a light sweep, so the turning is visible
        paint.setStrokeWidth(Math.max(1.5f, r * 0.09f));
        paint.setColor(0x66FFFFFF);
        rect.set(cx - r * 0.81f, cy - r * 0.81f, cx + r * 0.81f, cy + r * 0.81f);
        canvas.drawArc(rect, -60, 38, false, paint);
        canvas.drawArc(rect, 120, 38, false, paint);

        // the label
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFA855F7, 0xFF22D3EE, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * 0.42f, paint);
        paint.setShader(null);

        // the hole
        paint.setColor(0xFF15151F);
        canvas.drawCircle(cx, cy, r * 0.10f, paint);
        canvas.restore();

        if (spinning && isAttachedToWindow()) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        last = SystemClock.uptimeMillis();
        if (spinning) {
            invalidate();
        }
    }
}
