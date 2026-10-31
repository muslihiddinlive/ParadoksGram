package uz.paradoks.gram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Interactive chat counters screen. Shows totals per category; tapping a category
 * opens a list of those chats; tapping a chat opens it. Unread messages has a bar chart.
 */
public class ParadoksCountersActivity extends BaseFragment {

    // Sections
    private static final int S_HEADER_TOTALS = 0;
    private static final int S_PEOPLE = 1;
    private static final int S_BOTS = 2;
    private static final int S_GROUPS = 3;
    private static final int S_CHANNELS = 4;
    private static final int S_UNREAD_CHATS = 5;
    private static final int S_UNREAD_MSG = 6;
    private static final int S_MUTED = 7;

    private static final int T_SECTION = 0;
    private static final int T_ROW = 1;
    private static final int T_CHART_HEADER = 2;
    private static final int T_CHART_BAR = 3;

    private RecyclerListView listView;
    private Adapter adapter;
    private List<Item> items;
    private int currentSection = -1; // -1 = totals overview
    private int account;

    // Data
    private List<TLRPC.Dialog> allDialogs;
    private List<TLRPC.Dialog> people, bots, groups, channels, unreadChats, mutedChats;
    private int totalUnreadMessages;

    private static class Item {
        int type;
        String title, subtitle;
        int section;
        int icon;
        TLRPC.Dialog dialog;
        // chart bar
        String barLabel;
        int barValue, barMax;
    }

