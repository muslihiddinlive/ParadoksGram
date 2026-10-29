package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BotForumHelper;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * The Paradoks AI window (Claude-like design). It is a custom screen over the conversation with @ParadoksAiBot:
 * messages go to the bot from the user's own account, so the whole agent (memory, tools, limits, plans) keeps working.
 * The bot's "Topics" (private chat threads) are the user's sessions: they are listed in the side drawer,
 * can be reopened, started and deleted here. Photos, files, text and emoji can be sent.
 */
public class ParadoksAiActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int REQ_PHOTO = 7771;
    private static final int REQ_FILE = 7772;
    private static final int MENU_SESSIONS = 1;
    private static final int MENU_NEW = 2;
    private static final int MENU_OPEN_BOT = 3;
    private static final int MENU_CLEAR = 4;
    private static final int T_USER = 0;
    private static final int T_BOT = 1;
    private static final int T_TYPING = 2;
    private static final long WAIT_TIMEOUT_MS = 120_000;

    private static final String[] SUGGESTIONS = {
            "Bugun nimalar qila olasan?",
            "Menga qisqa reja tuzib ber",
            "Matnni o'zbekchaga tarjima qil",
            "Kod yozishda yordam ber"
    };

    // palette (Claude-like: warm paper in light mode, soft charcoal in dark mode, terracotta accent)
    private boolean dark;
    private int cBg;
    private int cSurface;
    private int cUser;
    private int cText;
    private int cMuted;
    private int cBorder;
    private final int cAccent = 0xFFD97757;

    private static final class Attachment {
        File file;
        String name;
        boolean photo;
    }

    private long botId;
    private TLRPC.User bot;
    private boolean botForum;
    private int topicId; // 0 = a new session (forum bots) or the main chat (ordinary bots)
    private boolean pendingNew; // the first message of a new session was just sent, its topic is being created
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final LinkedHashMap<Integer, MessageObject> drafts = new LinkedHashMap<>();
    private final ArrayList<MessageObject> shown = new ArrayList<>();
    private final ArrayList<TLRPC.TL_forumTopic> topics = new ArrayList<>();
    private final ArrayList<Attachment> pending = new ArrayList<>();
    private boolean waiting;
    private long waitingSince;
    private int loadToken;
    private boolean loadingHistory;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());

    private FrameLayout root;
    private RecyclerListView listView;
    private Adapter adapter;
    private View emptyView;
    private LinearLayout recentBox;
    private EditText input;
    private ImageView sendButton;
    private LinearLayout attachStrip;
    private HorizontalScrollView attachScroll;
    private FrameLayout drawerOverlay;
    private LinearLayout drawerPanel;
    private View scrim;
    private RecyclerListView sessionList;
    private SessionAdapter sessionAdapter;
    private boolean drawerOpen;

    private final Runnable waitTimeout = () -> {
        waiting = false;
        refresh(false);
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.addObserver(this, NotificationCenter.didReceiveNewMessages);
        center.addObserver(this, NotificationCenter.replaceMessagesObjects);
        center.addObserver(this, NotificationCenter.messageReceivedByServer);
        center.addObserver(this, NotificationCenter.topicsDidLoaded);
        center.addObserver(this, NotificationCenter.botForumTopicDidCreate);
        center.addObserver(this, NotificationCenter.botForumDraftUpdate);
        center.addObserver(this, NotificationCenter.botForumDraftDelete);
        botId = ParadoksConfig.aiBotId();
        if (botId == 0) {
            resolveBot(null);
        } else {
            onBotReady();
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        center.removeObserver(this, NotificationCenter.replaceMessagesObjects);
        center.removeObserver(this, NotificationCenter.messageReceivedByServer);
        center.removeObserver(this, NotificationCenter.topicsDidLoaded);
        center.removeObserver(this, NotificationCenter.botForumTopicDidCreate);
        center.removeObserver(this, NotificationCenter.botForumDraftUpdate);
        center.removeObserver(this, NotificationCenter.botForumDraftDelete);
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

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (drawerOpen) {
            if (invoked) {
                closeDrawer();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    // ------------------------------------------------------------------ the bot and its sessions

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
                    onBotReady();
                }
            }
            if (done != null) {
                done.run();
            }
        }));
    }

    private void onBotReady() {
        bot = MessagesController.getInstance(currentAccount).getUser(botId);
        botForum = bot != null && UserObject.isBotForum(bot);
        if (botForum) {
            MessagesController.getInstance(currentAccount).getTopicsController().preloadTopics(-botId);
            MessagesController.getInstance(currentAccount).getTopicsController().loadTopics(-botId);
            reloadTopics();
        } else {
            loadHistory(); // an ordinary bot has one conversation: show it
        }
    }

    private void reloadTopics() {
        topics.clear();
        ArrayList<TLRPC.TL_forumTopic> list = MessagesController.getInstance(currentAccount).getTopicsController().getTopics(-botId);
        if (list != null) {
            topics.addAll(list);
            Collections.sort(topics, (a, b) -> Integer.compare(b.top_message, a.top_message)); // most recent first
        }
        if (sessionAdapter != null) {
            sessionAdapter.notifyDataSetChanged();
        }
        refreshRecent();
    }

    private int topicOf(TLRPC.Message message) {
        if (message == null || message.reply_to == null) {
            return 0;
        }
        return message.reply_to.reply_to_top_id != 0 ? message.reply_to.reply_to_top_id : message.reply_to.reply_to_msg_id;
    }

    private boolean accept(TLRPC.Message message) {
        return !botForum || pendingNew || topicOf(message) == topicId;
    }

    private boolean visible(MessageObject object) {
        TLRPC.Message m = object.messageOwner;
        if (m == null || m instanceof TLRPC.TL_messageService) {
            return false;
        }
        if (m.media != null && (m.media.photo != null || m.media.document != null)) {
            return true;
        }
        return !TextUtils.isEmpty(object.messageText) || !TextUtils.isEmpty(object.caption);
    }

    private void loadHistory() {
        if (botId == 0) {
            return;
        }
        final int token = ++loadToken;
        loadingHistory = true;
        requestHistory(token);
    }

    private void requestHistory(final int token) {
        org.telegram.tgnet.TLObject request;
        if (botForum && topicId != 0) {
            TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(botId);
            req.msg_id = topicId;
            req.limit = 60;
            req.offset_id = 0;
            request = req;
        } else {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(botId);
            req.limit = 60;
            req.offset_id = 0;
            request = req;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (token != loadToken) {
                return;
            }
            loadingHistory = false;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(currentAccount).putUsers(result.users, false);
                MessagesController.getInstance(currentAccount).putChats(result.chats, false);
                ArrayList<MessageObject> loaded = new ArrayList<>();
                for (int i = result.messages.size() - 1; i >= 0; i--) { // the server returns the newest first
                    TLRPC.Message message = result.messages.get(i);
                    if (!accept(message)) {
                        continue;
                    }
                    MessageObject object = new MessageObject(currentAccount, message, true, true);
                    if (visible(object)) {
                        loaded.add(object);
                    }
                }
                // Keep messages that arrived while the history request was in flight (for example the one
                // the user just sent): they are not in the server answer yet, and replacing the list
                // wholesale made them vanish.
                ArrayList<MessageObject> merged = new ArrayList<>(loaded);
                for (MessageObject existing : messages) {
                    boolean present = false;
                    for (MessageObject item : loaded) {
                        if (item.getId() == existing.getId()) {
                            present = true;
                            break;
                        }
                    }
                    if (!present) {
                        merged.add(existing);
                    }
                }
                Collections.sort(merged, (a, b) -> Integer.compare(a.messageOwner.date, b.messageOwner.date));
                messages.clear();
                messages.addAll(merged);
            }
            refresh(true);
        }));
    }

    private void newSession() {
        topicId = 0;
        pendingNew = false;
        messages.clear();
        drafts.clear();
        pending.clear();
        waiting = false;
        loadToken++;
        updateAttachStrip();
        updateTitle();
        refresh(false);
        closeDrawer();
        if (botForum) {
            MessagesController.getInstance(currentAccount).getTopicsController().loadTopics(-botId);
        } else {
            loadHistory();
        }
    }

    private void openSession(int id) {
        topicId = id;
        pendingNew = false;
        messages.clear();
        drafts.clear();
        waiting = false;
        updateTitle();
        refresh(false);
        closeDrawer();
        loadHistory();
    }

    private TLRPC.TL_forumTopic findTopic(int id) {
        for (TLRPC.TL_forumTopic topic : topics) {
            if (topic.id == id) {
                return topic;
            }
        }
        return null;
    }

    private void updateTitle() {
        if (actionBar == null) {
            return;
        }
        String subtitle = "Yangi suhbat";
        if (!botForum) {
            subtitle = "@" + ParadoksConfig.aiBotUsername();
        } else if (topicId != 0) {
            TLRPC.TL_forumTopic topic = findTopic(topicId);
            subtitle = topic != null && !TextUtils.isEmpty(topic.title) ? topic.title : "Suhbat";
        }
        actionBar.setSubtitle(subtitle);
    }

    // ------------------------------------------------------------------ sending

    /** The message that identifies the topic: replies to it end up in that session. */
    private MessageObject topicRoot() {
        if (!botForum || topicId == 0) {
            return null;
        }
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = topicId;
        message.date = (int) (System.currentTimeMillis() / 1000);
        message.dialog_id = botId;
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = botId;
        message.peer_id = peer;
        message.message = "";
        MessageObject object = new MessageObject(currentAccount, message, false, false);
        object.isTopicMainMessage = true;
        return object;
    }

    private boolean botStarted() {
        return MessagesController.getInstance(currentAccount).dialogs_dict.get(botId) != null;
    }

    private void onSend() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() && pending.isEmpty()) {
            return;
        }
        if (botId == 0) {
            Toast.makeText(getParentActivity(), "Bot qidirilmoqda...", Toast.LENGTH_SHORT).show();
            resolveBot(() -> {
                if (botId == 0 && getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), "@" + ParadoksConfig.aiBotUsername() + " topilmadi. Internetni tekshiring.", Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        if (!botStarted()) {
            if (botForum) {
                // a forum bot must be started once in its own chat
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("Botni ishga tushiring");
                builder.setMessage("Birinchi marta @" + ParadoksConfig.aiBotUsername() + " chatini ochib, \"Start\" tugmasini bosing, keyin shu oynaga qayting.");
                builder.setPositiveButton("Bot chatini ochish", (d, w) -> openBotChat());
                builder.setNegativeButton("Bekor qilish", null);
                showDialog(builder.create());
                return;
            }
            SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of("/start", botId));
        }
        input.setText("");
        if (botForum && topicId == 0) {
            pendingNew = true; // the topic of the new session is created by the first message
        }
        final MessageObject top = topicRoot();
        if (pending.isEmpty()) {
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, botId, top, top, null, false, null, null, null, true, 0, 0, null, false);
            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
        } else {
            sendAttachments(text, top);
        }
        waiting = true;
        waitingSince = System.currentTimeMillis();
        AndroidUtilities.cancelRunOnUIThread(waitTimeout);
        AndroidUtilities.runOnUIThread(waitTimeout, WAIT_TIMEOUT_MS);
        refresh(true);
    }

    private void sendAttachments(String caption, MessageObject top) {
        AccountInstance instance = AccountInstance.getInstance(currentAccount);
        ArrayList<SendMessagesHelper.SendingMediaInfo> photos = new ArrayList<>();
        ArrayList<String> files = new ArrayList<>();
        boolean captionUsed = false;
        for (Attachment attachment : pending) {
            if (attachment.photo) {
                SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                info.path = attachment.file.getAbsolutePath();
                if (!captionUsed && !caption.isEmpty()) {
                    info.caption = caption;
                    captionUsed = true;
                }
                photos.add(info);
            } else {
                files.add(attachment.file.getAbsolutePath());
            }
        }
        if (!photos.isEmpty()) {
            SendMessagesHelper.prepareSendingMedia(instance, photos, botId, top, top, null, null, false, photos.size() > 1, null, true, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
        }
        if (!files.isEmpty()) {
            String fileCaption = captionUsed ? "" : caption;
            SendMessagesHelper.prepareSendingDocuments(instance, files, files, null, fileCaption, null, botId, top, top, null, null, null, true, 0, null, null, 0, false, 0);
            captionUsed = true;
        }
        if (!captionUsed && !caption.isEmpty()) {
            SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(caption, botId, top, top, null, false, null, null, null, true, 0, 0, null, false));
        }
        pending.clear();
        updateAttachStrip();
    }

    private void openBotChat() {
        if (botId == 0) {
            return;
        }
        Bundle args = new Bundle();
        args.putLong("user_id", botId);
        presentFragment(new ChatActivity(args));
    }

    // ------------------------------------------------------------------ incoming events

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        try {
            if (id == NotificationCenter.topicsDidLoaded) {
                reloadTopics();
                updateTitle();
                return;
            }
            if (botId == 0) {
                return;
            }
            if (id == NotificationCenter.didReceiveNewMessages) {
                if ((long) args[0] != botId || Boolean.TRUE.equals(args[2])) {
                    return;
                }
                boolean incoming = false;
                for (MessageObject object : new ArrayList<>((ArrayList<MessageObject>) args[1])) {
                    if (accept(object.messageOwner) && visible(object) && indexOf(object.getId()) < 0) {
                        messages.add(object);
                        if (!object.isOut()) {
                            incoming = true;
                        }
                    }
                }
                if (incoming) {
                    waiting = false;
                    AndroidUtilities.cancelRunOnUIThread(waitTimeout);
                }
                refresh(true);
            } else if (id == NotificationCenter.replaceMessagesObjects) {
                if ((long) args[0] != botId) {
                    return;
                }
                boolean changed = false;
                for (MessageObject object : (ArrayList<MessageObject>) args[1]) {
                    int index = indexOf(object.getId());
                    if (index >= 0) {
                        messages.set(index, object);
                        changed = true;
                    }
                }
                if (changed) {
                    refresh(false);
                }
            } else if (id == NotificationCenter.messageReceivedByServer) {
                if (args.length < 4 || (long) args[3] != botId) {
                    return;
                }
                int index = indexOf((Integer) args[0]);
                if (index >= 0 && args[2] != null) {
                    messages.set(index, new MessageObject(currentAccount, (TLRPC.Message) args[2], true, true));
                    refresh(false);
                }
            } else if (id == NotificationCenter.botForumTopicDidCreate) {
                BotForumHelper.BotForumTopicCreateNotification created = (BotForumHelper.BotForumTopicCreateNotification) args[0];
                if (created.dialogId == botId && pendingNew) {
                    topicId = created.topicId;
                    pendingNew = false;
                    MessagesController.getInstance(currentAccount).getTopicsController().loadTopics(-botId);
                    updateTitle();
                }
            } else if (id == NotificationCenter.botForumDraftUpdate) {
                BotForumHelper.BotForumTextDraftUpdateNotification update = (BotForumHelper.BotForumTextDraftUpdateNotification) args[0];
                if (update.botUserId == botId && update.messageObject != null && (!botForum || pendingNew || update.botTopicId == topicId)) {
                    drafts.put(update.messageObject.getId(), update.messageObject);
                    waiting = false;
                    refresh(true);
                }
            } else if (id == NotificationCenter.botForumDraftDelete) {
                BotForumHelper.BotForumTextDraftDeleteNotification removed = (BotForumHelper.BotForumTextDraftDeleteNotification) args[0];
                if (removed.botUserId == botId && drafts.remove(removed.messageId) != null) {
                    refresh(false);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private int indexOf(int messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getId() == messageId) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ view building

    private void initPalette() {
        dark = Theme.isCurrentThemeDark();
        cBg = dark ? 0xFF262624 : 0xFFFAF9F5;
        cSurface = dark ? 0xFF30302E : 0xFFFFFFFF;
        cUser = dark ? 0xFF3B3A37 : 0xFFF0EEE6;
        cText = dark ? 0xFFF0EEE6 : 0xFF1F1E1D;
        cMuted = dark ? 0xFFA6A59E : 0xFF73726C;
        cBorder = dark ? 0x26FFFFFF : 0x1F000000;
    }

    private GradientDrawable rounded(int color, int radiusDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            drawable.setStroke(Math.max(1, dp(1)), stroke);
        }
        return drawable;
    }

    @Override
    public View createView(Context context) {
        initPalette();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setBackgroundColor(cBg);
        actionBar.setTitleColor(cText);
        actionBar.setSubtitleColor(cMuted);
        actionBar.setItemsColor(cText, false);
        actionBar.setItemsBackgroundColor(0x22808080, false);
        actionBar.setTitle("Paradoks AI");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SESSIONS) {
                    if (drawerOpen) {
                        closeDrawer();
                    } else {
                        openDrawer();
                    }
                } else if (id == MENU_NEW) {
                    newSession();
                } else if (id == MENU_OPEN_BOT) {
                    openBotChat();
                } else if (id == MENU_CLEAR) {
                    messages.clear();
                    drafts.clear();
                    waiting = false;
                    refresh(false);
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_NEW, R.drawable.msg_add);
        menu.addItem(MENU_SESSIONS, R.drawable.msg_recent);
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_OPEN_BOT, R.drawable.msg_info, "Bot chatini ochish");
        other.addSubItem(MENU_CLEAR, R.drawable.msg_delete, "Oynani tozalash");
        updateTitle();

        root = new FrameLayout(context);
        root.setBackgroundColor(cBg);
        fragmentView = root;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        FrameLayout body = new FrameLayout(context);
        column.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context);
        listView.setItemAnimator(null);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(8), 0, dp(16));
        listView.setAdapter(adapter = new Adapter(context));
        body.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = buildEmptyView(context);
        body.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        column.addView(buildInputCard(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        buildDrawer(context);
        refresh(false);
        return fragmentView;
    }

    private View buildEmptyView(Context context) {
        FrameLayout scroll = new FrameLayout(context);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        ParadoksAiLogoView logo = new ParadoksAiLogoView(context);
        logo.setColors(cBg, cText);
        box.addView(logo, LayoutHelper.createLinear(104, 104, Gravity.CENTER_HORIZONTAL));

        TextView hello = new TextView(context);
        hello.setText("Salom, men Paradoks AI");
        hello.setTextSize(14);
        hello.setTextColor(cAccent);
        hello.setTypeface(AndroidUtilities.bold());
        hello.setGravity(Gravity.CENTER);
        box.addView(hello, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        TextView title = new TextView(context);
        title.setText("Bugun nima haqida\ngaplashamiz?");
        title.setTextSize(28);
        title.setTypeface(Typeface.SERIF);
        title.setTextColor(cText);
        title.setGravity(Gravity.CENTER);
        title.setLineSpacing(0, 1.1f);
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 18));

        for (String suggestion : SUGGESTIONS) {
            TextView chip = new TextView(context);
            chip.setText(suggestion);
            chip.setTextSize(14);
            chip.setTextColor(cText);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), dp(10), dp(16), dp(10));
            chip.setBackground(rounded(cSurface, 20, cBorder));
            chip.setOnClickListener(v -> {
                input.setText(suggestion);
                input.setSelection(suggestion.length());
                onSend();
            });
            box.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 4));
        }

        recentBox = new LinearLayout(context);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(recentBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 0, 0));
        return scroll;
    }

    /** "Continue" rows with the latest sessions under the greeting. */
    private void refreshRecent() {
        if (recentBox == null) {
            return;
        }
        recentBox.removeAllViews();
        if (topics.isEmpty() || getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        TextView header = new TextView(context);
        header.setText("So'nggi suhbatlar");
        header.setTextSize(13);
        header.setTextColor(cMuted);
        header.setTypeface(AndroidUtilities.bold());
        header.setPadding(dp(4), 0, 0, dp(6));
        recentBox.addView(header);
        for (int i = 0; i < Math.min(3, topics.size()); i++) {
            final TLRPC.TL_forumTopic topic = topics.get(i);
            TextView row = new TextView(context);
            row.setText(TextUtils.isEmpty(topic.title) ? "Suhbat" : topic.title);
            row.setTextSize(15);
            row.setTextColor(cText);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(rounded(cSurface, 14, cBorder));
            row.setOnClickListener(v -> openSession(topic.id));
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6);
            recentBox.addView(row, lp);
        }
    }

    private View buildInputCard(Context context) {
        FrameLayout outer = new FrameLayout(context);
        outer.setPadding(dp(10), dp(4), dp(10), dp(10));
        outer.setBackgroundColor(cBg);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(cSurface, 26, cBorder));
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        outer.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        attachScroll = new HorizontalScrollView(context);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.setVisibility(View.GONE);
        attachStrip = new LinearLayout(context);
        attachStrip.setOrientation(LinearLayout.HORIZONTAL);
        attachStrip.setPadding(dp(8), dp(6), dp(8), dp(2));
        attachScroll.addView(attachStrip);
        card.addView(attachScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        input = new EditText(context);
        input.setHint("Paradoks AI bilan gaplashing");
        input.setHintTextColor(cMuted);
        input.setTextColor(cText);
        input.setTextSize(16);
        input.setMaxLines(7);
        input.setBackground(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(12), dp(8), dp(12), dp(6));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSendButton();
            }
        });
        card.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(bottom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ImageView attach = new ImageView(context);
        attach.setImageResource(R.drawable.input_attach);
        attach.setColorFilter(cMuted);
        attach.setScaleType(ImageView.ScaleType.CENTER);
        attach.setBackground(rounded(0x00000000, 18, cBorder));
        attach.setContentDescription("Biriktirish");
        attach.setOnClickListener(v -> showAttachMenu());
        bottom.addView(attach, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        View spacer = new View(context);
        bottom.addView(spacer, LayoutHelper.createLinear(0, 1, 1f));

        sendButton = new ImageView(context);
        sendButton.setImageResource(R.drawable.msg_send);
        sendButton.setColorFilter(0xFFFFFFFF);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setContentDescription("Yuborish");
        sendButton.setOnClickListener(v -> onSend());
        bottom.addView(sendButton, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        String prepared = ParadoksAiTools.takePendingPrompt(); // request prepared from a chat menu: the user reviews it and presses send
        if (prepared != null) {
            input.setText(prepared);
            input.setSelection(input.getText().length());
        }
        updateSendButton();
        return outer;
    }

    private void updateSendButton() {
        if (sendButton == null) {
            return;
        }
        boolean ready = (input != null && !input.getText().toString().trim().isEmpty()) || !pending.isEmpty();
        sendButton.setBackground(rounded(ready ? cAccent : (dark ? 0xFF4A4A47 : 0xFFD9D6CB), 18, 0));
        sendButton.setAlpha(ready ? 1f : 0.85f);
    }

    // ------------------------------------------------------------------ attachments

    private void showAttachMenu() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Biriktirish");
        builder.setItems(new CharSequence[]{"Rasm (galereya)", "Fayl"}, (d, which) -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            if (which == 0) {
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, "Rasm tanlang"), REQ_PHOTO);
            } else {
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent, "Fayl tanlang"), REQ_FILE);
            }
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if ((requestCode != REQ_PHOTO && requestCode != REQ_FILE) || resultCode != android.app.Activity.RESULT_OK || data == null) {
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        final boolean photo = requestCode == REQ_PHOTO;
        // copy in the background: the picked content may be big
        new Thread(() -> {
            final ArrayList<Attachment> added = new ArrayList<>();
            for (Uri uri : uris) {
                Attachment attachment = copyToCache(uri, photo);
                if (attachment != null) {
                    added.add(attachment);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                pending.addAll(added);
                updateAttachStrip();
            });
        }, "ParadoksAiAttach").start();
    }

    private Attachment copyToCache(Uri uri, boolean photo) {
        try {
            Context context = ApplicationLoader.applicationContext;
            String name = "fayl";
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0 && cursor.getString(index) != null) {
                        name = cursor.getString(index);
                    }
                }
            } catch (Throwable ignore) {
            }
            File dir = new File(context.getCacheDir(), "paradoks_ai");
            dir.mkdirs();
            File out = new File(dir, System.currentTimeMillis() + "_" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
            try (InputStream in = context.getContentResolver().openInputStream(uri); FileOutputStream os = new FileOutputStream(out)) {
                if (in == null) {
                    return null;
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
            Attachment attachment = new Attachment();
            attachment.file = out;
            attachment.name = name;
            attachment.photo = photo;
            return attachment;
        } catch (Throwable t) {
            return null;
        }
    }

    private void updateAttachStrip() {
        if (attachStrip == null || getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        attachStrip.removeAllViews();
        for (final Attachment attachment : pending) {
            FrameLayout chip = new FrameLayout(context);
            if (attachment.photo) {
                ImageView thumb = new ImageView(context);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 8;
                Bitmap bitmap = BitmapFactory.decodeFile(attachment.file.getAbsolutePath(), options);
                if (bitmap != null) {
                    thumb.setImageBitmap(bitmap);
                }
                thumb.setBackground(rounded(cUser, 12, 0));
                thumb.setClipToOutline(true);
                chip.addView(thumb, LayoutHelper.createFrame(64, 64));
            } else {
                TextView file = new TextView(context);
                file.setText(attachment.name);
                file.setTextSize(12);
                file.setTextColor(cText);
                file.setMaxLines(2);
                file.setEllipsize(TextUtils.TruncateAt.END);
                file.setGravity(Gravity.CENTER);
                file.setPadding(dp(8), dp(6), dp(8), dp(6));
                file.setBackground(rounded(cUser, 12, 0));
                chip.addView(file, LayoutHelper.createFrame(96, 64));
            }
            TextView remove = new TextView(context);
            remove.setText("✕");
            remove.setTextSize(11);
            remove.setTextColor(0xFFFFFFFF);
            remove.setGravity(Gravity.CENTER);
            remove.setBackground(rounded(0xCC000000, 9, 0));
            remove.setOnClickListener(v -> {
                pending.remove(attachment);
                updateAttachStrip();
            });
            chip.addView(remove, LayoutHelper.createFrame(18, 18, Gravity.TOP | Gravity.RIGHT, 0, 2, 2, 0));
            attachStrip.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        }
        attachScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        updateSendButton();
    }

    // ------------------------------------------------------------------ the list

    private boolean typing() {
        return waiting && System.currentTimeMillis() - waitingSince < WAIT_TIMEOUT_MS;
    }

    private void refresh(boolean scrollToBottom) {
        shown.clear();
        shown.addAll(messages);
        shown.addAll(drafts.values());
        boolean empty = shown.isEmpty() && !typing() && !loadingHistory;
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            int count = adapter.getItemCount();
            if (scrollToBottom && listView != null && count > 0) {
                listView.scrollToPosition(count - 1);
            }
        }
    }

    private CharSequence textOf(MessageObject object) {
        TLRPC.MessageMedia media = object.messageOwner.media;
        boolean mediaMessage = media != null && (media.photo != null || media.document != null);
        CharSequence text = mediaMessage ? object.caption : object.messageText;
        return text == null ? "" : text;
    }

    /** Links in the bot's answers open in the browser. */
    private CharSequence linkify(CharSequence text, final Context context) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        for (URLSpan span : builder.getSpans(0, builder.length(), URLSpan.class)) {
            final String url = span.getURL();
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            builder.removeSpan(span);
            if (url != null && (url.startsWith("http") || url.startsWith("tg:") || url.startsWith("mailto:"))) {
                builder.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        Browser.openUrl(context, url);
                    }

                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(cAccent);
                        ds.setUnderlineText(true);
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return builder;
    }

    private class MessageRow extends LinearLayout {

        private final boolean user;
        private final ParadoksAiLogoView logo;
        private final LinearLayout media;
        private final TextView text;
        private final LinearLayout actions;
        private final ImageView copy;

        MessageRow(Context context, boolean user) {
            super(context);
            this.user = user;
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(6), dp(16), dp(6));
            int maxWidth = (int) (AndroidUtilities.displaySize.x * 0.82f);

            LinearLayout box = new LinearLayout(context);
            box.setOrientation(VERTICAL);
            media = new LinearLayout(context);
            media.setOrientation(VERTICAL);
            text = new TextView(context);
            text.setTextSize(16);
            text.setTextColor(cText);
            text.setLineSpacing(0, 1.25f);
            text.setMovementMethod(LinkMovementMethod.getInstance());

            if (user) {
                logo = null;
                actions = null;
                copy = null;
                box.setBackground(rounded(cUser, 20, 0));
                box.setPadding(dp(14), dp(10), dp(14), dp(10));
                media.setPadding(0, 0, 0, 0);
                box.addView(media);
                box.addView(text);
                text.setMaxWidth(maxWidth - dp(28));
                addView(box, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));
            } else {
                logo = new ParadoksAiLogoView(context);
                logo.setColors(cBg, cText);
                addView(logo, LayoutHelper.createLinear(26, 26, Gravity.LEFT, 0, 0, 0, 6));
                box.addView(media);
                box.addView(text);
                addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                actions = new LinearLayout(context);
                actions.setOrientation(HORIZONTAL);
                copy = new ImageView(context);
                copy.setImageResource(R.drawable.msg_copy);
                copy.setColorFilter(cMuted);
                copy.setScaleType(ImageView.ScaleType.CENTER);
                copy.setContentDescription("Nusxa olish");
                actions.addView(copy, LayoutHelper.createLinear(32, 32));
                addView(actions, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, -6, 2, 0, 0));
            }
        }

        void bind(final MessageObject object, boolean showLogo) {
            final CharSequence content = textOf(object);
            text.setVisibility(content.length() == 0 ? View.GONE : View.VISIBLE);
            text.setText(linkify(content, getContext()));
            media.removeAllViews();
            addMedia(object);
            if (!user) {
                logo.setVisibility(showLogo ? View.VISIBLE : View.GONE);
                actions.setVisibility(content.length() == 0 ? View.GONE : View.VISIBLE);
                copy.setOnClickListener(v -> {
                    AndroidUtilities.addToClipboard(content.toString());
                    Toast.makeText(getContext(), "Nusxa olindi", Toast.LENGTH_SHORT).show();
                });
            }
            text.setOnLongClickListener(v -> {
                AndroidUtilities.addToClipboard(content.toString());
                Toast.makeText(getContext(), "Nusxa olindi", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        private void addMedia(final MessageObject object) {
            Context context = getContext();
            TLRPC.MessageMedia m = object.messageOwner.media;
            if (m == null) {
                return;
            }
            if (m.photo != null && object.photoThumbs != null) {
                TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(object.photoThumbs, AndroidUtilities.getPhotoSize());
                TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(object.photoThumbs, 40);
                int maxW = dp(user ? 230 : 290);
                int w = big != null && big.w > 0 ? big.w : 800;
                int h = big != null && big.h > 0 ? big.h : 800;
                float scale = Math.min(maxW / (float) w, dp(340) / (float) h);
                BackupImageView image = new BackupImageView(context);
                image.setRoundRadius(dp(14));
                image.setImage(ImageLocation.getForObject(big, object.photoThumbsObject), "320_320", ImageLocation.getForObject(small, object.photoThumbsObject), "b", 0, object);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(dp(120), (int) (w * scale)), Math.max(dp(120), (int) (h * scale)));
                lp.bottomMargin = dp(6);
                media.addView(image, lp);
            } else if (m.document != null) {
                final TLRPC.Document document = m.document;
                LinearLayout card = new LinearLayout(context);
                card.setOrientation(HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(10), dp(10), dp(12), dp(10));
                card.setBackground(rounded(user ? cBg : cSurface, 14, cBorder));
                String name = object.getDocumentName();
                if (name == null || name.isEmpty()) {
                    name = "fayl";
                }
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : "FILE";
                TextView badge = new TextView(context);
                badge.setText(ext.length() > 4 ? ext.substring(0, 4) : ext);
                badge.setTextSize(11);
                badge.setTypeface(AndroidUtilities.bold());
                badge.setTextColor(0xFFFFFFFF);
                badge.setGravity(Gravity.CENTER);
                badge.setBackground(rounded(cAccent, 10, 0));
                card.addView(badge, LayoutHelper.createLinear(44, 44));
                LinearLayout info = new LinearLayout(context);
                info.setOrientation(VERTICAL);
                TextView nameView = new TextView(context);
                nameView.setText(name);
                nameView.setTextSize(14);
                nameView.setTextColor(cText);
                nameView.setSingleLine(true);
                nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                nameView.setMaxWidth(dp(200));
                TextView sizeView = new TextView(context);
                sizeView.setText(AndroidUtilities.formatFileSize(document.size));
                sizeView.setTextSize(12);
                sizeView.setTextColor(cMuted);
                info.addView(nameView);
                info.addView(sizeView);
                card.addView(info, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 0, 0));
                final String finalName = name;
                card.setOnClickListener(v -> openDocument(object, document, finalName));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(6);
                media.addView(card, lp);
            }
        }
    }

    private void openDocument(MessageObject object, TLRPC.Document document, String name) {
        try {
            File file = FileLoader.getInstance(currentAccount).getPathToMessage(object.messageOwner);
            if (file != null && file.exists() && getParentActivity() != null) {
                AndroidUtilities.openForView(file, name, document.mime_type, getParentActivity(), null, false);
            } else {
                FileLoader.getInstance(currentAccount).loadFile(document, object, FileLoader.PRIORITY_NORMAL, 0);
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), "Yuklanmoqda... tugagach qayta bosing", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** Three pulsing dots while the answer is being prepared. */
    private class TypingDots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TypingDots(Context context) {
            super(context);
            paint.setColor(cMuted);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long time = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                float phase = (float) ((Math.sin(time / 260.0 - i * 0.9) + 1) / 2);
                paint.setAlpha((int) (70 + 185 * phase));
                canvas.drawCircle(dp(10) + i * dp(14), getHeight() / 2f, dp(3.5f) + dp(1.5f) * phase, paint);
            }
            postInvalidateOnAnimation();
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return shown.size() + (typing() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            if (position >= shown.size()) {
                return T_TYPING;
            }
            return shown.get(position).isOut() ? T_USER : T_BOT;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == T_TYPING) {
                FrameLayout holder = new FrameLayout(context);
                holder.setPadding(dp(16), dp(6), dp(16), dp(6));
                holder.addView(new TypingDots(context), LayoutHelper.createFrame(70, 28, Gravity.LEFT));
                view = holder;
            } else {
                view = new MessageRow(context, viewType == T_USER);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof MessageRow) {
                boolean showLogo = position == 0 || shown.get(position - 1).isOut();
                ((MessageRow) holder.itemView).bind(shown.get(position), showLogo);
            }
        }
    }

    // ------------------------------------------------------------------ sessions drawer

    private void buildDrawer(Context context) {
        drawerOverlay = new FrameLayout(context);
        drawerOverlay.setVisibility(View.GONE);
        root.addView(drawerOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        scrim = new View(context);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> closeDrawer());
        drawerOverlay.addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        drawerPanel = new LinearLayout(context);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setBackgroundColor(cSurface);
        drawerPanel.setClickable(true);
        drawerOverlay.addView(drawerPanel, LayoutHelper.createFrame(300, LayoutHelper.MATCH_PARENT, Gravity.LEFT));

        TextView title = new TextView(context);
        title.setText("Suhbatlar");
        title.setTextSize(22);
        title.setTypeface(Typeface.SERIF);
        title.setTextColor(cText);
        title.setPadding(dp(20), dp(18), dp(20), dp(10));
        drawerPanel.addView(title);

        TextView create = new TextView(context);
        create.setText("+  Yangi suhbat");
        create.setTextSize(15);
        create.setTypeface(AndroidUtilities.bold());
        create.setTextColor(0xFFFFFFFF);
        create.setGravity(Gravity.CENTER);
        create.setPadding(dp(16), dp(12), dp(16), dp(12));
        create.setBackground(rounded(cAccent, 22, 0));
        create.setOnClickListener(v -> newSession());
        drawerPanel.addView(create, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 10));

        sessionList = new RecyclerListView(context);
        sessionList.setLayoutManager(new LinearLayoutManager(context));
        sessionList.setAdapter(sessionAdapter = new SessionAdapter(context));
        sessionList.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < topics.size()) {
                openSession(topics.get(position).id);
            }
        });
        sessionList.setOnItemLongClickListener((view, position) -> {
            if (position >= 0 && position < topics.size()) {
                confirmDelete(topics.get(position));
                return true;
            }
            return false;
        });
        drawerPanel.addView(sessionList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(context);
        hint.setText("Suhbatni o'chirish: bosib turing");
        hint.setTextSize(12);
        hint.setTextColor(cMuted);
        hint.setPadding(dp(20), dp(10), dp(20), dp(14));
        drawerPanel.addView(hint);
    }

    private void openDrawer() {
        if (drawerOpen || drawerOverlay == null) {
            return;
        }
        drawerOpen = true;
        if (botForum) {
            MessagesController.getInstance(currentAccount).getTopicsController().loadTopics(-botId);
        }
        reloadTopics();
        drawerOverlay.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        drawerPanel.setTranslationX(-dp(300));
        drawerPanel.animate().translationX(0).setDuration(200).start();
    }

    private void closeDrawer() {
        if (!drawerOpen || drawerOverlay == null) {
            return;
        }
        drawerOpen = false;
        scrim.animate().alpha(0f).setDuration(160).start();
        drawerPanel.animate().translationX(-dp(300)).setDuration(180).withEndAction(() -> {
            if (!drawerOpen) {
                drawerOverlay.setVisibility(View.GONE);
            }
        }).start();
    }

    private void confirmDelete(final TLRPC.TL_forumTopic topic) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Suhbatni o'chirish");
        builder.setMessage("\"" + (TextUtils.isEmpty(topic.title) ? "Suhbat" : topic.title) + "\" suhbati va uning xabarlari o'chiriladi.");
        builder.setPositiveButton("O'chirish", (d, w) -> {
            ArrayList<Integer> ids = new ArrayList<>();
            ids.add(topic.id);
            MessagesController.getInstance(currentAccount).getTopicsController().deleteTopics(-botId, ids);
            if (topic.id == topicId) {
                newSession();
            }
            reloadTopics();
        });
        builder.setNegativeButton("Bekor qilish", null);
        showDialog(builder.create());
    }

    private class SessionAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        SessionAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return topics.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(20), dp(10), dp(20), dp(10));
            TextView name = new TextView(context);
            name.setTag("name");
            name.setTextSize(15);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView when = new TextView(context);
            when.setTag("when");
            when.setTextSize(12);
            when.setTextColor(cMuted);
            row.addView(name);
            row.addView(when);
            row.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(row);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TLRPC.TL_forumTopic topic = topics.get(position);
            boolean selected = topic.id == topicId;
            TextView name = holder.itemView.findViewWithTag("name");
            TextView when = holder.itemView.findViewWithTag("when");
            name.setText(TextUtils.isEmpty(topic.title) ? "Suhbat" : topic.title);
            name.setTextColor(selected ? cAccent : cText);
            name.setTypeface(selected ? AndroidUtilities.bold() : Typeface.DEFAULT);
            when.setText(dateFormat.format(new Date(topic.date * 1000L)));
            holder.itemView.setBackgroundColor(selected ? (dark ? 0xFF3B3A37 : 0xFFF5F2EA) : 0x00000000);
        }
    }
}
