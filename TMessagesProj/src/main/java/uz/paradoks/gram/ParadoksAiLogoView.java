package uz.paradoks.gram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Paradoks AI logo that follows the Telegram theme: the chrome shell is drawn as it is, the visor takes the
 * colour of the page, the eyes take the text colour (so they stay readable on light and dark themes).
 */
public class ParadoksAiLogoView extends View {

    private static Bitmap shell;
    private static Bitmap visor;
    private static Bitmap trim;
    private static Bitmap eyes;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private int backdropKey = Theme.key_windowBackgroundWhite;
    private boolean customColors;
    private int backdropColor;
    private int eyeColor;

    public ParadoksAiLogoView(Context context) {
        super(context);
        if (shell == null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            shell = BitmapFactory.decodeResource(getResources(), R.drawable.paradoks_ai_shell, options);
            visor = BitmapFactory.decodeResource(getResources(), R.drawable.paradoks_ai_visor, options);
            trim = BitmapFactory.decodeResource(getResources(), R.drawable.paradoks_ai_trim, options);
            eyes = BitmapFactory.decodeResource(getResources(), R.drawable.paradoks_ai_eyes, options);
        }
    }

    /** Colour key of the surface behind the logo (the visor is filled with it). */
    public void setBackdropKey(int key) {
        backdropKey = key;
        invalidate();
    }

    /** Fixed colours instead of Telegram theme keys (used by the AI window, which has its own palette). */
    public void setColors(int backdrop, int eyes) {
        customColors = true;
        backdropColor = backdrop;
        eyeColor = eyes;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (shell == null || shell.isRecycled()) {
            return;
        }
        int size = Math.min(getWidth(), getHeight());
        int left = (getWidth() - size) / 2;
        int top = (getHeight() - size) / 2;
        dst.set(left, top, left + size, top + size);
        src.set(0, 0, shell.getWidth(), shell.getHeight());

        int backdrop = customColors ? backdropColor : Theme.getColor(backdropKey);
        boolean light = ColorUtils.calculateLuminance(backdrop) > 0.5;

        paint.setColorFilter(light ? new LightingColorFilter(0xFF9E9E9E, 0) : null); // darker chrome on light themes
        canvas.drawBitmap(shell, src, dst, paint);
        paint.setColorFilter(new PorterDuffColorFilter(backdrop, PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(visor, src, dst, paint);
        paint.setColorFilter(new PorterDuffColorFilter(light ? 0x803C4655 : 0xE6FFFFFF, PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(trim, src, dst, paint);
        paint.setColorFilter(new PorterDuffColorFilter(customColors ? eyeColor : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(eyes, src, dst, paint);
        paint.setColorFilter(null);
    }
}