    @Override
    public View createView(Context context) {
        account = UserConfig.selectedAccount;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Chat hisoblagichlari");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (currentSection != -1) {
                        currentSection = -1;
                        actionBar.setTitle("Chat hisoblagichlari");
                        buildItems();
                        adapter.notifyDataSetChanged();
                    } else {
                        finishFragment();
                    }
                }
            }
        });

        collectData();

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new Adapter());
        listView.setOnItemClickListener((view, position) -> onItemClick(items.get(position)));
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        buildItems();
        fragmentView = frame;
        return frame;
    }

    private void collectData() {
        MessagesController mc = MessagesController.getInstance(account);
        allDialogs = new ArrayList<>(mc.getAllDialogs());
        people = new ArrayList<>(); bots = new ArrayList<>();
        groups = new ArrayList<>(); channels = new ArrayList<>();
        unreadChats = new ArrayList<>(); mutedChats = new ArrayList<>();
        totalUnreadMessages = 0;

        for (TLRPC.Dialog d : allDialogs) {
            long id = d.id;
            boolean isUser = DialogObject.isUserDialog(id);
            boolean isChat = DialogObject.isChatDialog(id);
            if (isUser) {
                TLRPC.User u = mc.getUser(id);
                if (u != null && u.bot) bots.add(d); else people.add(d);
            } else if (isChat) {
                TLRPC.Chat c = mc.getChat(-id);
                if (c == null) continue;
                if (ChatObject.isChannelAndNotMegaGroup(c)) channels.add(d); else groups.add(d);
            }
            if (d.unread_count > 0) {
                unreadChats.add(d);
                totalUnreadMessages += d.unread_count;
            }
            if (mc.isDialogMuted(id, 0)) mutedChats.add(d);
        }
        // sort unread by count desc
        unreadChats.sort((a, b) -> b.unread_count - a.unread_count);
    }

    private void buildItems() {
        items = new ArrayList<>();
        if (currentSection == -1) buildOverview();
        else buildSection(currentSection);
    }

    private Item section(String title) {
        Item i = new Item(); i.type = T_SECTION; i.title = title; return i;
    }

    private Item row(String title, String subtitle, int section) {
        return row(title, subtitle, section, 0);
    }
    private Item row(String title, String subtitle, int section, int icon) {
        Item i = new Item(); i.type = T_ROW; i.title = title; i.subtitle = subtitle; i.section = section; i.icon = icon; return i;
    }

    private Item dialogRow(TLRPC.Dialog d) {
        Item i = new Item(); i.type = T_ROW; i.dialog = d;
        MessagesController mc = MessagesController.getInstance(account);
        long id = d.id;
        if (DialogObject.isUserDialog(id)) {
            TLRPC.User u = mc.getUser(id);
            i.title = u != null ? (u.first_name + " " + (u.last_name != null ? u.last_name : "")).trim() : String.valueOf(id);
        } else {
            TLRPC.Chat c = mc.getChat(-id);
            i.title = c != null ? c.title : String.valueOf(id);
        }
        i.subtitle = d.unread_count > 0 ? d.unread_count + " o'qilmagan" : "";
        return i;
    }

    private void buildOverview() {
        items.add(section("Chatlar"));
        items.add(row("Shaxsiy chatlar", String.valueOf(people.size()), S_PEOPLE, R.drawable.msg_folders_private));
        items.add(row("Botlar", String.valueOf(bots.size()), S_BOTS, R.drawable.msg_folders_bots));
        items.add(row("Guruhlar", String.valueOf(groups.size()), S_GROUPS, R.drawable.msg_folders_groups));
        items.add(row("Kanallar", String.valueOf(channels.size()), S_CHANNELS, R.drawable.msg_folders_channels));
        items.add(section("Xabarlar"));
        items.add(row("O'qilmagan chatlar", String.valueOf(unreadChats.size()), S_UNREAD_CHATS, R.drawable.msg_markread));
        items.add(row("O'qilmagan xabarlar", String.valueOf(totalUnreadMessages) + " ta", S_UNREAD_MSG, R.drawable.msg_stats));
        items.add(row("Ovozi o'chirilgan", String.valueOf(mutedChats.size()), S_MUTED, R.drawable.msg_folders_muted));
    }

    private void buildSection(int section) {
        List<TLRPC.Dialog> list = listForSection(section);
        String title = titleForSection(section);
        if (section == S_UNREAD_MSG) {
            buildUnreadChart();
            return;
        }
        items.add(section(title + " — " + list.size() + " ta"));
        for (TLRPC.Dialog d : list) items.add(dialogRow(d));
    }

    private void buildUnreadChart() {
        List<TLRPC.Dialog> top = new ArrayList<>(unreadChats);
        if (top.size() > 20) top = top.subList(0, 20);
        int max = top.isEmpty() ? 1 : top.get(0).unread_count;

        Item h = new Item(); h.type = T_CHART_HEADER;
        h.title = "O'qilmagan xabarlar taqsimoti";
        h.subtitle = "Jami: " + totalUnreadMessages + " ta, " + unreadChats.size() + " chatda";
        items.add(h);

        MessagesController mc = MessagesController.getInstance(account);
        for (TLRPC.Dialog d : top) {
            Item bar = new Item(); bar.type = T_CHART_BAR; bar.dialog = d;
            long id = d.id;
            if (DialogObject.isUserDialog(id)) {
                TLRPC.User u = mc.getUser(id);
                bar.barLabel = u != null ? (u.first_name + " " + (u.last_name != null ? u.last_name : "")).trim() : String.valueOf(id);
            } else {
                TLRPC.Chat c = mc.getChat(-id);
                bar.barLabel = c != null ? c.title : String.valueOf(id);
            }
            bar.barValue = d.unread_count;
            bar.barMax = max;
            items.add(bar);
        }
        if (unreadChats.size() > 20) {
            Item more = new Item(); more.type = T_SECTION;
            more.title = "... va yana " + (unreadChats.size() - 20) + " ta chat";
            items.add(more);
        }
    }

    private List<TLRPC.Dialog> listForSection(int section) {
        switch (section) {
            case S_PEOPLE: return people;
            case S_BOTS: return bots;
            case S_GROUPS: return groups;
            case S_CHANNELS: return channels;
            case S_UNREAD_CHATS: return unreadChats;
            case S_MUTED: return mutedChats;
            default: return new ArrayList<>();
        }
    }

    private String titleForSection(int section) {
        switch (section) {
            case S_PEOPLE: return "Shaxsiy chatlar";
            case S_BOTS: return "Botlar";
            case S_GROUPS: return "Guruhlar";
            case S_CHANNELS: return "Kanallar";
            case S_UNREAD_CHATS: return "O'qilmagan chatlar";
            case S_MUTED: return "Ovozi o'chirilgan";
            default: return "";
        }
    }

    private void onItemClick(Item item) {
        if (item.type == T_SECTION) return;
        if (item.dialog == null) {
            // overview row — drill down
            if (item.section == S_UNREAD_MSG) {
                currentSection = S_UNREAD_MSG;
                actionBar.setTitle("O'qilmagan xabarlar");
            } else {
                currentSection = item.section;
                actionBar.setTitle(titleForSection(item.section));
            }
            buildItems();
            adapter.notifyDataSetChanged();
            listView.scrollToPosition(0);
            return;
        }
        // open chat
        presentFragment(ChatActivity.of(item.dialog.id));
    }

    // ---- Adapter ----

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return items == null ? 0 : items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            Item item = items.get(holder.getAdapterPosition());
            return item.type == T_ROW || item.type == T_CHART_BAR;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            View v;
            if (viewType == T_SECTION) {
                TextView tv = new TextView(ctx);
                tv.setTextSize(12);
                tv.setTypeface(AndroidUtilities.bold());
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
                tv.setPadding(dp(16), dp(14), dp(16), dp(4));
                tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                v = tv;
            } else if (viewType == T_CHART_HEADER) {
                LinearLayout ll = new LinearLayout(ctx);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                ll.setPadding(dp(16), dp(14), dp(16), dp(8));
                TextView t1 = new TextView(ctx); t1.setTag("t1");
                t1.setTextSize(15); t1.setTypeface(AndroidUtilities.bold());
                t1.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                TextView t2 = new TextView(ctx); t2.setTag("t2");
                t2.setTextSize(13);
                t2.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
                t2.setPadding(0, dp(4), 0, 0);
                ll.addView(t1); ll.addView(t2);
                ll.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                v = ll;
            } else if (viewType == T_CHART_BAR) {
                v = new BarView(ctx);
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            } else {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                android.widget.ImageView icon = new android.widget.ImageView(ctx);
                icon.setTag("icon");
                icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                icon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), android.graphics.PorterDuff.Mode.SRC_IN);
                row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
                LinearLayout texts = new LinearLayout(ctx);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(12), 0, 0, 0);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                texts.setLayoutParams(tp);
                TextView t1 = new TextView(ctx); t1.setTag("t1");
                t1.setTextSize(16);
                t1.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                TextView t2 = new TextView(ctx); t2.setTag("t2");
                t2.setTextSize(13);
                t2.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
                texts.addView(t1); texts.addView(t2);
                row.addView(texts);
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                v = row;
            }
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Item item = items.get(position);
            View v = holder.itemView;
            if (item.type == T_SECTION) {
                ((TextView) v).setText(item.title);
            } else if (item.type == T_CHART_HEADER) {
                ((TextView) ((ViewGroup) v).findViewWithTag("t1")).setText(item.title);
                ((TextView) ((ViewGroup) v).findViewWithTag("t2")).setText(item.subtitle);
            } else if (item.type == T_CHART_BAR) {
                ((BarView) v).bind(item.barLabel, item.barValue, item.barMax);
            } else {
                TextView t1 = v.findViewWithTag("t1");
                TextView t2 = v.findViewWithTag("t2");
                t1.setText(item.title);
                if (item.subtitle != null && !item.subtitle.isEmpty()) {
                    t2.setText(item.subtitle);
                    t2.setVisibility(View.VISIBLE);
                } else {
                    t2.setVisibility(View.GONE);
                }
                // Overview rows: subtitle is the count, shown as badge
                android.widget.ImageView ico = v.findViewWithTag("icon");
                if (ico != null) {
                    if (item.icon != 0) {
                        ico.setImageResource(item.icon);
                        ico.setVisibility(View.VISIBLE);
                    } else {
                        ico.setImageDrawable(null);
                        ico.setVisibility(View.GONE);
                    }
                }
                if (item.dialog == null && item.subtitle != null) {
                    SpannableStringBuilder ssb = new SpannableStringBuilder(item.title + "   " + item.subtitle);
                    ssb.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_chats_unreadCounter)),
                            item.title.length() + 3, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    t1.setText(ssb);
                    t2.setVisibility(View.GONE);
                }
            }
        }
    }

    /** Simple horizontal bar row for the unread chart. */
    private static class BarView extends View {
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String label = "";
        private int value, max;

        BarView(Context ctx) {
            super(ctx);
            barPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
            bgPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            textPaint.setTextSize(AndroidUtilities.dp(12));
            textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }

        void bind(String label, int value, int max) {
            this.label = label; this.value = value; this.max = max; invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            int pad = AndroidUtilities.dp(16);
            int barH = AndroidUtilities.dp(12);
            int barY = (h - barH) / 2;
            String valStr = String.valueOf(value);
            float valW = textPaint.measureText(valStr) + AndroidUtilities.dp(6);
            float labelW = textPaint.measureText(label);
            float maxLabelW = AndroidUtilities.dp(120);
            float lw = Math.min(labelW, maxLabelW);
            float barX = pad + lw + AndroidUtilities.dp(8);
            float barW = w - barX - valW - pad;
            float fill = max > 0 ? barW * value / (float) max : 0;
            RectF bg = new RectF(barX, barY, barX + barW, barY + barH);
            RectF bar = new RectF(barX, barY, barX + fill, barY + barH);
            c.drawRoundRect(bg, barH / 2f, barH / 2f, bgPaint);
            bgPaint.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            c.drawRoundRect(bg, barH / 2f, barH / 2f, bgPaint);
            bgPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            if (fill > 0) c.drawRoundRect(bar, barH / 2f, barH / 2f, barPaint);
            float ty = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            c.drawText(label, 0, Math.min(label.length(), 18), pad, ty, textPaint);
            c.drawText(valStr, w - pad - textPaint.measureText(valStr), ty, textPaint);
        }
    }
}
