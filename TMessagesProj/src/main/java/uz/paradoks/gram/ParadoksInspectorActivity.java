package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ProfileActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * "Xabar xususiyatlari" screen: the most important fields first (message, id, sender, chat, dates),
 * then sender, details, time, reply/forward, stats, flags, technical info and the raw TL object as JSON.
 */
public class ParadoksInspectorActivity extends BaseFragment {

    private static final int ID_COPY = 1;
    private static final int ID_SHARE = 2;
    private static final int ID_SAVE = 3;

    private final MessageObject messageObject;
    private final MessageObject.GroupedMessages group;

    private ArrayList<ParadoksInspector.Section> sections;
    private String allText;
    private String json;

    private FrameLayout frame;
    private ScrollView listScroll;
    private ScrollView jsonScroll;
    private TextView jsonView;
    private boolean jsonMode;
    private ActionBarMenuItem saveItem;

    public ParadoksInspectorActivity(MessageObject messageObject, MessageObject.GroupedMessages group) {
        super();
        this.messageObject = messageObject;
        this.group = group;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Xabar xususiyatlari");
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(ID_COPY, R.drawable.msg_copy);
        menu.addItem(ID_SHARE, R.drawable.msg_shareout);
        saveItem = menu.addItem(ID_SAVE, R.drawable.msg_download);
        saveItem.setVisibility(View.GONE);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (jsonMode) {
                        showJson(false);
                    } else {
                        finishFragment();
                    }
                } else if (id == ID_COPY) {
                    AndroidUtilities.addToClipboard(currentText());
                    BulletinFactory.of(ParadoksInspectorActivity.this).createCopyBulletin("Nusxalandi").show();
                } else if (id == ID_SHARE) {
                    share(currentText());
                } else if (id == ID_SAVE) {
                    saveJsonFile();
                }
            }
        });

        sections = ParadoksInspector.build(currentAccount, messageObject, group);
        allText = ParadoksInspector.toText(sections);

        frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listScroll = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        for (ParadoksInspector.Section section : sections) {
            addSection(context, content, section);
        }
        listScroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(listScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = frame;
        return frame;
    }

    private String currentText() {
        return jsonMode ? getJson() : allText;
    }

    private String getJson() {
        if (json == null) {
            json = messageObject != null && messageObject.messageOwner != null ? ParadoksInspector.toJson(messageObject.messageOwner) : "{}";
        }
        return json;
    }

    // JSON'ni .json fayl qilib Yuklamalar (Downloads) papkasiga saqlaydi
    private void saveJsonFile() {
        if (getParentActivity() == null || messageObject == null) {
            return;
        }
        try {
            final String name = "message_" + messageObject.getDialogId() + "_" + messageObject.getId() + ".json";
            final File file = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(getJson().getBytes(StandardCharsets.UTF_8));
            }
            MediaController.saveFile(file.getAbsolutePath(), getParentActivity(), 2, name, "application/json");
        } catch (Throwable t) {
            BulletinFactory.of(this).createCopyBulletin("Saqlab bo'lmadi").show();
        }
    }

    private void share(String text) {
        if (getParentActivity() == null || text == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            getParentActivity().startActivity(Intent.createChooser(intent, "Ulashish"));
        } catch (Throwable ignore) {
        }
    }

    private void addSection(Context context, LinearLayout parent, ParadoksInspector.Section section) {
        TextView header = new TextView(context);
        header.setText(section.title);
        header.setTextSize(14);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        header.setPadding(dp(18), dp(16), dp(18), dp(6));
        parent.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        for (int i = 0; i < section.rows.size(); i++) {
            ParadoksInspector.Row row = section.rows.get(i);
            card.addView(createRow(context, row), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < section.rows.size() - 1) {
                View divider = new View(context);
                divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.leftMargin = dp(18);
                card.addView(divider, lp);
            }
        }
        parent.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View createRow(Context context, ParadoksInspector.Row row) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(18), dp(10), dp(12), dp(10));
        line.setBackground(Theme.getSelectorDrawable(false));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(context);
        label.setText(row.label);
        label.setTextSize(13);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        texts.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView value = new TextView(context);
        value.setText(row.value);
        value.setTextSize(16);
        value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (row.emphasis != ParadoksInspector.EMPH_NONE) {
            // "Holat" qatorlari: kattaroq, qalin va rangli
            value.setTextSize(18);
            value.setTypeface(AndroidUtilities.bold());
            final int colorKey = row.emphasis == ParadoksInspector.EMPH_WARN ? Theme.key_text_RedBold
                    : row.emphasis == ParadoksInspector.EMPH_INFO ? Theme.key_windowBackgroundWhiteBlueText
                    : Theme.key_windowBackgroundWhiteGreenText;
            value.setTextColor(Theme.getColor(colorKey));
        }
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.topMargin = dp(2);
        texts.addView(value, valueParams);

        line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        if (row.action == ParadoksInspector.ACTION_NONE) {
            icon.setImageResource(R.drawable.msg_copy);
        } else {
            icon.setImageResource(R.drawable.msg_arrow_forward);
        }
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        line.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        line.setOnClickListener(v -> onRowClick(row));
        return line;
    }

    private void onRowClick(ParadoksInspector.Row row) {
        if (row.action == ParadoksInspector.ACTION_RAW_JSON) {
            showJson(true);
        } else if (row.action == ParadoksInspector.ACTION_OPEN_SENDER) {
            Bundle args = new Bundle();
            args.putLong("user_id", row.actionId);
            presentFragment(new ProfileActivity(args));
        } else {
            AndroidUtilities.addToClipboard(row.value);
            BulletinFactory.of(this).createCopyBulletin(row.label + " nusxalandi").show();
        }
    }

    private void showJson(boolean show) {
        if (frame == null) {
            return;
        }
        jsonMode = show;
        if (show) {
            if (jsonScroll == null) {
                Context context = frame.getContext();
                jsonScroll = new ScrollView(context);
                jsonView = new TextView(context);
                jsonView.setTypeface(Typeface.MONOSPACE);
                jsonView.setTextSize(12);
                jsonView.setTextIsSelectable(true);
                jsonView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                jsonView.setPadding(dp(14), dp(12), dp(14), dp(24));
                jsonView.setText(getJson());
                jsonScroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                jsonScroll.addView(jsonView, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                frame.addView(jsonScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
            jsonScroll.setVisibility(View.VISIBLE);
            listScroll.setVisibility(View.GONE);
            saveItem.setVisibility(View.VISIBLE);
            actionBar.setTitle("Xom Telegram obyekti");
        } else {
            if (jsonScroll != null) {
                jsonScroll.setVisibility(View.GONE);
            }
            listScroll.setVisibility(View.VISIBLE);
            saveItem.setVisibility(View.GONE);
            actionBar.setTitle("Xabar xususiyatlari");
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (jsonMode) {
            if (invoked) {
                showJson(false);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }
}
