package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

/**
 * ParadoksGram: full-screen PIN screen (lock icon, round number keys, fingerprint button), the same look
 * as the hidden chats screen in Plus Messenger. It can also unlock with a fingerprint or the face
 * (whatever the phone offers through the system biometric prompt).
 */
public class ParadoksPinScreen {

    public interface Checker {
        boolean matches(String pin);
    }

    public interface Result {
        void onPin(String pin);
    }

    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 12;
    private static final int MAX_FAILS = 5;
    private static final long COOLDOWN_MS = 30_000;

    private static int fails;
    private static long blockedUntil;

    private final Context context;
    private final String title;
    private final String subtitle;
    private final boolean cancelable;
    private final Checker checker; // null = "choose a new PIN" mode (no checking, asks twice)
    private final boolean biometric;
    private final Result onDone;
    private final Runnable onCancel;

    private Dialog dialog;
    private TextView titleView;
    private TextView subtitleView;
    private DotsView dots;
    private KeyView actionKey;
    private final StringBuilder entered = new StringBuilder();
    private String firstEntry;
    private boolean finished;
    private BiometricPrompt prompt;

    private ParadoksPinScreen(Context context, String title, String subtitle, boolean cancelable, Checker checker,
                              boolean biometric, Result onDone, Runnable onCancel) {
        this.context = context;
        this.title = title;
        this.subtitle = subtitle;
        this.cancelable = cancelable;
        this.checker = checker;
        this.biometric = biometric;
        this.onDone = onDone;
        this.onCancel = onCancel;
    }

    /** Asks for the existing PIN. Calls {@code onDone} with the right PIN, or {@code onCancel} when dismissed. */
    public static void unlock(Context context, String title, String subtitle, boolean cancelable, Checker checker, Result onDone, Runnable onCancel) {
        if (context == null) {
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }
        new ParadoksPinScreen(context, title, subtitle, cancelable, checker, ParadoksConfig.lockBiometric(), onDone, onCancel).show();
    }

    /** Asks for a new PIN twice. */
    public static void create(Context context, String title, String subtitle, Result onDone, Runnable onCancel) {
        if (context == null) {
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }
        new ParadoksPinScreen(context, title, subtitle, true, null, false, onDone, onCancel).show();
    }

