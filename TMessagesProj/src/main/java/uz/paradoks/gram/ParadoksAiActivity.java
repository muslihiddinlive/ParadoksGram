package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * The "Paradoks AI" window. It is a light chat screen over the conversation with @ParadoksAiBot: your messages go to the
 * bot from your own account, the bot's answers appear here. The whole agent (memory, tools, limits, plans) lives in the bot.
 */
public class ParadoksAiActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int T_BUBBLE = 0;
    private static final int T_TYPING = 1;
    private static final long WAIT_TIMEOUT_MS = 90_000;
    private static final int MENU_OPEN_BOT = 1;
    private static final int MENU_CLEAR = 2;

    private static final String[] SUGGESTIONS = {
            "Bugun nimalar qila olasan?",
            "Menga qisqa reja tuzib ber",
            "Matnni o'zbekchaga tarjima qil",
            "Kod yozishda yordam ber"
    };

    private long botId;
    private final ArrayList<MessageObject> items = new ArrayList<>();
    private boolean waiting;
    private long waitingSince;
    private RecyclerListView listView;
    private Adapter adapter;
    private EditText input;
    private View emptyView;
    private final Runnable waitTimeout = () -> {
        waiting = false;
        refresh();
    };

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.addObserver(this, NotificationCenter.didReceiveNewMessages);
        center.addObserver(this, NotificationCenter.replaceMessagesObjects);
        center.addObserver(this, NotificationCenter.messageReceivedByServer);
        botId = ParadoksConfig.aiBotId();
        if (botId == 0) {
            resolveBot(null);
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        center.removeObserver(this, NotificationCenter.replaceMessagesObjects);
        center.removeObserver(this, NotificationCenter.messageReceivedByServer);
        AndroidUtilities.cancelRunOnUIThread(waitTimeout);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getParentActivity() != null) {
            AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getParentActivity() != null) {
            AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
        }
    }

    // ------------------------------------------------------------------ the bot

    private void resolveBot(Runnable done) {
        TLRPC.TL_contacts_resolveUsername request = new TLRPC.TL_contacts_resolveUsername();
        request.username = ParadoksConfig.aiBotUsername();
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(currentAccount).putUsers(resolved.users, false);
                MessagesController.getInstance(currentAccount).putChats(resolved.chats, false);
                if (resolved.peer != null && resolved.peer.user_id != 0) {
                    botId = resolved.peer.user_id;
                    ParadoksConfig.setAiBotId(botId);
                }
            }
            if (done != null) {
                done.run();
            }
        }));
    }

    private void send(String text) {
        final String message = text == null ? "" : text.trim();
        if (message.isEmpty()) {
            return;
        }
        if (botId == 0) {
            Toast.makeText(getParentActivity(), "Bot qidirilmoqda...", Toast.LENGTH_SHORT).show();
            resolveBot(() -> {
                if (botId != 0) {
                    send(message);
                } else if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), "@" + ParadoksConfig.aiBotUsername() + " topilmadi. Internetni tekshiring.", Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        SendMessagesHelper helper = SendMessagesHelper.getInstance(currentAccount);
        if (!ParadoksConfig.aiStarted()) {
            // the bot needs /start once, unless you already talked to it
            if (MessagesController.getInstance(currentAccount).dialogs_dict.get(botId) == null) {
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of("/start", botId));
            }
            ParadoksConfig.setAiStarted(true);
        }
        helper.sendMessage(SendMessagesHelper.SendMessageParams.of(message, botId));
        waiting = true;
        waitingSince = System.currentTimeMillis();
        AndroidUtilities.cancelRunOnUIThread(waitTimeout);
        AndroidUtilities.runOnUIThread(waitTimeout, WAIT_TIMEOUT_MS);
        refresh();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        try {
            if (botId == 0) {
                return;
            }
            if (id == NotificationCenter.didReceiveNewMessages) {
                if ((long) args[0] != botId || Boolean.TRUE.equals(args[2])) {
                    return;
                }
                boolean incoming = false;
                for (MessageObject object : new ArrayList<>((ArrayList<MessageObject>) args[1])) {
                    if (indexOf(object.getId()) < 0) {
                        items.add(object);
                        if (!object.isOut()) {
                            incoming = true;
                        }
                    }
                }
                if (incoming) {
                    waiting = false;
                    AndroidUtilities.cancelRunOnUIThread(waitTimeout);
                }
                refresh();
            } else if (id == NotificationCenter.replaceMessagesObjects) {
                if ((long) args[0] != botId) {
                    return;
                }
                boolean changed = false;
                for (MessageObject object : (ArrayList<MessageObject>) args[1]) {
                    int index = indexOf(object.getId());
                    if (index >= 0) {
                        items.set(index, object);
                        changed = true;
                    }
                }
                if (changed) {
                    refresh();
                }
            } else if (id == NotificationCenter.messageReceivedByServer) {
                if (args.length < 4 || (long) args[3] != botId) {
                    return;
                }
                int index = indexOf((Integer) args[0]);
                if (index >= 0) {
                    TLRPC.Message real = (TLRPC.Message) args[2];
                    if (real != null) {
                        items.set(index, new MessageObject(currentAccount, real, true, true));
                    }
                    refresh();
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private int indexOf(int messageId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId() == messageId) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ UI

    @Override
    public View createView(Context context) {
        Theme.createChatResources(context, false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Paradoks AI");
        actionBar.setSubtitle("@" + ParadoksConfig.aiBotUsername());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_OPEN_BOT) {
                    if (botId != 0) {
                        Bundle args = new Bundle();
                        args.putLong("user_id", botId);
                        presentFragment(new ChatActivity(args));
                    }
                } else if (id == MENU_CLEAR) {
                    items.clear();
                    waiting = false;
                    refresh();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_OPEN_BOT, R.drawable.msg_info, "Bot chatini ochish (fayl, tugmalar)");
        other.addSubItem(MENU_CLEAR, R.drawable.msg_delete, "Oynani tozalash");

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        FrameLayout body = new FrameLayout(context);
        root.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context);
        listView.setItemAnimator(null);
        LinearLayoutManager manager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(manager);
        listView.setPadding(0, dp(8), 0, dp(8));
        listView.setClipToPadding(false);
        listView.setAdapter(adapter = new Adapter(context));
        body.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = buildEmptyView(context);
        body.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        root.addView(buildInputPanel(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        refresh();
        return fragmentView;
    }

    private View buildEmptyView(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(16), dp(24), dp(16));

        ParadoksAiLogoView logo = new ParadoksAiLogoView(context);
        logo.setBackdropKey(Theme.key_windowBackgroundGray);
        box.addView(logo, LayoutHelper.createLinear(150, 150, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(context);
        title.setText("Paradoks AI");
        title.setTextSize(24);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText("Savol bering, men xotira va vositalar bilan javob beraman. Fayl yoki tugmali javoblar uchun yuqoridagi menyudan bot chatini oching.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setGravity(Gravity.CENTER);
        box.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 14));

        for (String suggestion : SUGGESTIONS) {
            TextView chip = new TextView(context);
            chip.setText(suggestion);
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), dp(9), dp(16), dp(9));
            chip.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.blendOver(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.12f))));
            chip.setOnClickListener(v -> send(suggestion));
            box.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 5, 0, 5));
        }
        return box;
    }

    private View buildInputPanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.BOTTOM);
        panel.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        panel.setPadding(dp(8), dp(6), dp(8), dp(6));

        input = new EditText(context);
        input.setHint("Paradoks AI'dan so'rang...");
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setTextSize(16);
        input.setMaxLines(6);
        input.setBackground(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView sendButton = new ImageView(context);
        sendButton.setImageResource(R.drawable.msg_send);
        sendButton.setColorFilter(0xffffffff);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setBackground(Theme.createCircleDrawable(dp(42), Theme.getColor(Theme.key_featuredStickers_addButton)));
        sendButton.setContentDescription("Yuborish");
        sendButton.setOnClickListener(v -> {
            String text = input.getText().toString();
            if (!text.trim().isEmpty()) {
                input.setText("");
                send(text);
            }
        });
        panel.addView(sendButton, LayoutHelper.createLinear(42, 42, Gravity.BOTTOM, 0, 0, 0, 2));
        return panel;
    }

    private void refresh() {
        boolean typing = waiting && System.currentTimeMillis() - waitingSince < WAIT_TIMEOUT_MS;
        if (emptyView != null) {
            emptyView.setVisibility(items.isEmpty() && !typing ? View.VISIBLE : View.GONE);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            int count = adapter.getItemCount();
            if (listView != null && count > 0) {
                listView.scrollToPosition(count - 1);
            }
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        private boolean typing() {
            return waiting && System.currentTimeMillis() - waitingSince < WAIT_TIMEOUT_MS;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return items.size() + (typing() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            return position < items.size() ? T_BUBBLE : T_TYPING;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == T_BUBBLE) {
                ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                });
                view = cell;
            } else {
                FrameLayout holder = new FrameLayout(context);
                TextView pill = new TextView(context);
                pill.setText("Paradoks AI yozmoqda...");
                pill.setTextSize(13);
                pill.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                pill.setPadding(dp(12), dp(6), dp(12), dp(6));
                holder.addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 12, 6, 0, 6));
                view = holder;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == T_BUBBLE) {
                ((ChatMessageCell) holder.itemView).setMessageObject(items.get(position), null, false, false, false);
            }
        }
    }
}
