package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.FiltersSetupActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.SettingsActivity;

/**
 * ParadoksGram: the classic side menu (opened by the hamburger button at the top left of the chat list).
 * Newer Telegram versions removed it, so this is a small self-contained panel built on a plain Dialog.
 */
public class ParadoksSideMenu {

    private final BaseFragment fragment;
    private Dialog dialog;
    private FrameLayout panel;
    private boolean closing;

    private ParadoksSideMenu(BaseFragment fragment) {
        this.fragment = fragment;
    }

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        new ParadoksSideMenu(fragment).open();
    }

    private void open() {
        final Context context = fragment.getParentActivity();
        final int account = fragment.getCurrentAccount();
        final int width = Math.min(dp(320), (int) (AndroidUtilities.displaySize.x * 0.82f));

        dialog = new Dialog(context, R.style.TransparentDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(context);
        root.setOnClickListener(v -> close(null));

        panel = new FrameLayout(context);
        panel.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        panel.setClickable(true);
        panel.setElevation(dp(8));
        root.addView(panel, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(context, account));

        final long selfId = UserConfig.getInstance(account).getClientUserId();

        addRow(column, context, R.drawable.msg_openprofile, LocaleController.getString(R.string.MyProfile), () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", selfId);
            args.putBoolean("my_profile", true);
            fragment.presentFragment(new ProfileActivity(args, null));
        });
        addDivider(column, context);
        addRow(column, context, R.drawable.msg_groups, LocaleController.getString(R.string.NewGroup),
                () -> fragment.presentFragment(new GroupCreateActivity(new Bundle())));
        addRow(column, context, R.drawable.msg_channel, LocaleController.getString(R.string.NewChannel), () -> {
            Bundle args = new Bundle();
            args.putInt("step", 0);
            fragment.presentFragment(new ChannelCreateActivity(args));
        });
        addDivider(column, context);
        addRow(column, context, R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts),
                () -> fragment.presentFragment(new ContactsActivity(null)));
        addRow(column, context, R.drawable.msg_folders, LocaleController.getString(R.string.Filters),
                () -> fragment.presentFragment(new FiltersSetupActivity()));
        addRow(column, context, R.drawable.outline_saved_24, LocaleController.getString(R.string.SavedMessages), () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", selfId);
            fragment.presentFragment(new ChatActivity(args));
        });
        addRow(column, context, R.drawable.msg_calls, LocaleController.getString(R.string.Calls),
                () -> fragment.presentFragment(new CallLogActivity()));
        addRow(column, context, R.drawable.msg_settings_old, LocaleController.getString(R.string.Settings),
                () -> fragment.presentFragment(new SettingsActivity()));
        addDivider(column, context);
        addRow(column, context, R.drawable.msg_forward, "Ommaviy yuborish",
                () -> fragment.presentFragment(new ParadoksBroadcastActivity()));
        addRow(column, context, R.drawable.msg_settings, "ParadoksGram sozlamalari",
                () -> fragment.presentFragment(new ParadoksSettingsActivity()));

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setDimAmount(0.5f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.START | Gravity.TOP);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                close(null);
                return true;
            }
            return false;
        });
        dialog.show();

        panel.setTranslationX(-width);
        panel.animate().translationX(0).setDuration(200).start();
    }

    private View buildHeader(Context context, int account) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Theme.getColor(Theme.key_avatar_backgroundActionBarBlue));
        header.setPadding(dp(16), AndroidUtilities.statusBarHeight + dp(16), dp(16), dp(14));

        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(32));
        if (user != null) {
            avatar.setForUserOrChat(user, new AvatarDrawable(user));
        }
        header.addView(avatar, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView name = new TextView(context);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setText(user != null ? UserObject.getUserName(user) : "ParadoksGram");
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(12);
        header.addView(name, nameParams);

        if (user != null && user.phone != null && !user.phone.isEmpty()) {
            TextView phone = new TextView(context);
            phone.setTextColor(0xB3FFFFFF);
            phone.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            phone.setSingleLine(true);
            phone.setText(PhoneFormat.getInstance().format("+" + user.phone));
            header.addView(phone, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return header;
    }

    private void addRow(LinearLayout parent, Context context, int icon, String title, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, dp(16), 0);
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));

        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), android.graphics.PorterDuff.Mode.MULTIPLY));
        row.addView(image, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView text = new TextView(context);
        text.setText(title);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(28);
        row.addView(text, tp);

        row.setOnClickListener(v -> close(action));
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void addDivider(LinearLayout parent, Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        parent.addView(divider, lp);
    }

    /** Slides the panel out, then runs the chosen action (so the next screen opens on a clean window). */
    private void close(final Runnable after) {
        if (closing || dialog == null) {
            return;
        }
        closing = true;
        panel.animate().translationX(-panel.getWidth()).setDuration(160).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                try {
                    dialog.dismiss();
                } catch (Exception ignore) {
                }
                if (after != null) {
                    after.run();
                }
            }
        }).start();
    }
}
