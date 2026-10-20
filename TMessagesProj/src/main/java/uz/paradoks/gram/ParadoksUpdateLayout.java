package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.IUpdateLayout;

/** The "ParadoksGram ni yangilash" bar at the bottom of the chat list, like "Update Telegram" in the official app. */
public class ParadoksUpdateLayout extends IUpdateLayout {

    private final Activity activity;
    private final ViewGroup container;
    private FrameLayout bar;
    private TextView titleView;
    private TextView sizeView;

    public ParadoksUpdateLayout(Activity activity, ViewGroup container) {
        super(activity, container);
        this.activity = activity;
        this.container = container;
        ParadoksUpdater.register(this);
    }

    @Override
    public void updateFileProgress(Object[] args) {
    }

    @Override
    public void createUpdateUI(int currentAccount) {
        if (container == null || bar != null) {
            return;
        }
        bar = new FrameLayout(activity);
        bar.setVisibility(View.INVISIBLE);
        bar.setTranslationY(dp(44));
        bar.setBackground(Theme.getSelectorDrawable(0x40ffffff, false));
        container.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.LEFT | Gravity.BOTTOM));
        bar.setOnClickListener(v -> {
            switch (ParadoksUpdater.state()) {
                case ParadoksUpdater.AVAILABLE:
                case ParadoksUpdater.FAILED:
                    ParadoksUpdater.download(activity);
                    break;
                case ParadoksUpdater.DOWNLOADING:
                    ParadoksUpdater.cancel();
                    break;
                case ParadoksUpdater.READY:
                    ParadoksUpdater.install(activity);
                    break;
                default:
                    break;
            }
        });

        titleView = new TextView(activity);
        titleView.setTextSize(15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(0xffffffff);
        titleView.setGravity(Gravity.CENTER);
        bar.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        sizeView = new TextView(activity);
        sizeView.setTextSize(14);
        sizeView.setTypeface(AndroidUtilities.bold());
        sizeView.setTextColor(0xccffffff);
        sizeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        bar.addView(sizeView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.RIGHT, 0, 0, 16, 0));
    }

    @Override
    public void updateAppUpdateViews(int currentAccount, boolean animated) {
        refresh(animated);
    }

    void refresh(boolean animated) {
        if (container == null) {
            return;
        }
        int state = ParadoksUpdater.state();
        if (state == ParadoksUpdater.IDLE) {
            hide(animated);
            return;
        }
        createUpdateUI(0);
        String size = "";
        switch (state) {
            case ParadoksUpdater.DOWNLOADING:
                titleView.setText("Yuklanmoqda... " + (int) (ParadoksUpdater.progress() * 100) + "%");
                break;
            case ParadoksUpdater.READY:
                titleView.setText("O'rnatish uchun bosing");
                break;
            case ParadoksUpdater.FAILED:
                titleView.setText("Yuklab bo'lmadi. Qayta urinish");
                break;
            default:
                titleView.setText("ParadoksGram ni yangilash");
                size = ParadoksUpdater.apkSize() > 0 ? AndroidUtilities.formatFileSize(ParadoksUpdater.apkSize()) : "";
                break;
        }
        sizeView.setText(size);
        if (state == ParadoksUpdater.AVAILABLE) {
            ParadoksUpdater.announceOnce(activity);
        }
        if (bar.getTag() != null) {
            return;
        }
        bar.setVisibility(View.VISIBLE);
        bar.setTag(1);
        if (animated) {
            bar.animate().translationY(0).setInterpolator(CubicBezierInterpolator.EASE_OUT).setListener(null).setDuration(180).start();
        } else {
            bar.setTranslationY(0);
        }
    }

    private void hide(boolean animated) {
        if (bar == null || bar.getTag() == null) {
            return;
        }
        bar.setTag(null);
        if (animated) {
            bar.animate().translationY(dp(44)).setInterpolator(CubicBezierInterpolator.EASE_OUT).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (bar.getTag() == null) {
                        bar.setVisibility(View.INVISIBLE);
                    }
                }
            }).setDuration(180).start();
        } else {
            bar.setTranslationY(dp(44));
            bar.setVisibility(View.INVISIBLE);
        }
    }
}