    /** True when the phone has an enrolled fingerprint or face the system prompt can use. */
    public static boolean biometricAvailable(Context context) {
        if (Build.VERSION.SDK_INT < 23 || context == null) {
            return false;
        }
        try {
            return BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ UI

    private void show() {
        dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final FrameLayout root = new FrameLayout(context);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF0A101C, 0xFF1B2638});
        root.setBackground(background);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView lock = new ImageView(context);
        lock.setImageResource(R.drawable.ic_lock_header);
        lock.setColorFilter(Color.WHITE);
        lock.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams lockParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        lockParams.topMargin = dp(70);
        column.addView(lock, lockParams);

        View spacer = new View(context);
        column.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setText(title);
        titleView.setPadding(dp(24), 0, dp(24), 0);
        column.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        subtitleView = new TextView(context);
        subtitleView.setTextColor(0xCCFFFFFF);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setText(subtitle);
        subtitleView.setPadding(dp(24), dp(6), dp(24), 0);
        column.addView(subtitleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dots = new DotsView(context);
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        dotsParams.topMargin = dp(10);
        column.addView(dots, dotsParams);

        column.addView(buildPad(), new LinearLayout.LayoutParams(Math.min(AndroidUtilities.displaySize.x, dp(330)), ViewGroup.LayoutParams.WRAP_CONTENT));

        View bottom = new View(context);
        column.addView(bottom, new LinearLayout.LayoutParams(1, dp(48)));

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); // no screenshots / app-switcher preview of the PIN screen
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.setCancelable(false);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (cancelable) {
                    cancel();
                }
                return true;
            }
            return false;
        });
        dialog.show();
        refresh();
        if (checker != null && biometric && biometricAvailable(context)) {
            root.postDelayed(this::startBiometric, 250);
        }
    }

    private View buildPad() {
        LinearLayout pad = new LinearLayout(context);
        pad.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"back", "0", "action"},
        };
        String[] letters = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"};
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : row) {
                final KeyView view;
                if (key.equals("back")) {
                    view = new KeyView(context, KeyView.BACK, "", "");
                    view.setOnClickListener(v -> backspace());
                    view.setOnLongClickListener(v -> {
                        entered.setLength(0);
                        refresh();
                        return true;
                    });
                } else if (key.equals("action")) {
                    view = new KeyView(context, KeyView.FINGER, "", "");
                    actionKey = view;
                    view.setOnClickListener(v -> onActionKey());
                } else {
                    String small = key.equals("0") ? "+" : letters[Integer.parseInt(key) - 1];
                    view = new KeyView(context, KeyView.DIGIT, key, small);
                    final String digit = key;
                    view.setOnClickListener(v -> addDigit(digit));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(76), 1f);
                lp.topMargin = dp(8);
                line.addView(view, lp);
            }
            pad.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return pad;
    }

    // ------------------------------------------------------------------ input

    private void addDigit(String digit) {
        if (finished) {
            return;
        }
        if (checker != null && System.currentTimeMillis() < blockedUntil) {
            showBlocked();
            return;
        }
        if (entered.length() >= MAX_LENGTH) {
            return;
        }
        entered.append(digit);
        refresh();
        // Right PIN typed: open at once, no OK press needed (wrong prefixes are silent).
        if (checker != null && entered.length() >= MIN_LENGTH && checker.matches(entered.toString())) {
            success(entered.toString());
        }
    }

    private void backspace() {
        if (finished || entered.length() == 0) {
            return;
        }
        entered.setLength(entered.length() - 1);
        refresh();
    }

    /** Bottom-right key: confirm (check mark) while a PIN of 4+ digits is typed, otherwise the fingerprint. */
    private void onActionKey() {
        if (finished) {
            return;
        }
        if (entered.length() >= MIN_LENGTH) {
            confirm();
        } else if (checker != null && biometric && biometricAvailable(context)) {
            startBiometric();
        }
    }

    private void confirm() {
        final String pin = entered.toString();
        if (checker == null) { // choosing a new PIN: enter twice
            if (firstEntry == null) {
                firstEntry = pin;
                entered.setLength(0);
                titleView.setText("PIN ni takrorlang");
                subtitleView.setText("Xuddi shu PIN ni yana bir marta kiriting");
                refresh();
            } else if (firstEntry.equals(pin)) {
                success(pin);
            } else {
                firstEntry = null;
                entered.setLength(0);
                titleView.setText(title);
                subtitleView.setText("PIN lar mos kelmadi, qaytadan kiriting");
                AndroidUtilities.shakeView(dots);
                refresh();
            }
            return;
        }
        if (System.currentTimeMillis() < blockedUntil) {
            showBlocked();
            return;
        }
        if (checker.matches(pin)) {
            success(pin);
            return;
        }
        fails++;
        entered.setLength(0);
        refresh();
        AndroidUtilities.shakeView(dots);
        if (fails >= MAX_FAILS) {
            fails = 0;
            blockedUntil = System.currentTimeMillis() + COOLDOWN_MS;
            showBlocked();
        } else {
            subtitleView.setText("PIN noto'g'ri. Qolgan urinishlar: " + (MAX_FAILS - fails));
        }
    }

    private void showBlocked() {
        long seconds = Math.max(1, (blockedUntil - System.currentTimeMillis() + 999) / 1000);
        subtitleView.setText("Juda ko'p xato urinish. " + seconds + " soniyadan keyin urinib ko'ring");
    }

    private void refresh() {
        dots.setCount(entered.length());
        if (actionKey != null) {
            if (entered.length() >= MIN_LENGTH) {
                actionKey.setKind(KeyView.CHECK);
            } else if (checker != null && biometric && biometricAvailable(context)) {
                actionKey.setKind(KeyView.FINGER);
            } else {
                actionKey.setKind(KeyView.NONE);
            }
        }
    }

    private void success(String pin) {
        if (finished) {
            return;
        }
        finished = true;
        fails = 0;
        dismiss();
        if (onDone != null) {
            onDone.onPin(pin);
        }
    }

    private void cancel() {
        if (finished) {
            return;
        }
        finished = true;
        dismiss();
        if (onCancel != null) {
            onCancel.run();
        }
    }

    private void dismiss() {
        try {
            if (prompt != null) {
                prompt.cancelAuthentication();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ biometrics

    private void startBiometric() {
        if (finished || !(context instanceof FragmentActivity)) {
            return;
        }
        if (System.currentTimeMillis() < blockedUntil) {
            return;
        }
        try {
            prompt = new BiometricPrompt((FragmentActivity) context, ContextCompat.getMainExecutor(context), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    success("");
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    // cancelled or too many tries: the PIN keys stay available
                }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle("Barmoq izi yoki yuz bilan oching")
                    .setNegativeButtonText("PIN ishlatish")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build();
            prompt.authenticate(info);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------ views

    private static class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count;

        DotsView(Context context) {
            super(context);
        }

        void setCount(int count) {
            this.count = count;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (count == 0) {
                return;
            }
            float radius = dp(5);
            float gap = dp(14);
            float total = count * radius * 2 + (count - 1) * gap;
            float x = (getWidth() - total) / 2f + radius;
            paint.setColor(Color.WHITE);
            for (int i = 0; i < count; i++) {
                canvas.drawCircle(x, getHeight() / 2f, radius, paint);
                x += radius * 2 + gap;
            }
        }
    }

    /** One round key: a digit with letters, backspace, fingerprint or confirm. */
    private static class KeyView extends View {
        static final int DIGIT = 0, BACK = 1, FINGER = 2, CHECK = 3, NONE = 4;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int kind;
        private final String digit;
        private final String letters;
        private android.graphics.drawable.Drawable fingerprint;

        KeyView(Context context, int kind, String digit, String letters) {
            super(context);
            this.kind = kind;
            this.digit = digit;
            this.letters = letters;
            fill.setColor(0x33FFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(0x26FFFFFF);
            text.setColor(Color.WHITE);
            text.setTextSize(dp(30));
            text.setTextAlign(Paint.Align.CENTER);
            small.setColor(0x99FFFFFF);
            small.setTextSize(dp(10));
            small.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(Typeface.DEFAULT_BOLD);
            icon.setColor(Color.WHITE);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(dp(2.2f));
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setStrokeJoin(Paint.Join.ROUND);
            fingerprint = ContextCompat.getDrawable(context, R.drawable.fingerprint);
            if (fingerprint != null) {
                fingerprint = fingerprint.mutate();
                fingerprint.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            setClickable(kind != NONE);
            setSoundEffectsEnabled(false);
        }

        void setKind(int kind) {
            if (this.kind != kind) {
                this.kind = kind;
                setClickable(kind != NONE);
                invalidate();
            }
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (kind == NONE) {
                return;
            }
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f - dp(4);
            fill.setColor(isPressed() ? 0x66FFFFFF : 0x33FFFFFF);
            canvas.drawCircle(cx, cy, radius, fill);
            canvas.drawCircle(cx, cy, radius, stroke);
            switch (kind) {
                case DIGIT:
                    if (letters.isEmpty()) {
                        canvas.drawText(digit, cx, cy + dp(10), text);
                    } else {
                        canvas.drawText(digit, cx, cy + dp(5), text);
                        canvas.drawText(letters, cx, cy + dp(21), small);
                    }
                    break;
                case BACK: {
                    float w = dp(13);
                    float h = dp(9);
                    path.reset();
                    path.moveTo(cx - w, cy);
                    path.lineTo(cx - w + dp(7), cy - h);
                    path.lineTo(cx + w, cy - h);
                    path.lineTo(cx + w, cy + h);
                    path.lineTo(cx - w + dp(7), cy + h);
                    path.close();
                    canvas.drawPath(path, icon);
                    canvas.drawLine(cx - dp(2), cy - dp(4), cx + dp(6), cy + dp(4), icon);
                    canvas.drawLine(cx + dp(6), cy - dp(4), cx - dp(2), cy + dp(4), icon);
                    break;
                }
                case FINGER:
                    if (fingerprint != null) {
                        int size = dp(34);
                        fingerprint.setBounds((int) cx - size / 2, (int) cy - size / 2, (int) cx + size / 2, (int) cy + size / 2);
                        fingerprint.draw(canvas);
                    }
                    break;
                case CHECK:
                    path.reset();
                    path.moveTo(cx - dp(10), cy + dp(1));
                    path.lineTo(cx - dp(3), cy + dp(8));
                    path.lineTo(cx + dp(11), cy - dp(8));
                    canvas.drawPath(path, icon);
                    break;
                default:
                    break;
            }
        }
    }
}
