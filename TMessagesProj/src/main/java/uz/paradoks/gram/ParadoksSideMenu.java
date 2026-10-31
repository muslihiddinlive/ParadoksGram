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
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.InviteContactsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LoginActivity;
import org.telegram.ui.ThemeActivity;
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

        final LinearLayout accountsBox = new LinearLayout(context);
        accountsBox.setOrientation(LinearLayout.VERTICAL);
        accountsBox.setVisibility(View.GONE);
        fillAccounts(accountsBox, context, account);
        column.addView(buildHeader(context, account, accountsBox));
        column.addView(accountsBox);

        final long selfId = UserConfig.getInstance(account).getClientUserId();

        addRow(column, context, R.drawable.msg_openprofile, LocaleController.getString(R.string.MyProfile), () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", selfId);
            args.putBoolean("my_profile", true);
            fragment.presentFragment(new ProfileActivity(args, null));
        });
        addRow(column, context, R.drawable.msg_payment_address, "Wallet",
                () -> MessagesController.getInstance(account).openByUserName("wallet", fragment, 1));
        if (UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT) {
            addRow(column, context, R.drawable.msg_addcontact, "Hisob qo'shish", this::addAccount);
        }
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
                onlineContactsText(account), () -> fragment.presentFragment(new ContactsActivity(null)));
        addRow(column, context, R.drawable.msg_folders, LocaleController.getString(R.string.Filters),
                () -> fragment.presentFragment(new FiltersSetupActivity()));
        addRow(column, context, R.drawable.outline_saved_24, LocaleController.getString(R.string.SavedMessages), () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", selfId);
            fragment.presentFragment(new ChatActivity(args));
        });
        addRow(column, context, R.drawable.msg_calls, LocaleController.getString(R.string.Calls),
                () -> fragment.presentFragment(new CallLogActivity()));
        addRow(column, context, R.drawable.msg_user_search, "Do'stlarni taklif qilish",
                () -> fragment.presentFragment(new InviteContactsActivity()));
        addRow(column, context, R.drawable.msg_settings_old, LocaleController.getString(R.string.Settings),
                () -> fragment.presentFragment(new SettingsActivity()));
        addDivider(column, context);
        addRow(column, context, R.drawable.msg_customize, "Dizayn",
                () -> fragment.presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        addRow(column, context, R.drawable.msg_theme, "Mavzularni yuklab olish",
                () -> MessagesController.getInstance(account).openByUserName("themes", fragment, 1));
        addRow(column, context, R.drawable.msg_stats, "Chat hisoblagichlari", () -> showCounters(account));
        addRow(column, context, R.drawable.msg_forward, "Ommaviy yuborish",
                () -> fragment.presentFragment(new ParadoksBroadcastActivity()));
        addRow(column, context, R.drawable.msg_settings, "ParadoksGram sozlamalari",
                () -> fragment.presentFragment(new ParadoksSettingsActivity()));

        TextView footer = new TextView(context);
        footer.setText("ParadoksGram " + BuildVars.BUILD_VERSION_STRING);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        footer.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(16), dp(14), dp(16), dp(24));
        column.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

    private View buildHeader(final Context context, final int account, final LinearLayout accountsBox) {
        FrameLayout header = new FrameLayout(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_avatar_backgroundActionBarBlue));

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), AndroidUtilities.statusBarHeight + dp(16), dp(64), dp(14));
        header.addView(info, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(32));
        if (user != null) {
            avatar.setForUserOrChat(user, new AvatarDrawable(user));
        }
        info.addView(avatar, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView name = new TextView(context);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setText(user != null ? UserObject.getUserName(user) : "ParadoksGram");
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(12);
        info.addView(name, nameParams);

        if (user != null && user.phone != null && !user.phone.isEmpty()) {
            TextView phone = new TextView(context);
            phone.setTextColor(0xB3FFFFFF);
            phone.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            phone.setSingleLine(true);
            phone.setText(PhoneFormat.getInstance().format("+" + user.phone));
            info.addView(phone, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // day / night
        final ImageView night = new ImageView(context);
        night.setScaleType(ImageView.ScaleType.CENTER);
        night.setImageResource(Theme.isCurrentThemeDark() ? R.drawable.menu_day_mode_24 : R.drawable.menu_night_mode_24);
        night.setColorFilter(Color.WHITE);
        night.setOnClickListener(v -> toggleDayNight(night));
        FrameLayout.LayoutParams nightParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.TOP);
        nightParams.topMargin = AndroidUtilities.statusBarHeight + dp(8);
        header.addView(night, nightParams);

        // saved messages shortcut
        final ImageView saved = new ImageView(context);
        saved.setScaleType(ImageView.ScaleType.CENTER);
        saved.setImageResource(R.drawable.outline_saved_24);
        saved.setColorFilter(Color.WHITE);
        saved.setOnClickListener(v -> close(() -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
            fragment.presentFragment(new ChatActivity(args));
        }));
        FrameLayout.LayoutParams savedParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.TOP);
        savedParams.topMargin = AndroidUtilities.statusBarHeight + dp(56);
        header.addView(saved, savedParams);

        // expand / collapse the account list
        final TextView arrow = new TextView(context);
        arrow.setGravity(Gravity.CENTER);
        arrow.setText("\u25BE");
        arrow.setTextColor(Color.WHITE);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        arrow.setOnClickListener(v -> {
            boolean show = accountsBox.getVisibility() != View.VISIBLE;
            accountsBox.setVisibility(show ? View.VISIBLE : View.GONE);
            arrow.setText(show ? "\u25B4" : "\u25BE");
        });
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM);
        header.addView(arrow, arrowParams);
        return header;
    }

    private void fillAccounts(LinearLayout box, Context context, int currentAccount) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            final int index = a;
            TLRPC.User u = UserConfig.getInstance(a).getCurrentUser();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), 0, dp(16), 0);
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));

            BackupImageView image = new BackupImageView(context);
            image.setRoundRadius(dp(18));
            if (u != null) {
                image.setForUserOrChat(u, new AvatarDrawable(u));
            }
            row.addView(image, new LinearLayout.LayoutParams(dp(36), dp(36)));

            TextView text = new TextView(context);
            text.setText((u != null ? UserObject.getUserName(u) : "?") + (a == currentAccount ? "  \u2713" : ""));
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            text.setSingleLine(true);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = dp(14);
            row.addView(text, tp);

            int unread = 0;
            try {
                unread = MessagesStorage.getInstance(a).getMainUnreadCount();
            } catch (Throwable ignore) {
            }
            if (a != currentAccount && unread > 0) {
                TextView badge = new TextView(context);
                badge.setText(unread > 99999 ? "99999+" : String.valueOf(unread));
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setGravity(Gravity.CENTER);
                badge.setPadding(dp(8), dp(2), dp(8), dp(2));
                android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
                pill.setCornerRadius(dp(12));
                pill.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
                badge.setBackground(pill);
                row.addView(badge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.setOnClickListener(v -> {
                if (index == currentAccount) {
                    close(null);
                    return;
                }
                close(() -> {
                    if (fragment.getParentActivity() instanceof LaunchActivity) {
                        ((LaunchActivity) fragment.getParentActivity()).switchToAccount(index, true);
                    }
                });
            });
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
        if (UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT) {
            addRow(box, context, R.drawable.msg_addcontact, "Hisob qo'shish", this::addAccount);
        }
        addDivider(box, context);
    }

    private void addAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                fragment.presentFragment(new LoginActivity(a));
                return;
            }
        }
    }

    private String onlineContactsText(int account) {
        try {
            int now = ConnectionsManager.getInstance(account).getCurrentTime();
            int online = 0;
            for (TLRPC.TL_contact c : ContactsController.getInstance(account).contacts) {
                TLRPC.User u = MessagesController.getInstance(account).getUser(c.user_id);
                if (u != null && !u.self && !u.bot && u.status != null && u.status.expires > now) {
                    online++;
                }
            }
            return online > 0 ? online + " ta onlayn" : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private void showCounters(int account) {
        close(() -> fragment.presentFragment(new ParadoksCountersActivity()));
    }

    private boolean switchingTheme;

    /** Same choice the \u22ee menu makes: go to the last used light theme, or the last used dark one. */
    private void toggleDayNight(View anchor) {
        if (switchingTheme) {
            return;
        }
        switchingTheme = true;
        android.content.SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", Context.MODE_PRIVATE);
        boolean dark = Theme.isCurrentThemeDark();
        Theme.ThemeInfo info;
        if (dark) {
            String day = prefs.getString("lastDayTheme", "Blue");
            info = Theme.getTheme(day);
            if (info == null || info.isDark()) {
                info = Theme.getTheme("Blue");
            }
        } else {
            String night = prefs.getString("lastDarkTheme", "Dark Blue");
            info = Theme.getTheme(night);
            if (info == null || !info.isDark()) {
                info = Theme.getTheme("Dark Blue");
            }
        }
        if (info == null) {
            switchingTheme = false;
            return;
        }
        int[] pos = new int[2];
        anchor.getLocationInWindow(pos);
        pos[0] += anchor.getMeasuredWidth() / 2;
        pos[1] += anchor.getMeasuredHeight() / 2;
        final Theme.ThemeInfo target = info;
        final boolean toDark = !dark;
        close(() -> {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, target, false, pos, -1, toDark, null, null, null, true);
        });
    }

    private void addRow(LinearLayout parent, Context context, int icon, String title, Runnable action) {
        addRow(parent, context, icon, title, null, action);
    }

    private void addRow(LinearLayout parent, Context context, int icon, String title, String value, Runnable action) {
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

        if (value != null && !value.isEmpty()) {
            TextView right = new TextView(context);
            right.setText(value);
            right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            right.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            right.setSingleLine(true);
            row.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

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
