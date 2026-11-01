package uz.paradoks.gram;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Xabar xususiyatlari": collects every piece of metadata we can read from a message
 * (grouped in sections, the most important ones first) and dumps the raw TL object as JSON.
 */
public final class ParadoksInspector {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_OPEN_SENDER = 1;
    public static final int ACTION_RAW_JSON = 2;

    public static final int EMPH_NONE = 0;
    public static final int EMPH_WARN = 1; // qizil
    public static final int EMPH_INFO = 2; // ko'k
    public static final int EMPH_OK = 3;   // yashil

    public static final class Row {
        public final String label;
        public final String value;
        public final int action;
        public final long actionId;
        public final int emphasis;

        Row(String label, String value, int action, long actionId, int emphasis) {
            this.label = label;
            this.value = value;
            this.action = action;
            this.actionId = actionId;
            this.emphasis = emphasis;
        }
    }

    public static final class Section {
        public final String title;
        public final ArrayList<Row> rows = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }

        void add(String label, String value) {
            if (value != null) {
                rows.add(new Row(label, value, ACTION_NONE, 0, EMPH_NONE));
            }
        }

        void addEmph(String label, String value, int emphasis) {
            rows.add(new Row(label, value, ACTION_NONE, 0, emphasis));
        }

        void addAction(String label, String value, int action, long actionId) {
            rows.add(new Row(label, value, action, actionId, EMPH_NONE));
        }
    }

    private ParadoksInspector() {
    }

    private static String yn(boolean value) {
        return value ? "Ha" : "Yo'q";
    }

    private static String formatDate(int unix) {
        if (unix <= 0) {
            return "—";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss (zzz)", Locale.getDefault()).format(new Date(unix * 1000L));
    }

    private static String ago(int unix) {
        long diff = System.currentTimeMillis() / 1000L - unix;
        if (unix <= 0) {
            return "—";
        }
        if (diff < 0) {
            return "kelajakda";
        }
        long days = diff / 86400;
        long hours = (diff % 86400) / 3600;
        long minutes = (diff % 3600) / 60;
        long seconds = diff % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" kun ");
        }
        if (hours > 0) {
            sb.append(hours).append(" soat ");
        }
        if (days == 0 && minutes > 0) {
            sb.append(minutes).append(" daqiqa ");
        }
        if (days == 0 && hours == 0 && minutes == 0) {
            sb.append(seconds).append(" soniya ");
        }
        return sb.append("oldin").toString();
    }

    private static String duration(int seconds) {
        if (seconds <= 0) {
            return "—";
        }
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append(" kun ");
        }
        if (h > 0) {
            sb.append(h).append(" soat ");
        }
        if (m > 0) {
            sb.append(m).append(" daqiqa ");
        }
        if (s > 0 || sb.length() == 0) {
            sb.append(s).append(" soniya");
        }
        return sb.toString().trim() + " (" + seconds + " s)";
    }

    static String peerName(int account, TLRPC.Peer peer) {
        if (peer == null) {
            return null;
        }
        MessagesController mc = MessagesController.getInstance(account);
        if (peer.user_id != 0) {
            TLRPC.User user = mc.getUser(peer.user_id);
            return user != null ? UserObject.getUserName(user) : "user " + peer.user_id;
        }
        long chatId = peer.channel_id != 0 ? peer.channel_id : peer.chat_id;
        if (chatId != 0) {
            TLRPC.Chat chat = mc.getChat(chatId);
            return chat != null && chat.title != null ? chat.title : "chat " + chatId;
        }
        return null;
    }

    private static String peerWithId(int account, TLRPC.Peer peer) {
        if (peer == null) {
            return null;
        }
        String name = peerName(account, peer);
        long id = DialogObject.getPeerDialogId(peer);
        return (name != null ? name + "\n" : "") + id;
    }

    private static String simpleName(Object o) {
        if (o == null) {
            return "—";
        }
        String name = o.getClass().getSimpleName();
        if (name.startsWith("TL_")) {
            name = name.substring(3);
        }
        return name;
    }

    private static String entityName(TLRPC.MessageEntity entity) {
        String name = entity.getClass().getSimpleName();
        if (name.startsWith("TL_messageEntity")) {
            name = name.substring("TL_messageEntity".length());
        }
        return name;
    }

    private static String chatType(int account, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return "Maxfiy chat";
        }
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            return user != null && user.bot ? "Bot bilan chat" : "Shaxsiy chat";
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat == null) {
            return "Guruh/kanal";
        }
        if (ChatObject.isChannel(chat)) {
            return chat.megagroup ? "Superguruh" : "Kanal";
        }
        return "Guruh";
    }

    private static String messageLink(int account, MessageObject mo) {
        long dialogId = mo.getDialogId();
        if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            if (chat != null && ChatObject.isChannel(chat)) {
                String username = ChatObject.getPublicUsername(chat);
                if (!TextUtils.isEmpty(username)) {
                    return "https://t.me/" + username + "/" + mo.getId();
                }
                return "https://t.me/c/" + chat.id + "/" + mo.getId();
            }
        }
        return null;
    }

    private static String mediaTypeName(MessageObject mo, TLRPC.Message m) {
        if (m.media == null || m.media instanceof TLRPC.TL_messageMediaEmpty) {
            return "Matn";
        }
        return simpleName(m.media).replace("messageMedia", "");
    }

    static String reactionName(TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            return ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            return "custom:" + ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
        }
        if (reaction instanceof TLRPC.TL_reactionPaid) {
            return "⭐";
        }
        return simpleName(reaction);
    }

    /** Short form: "3 ta reaksiya: ❤ 2  👍 1" (null when the message has no reactions). */
    static String reactionSummary(TLRPC.Message m) {
        if (m == null || m.reactions == null || m.reactions.results == null || m.reactions.results.isEmpty()) {
            return null;
        }
        int total = 0;
        StringBuilder sb = new StringBuilder();
        for (TLRPC.ReactionCount rc : m.reactions.results) {
            total += rc.count;
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(reactionName(rc.reaction)).append(" ").append(rc.count);
        }
        return total + " ta reaksiya: " + sb;
    }

    /** Long form: counts per reaction plus the people Telegram lets us see (recent_reactions). */
    static String reactionDetails(int account, TLRPC.Message m) {
        String summary = reactionSummary(m);
        if (summary == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(summary);
        if (m.reactions.recent_reactions != null && !m.reactions.recent_reactions.isEmpty()) {
            sb.append("\n\nKim bosgan:");
            for (TLRPC.MessagePeerReaction r : m.reactions.recent_reactions) {
                String name = peerName(account, r.peer_id);
                sb.append("\n• ").append(name != null ? name : "—").append("  ").append(reactionName(r.reaction));
                if (r.date > 0) {
                    sb.append("  (").append(formatDate(r.date)).append(")");
                }
            }
        } else {
            sb.append("\n\nKim bosgani ko'rinmayapti (Telegram bu guruh yoki kanalda ro'yxatni bermaydi).");
        }
        return sb.toString();
    }

    public static ArrayList<Section> build(int account, MessageObject mo, MessageObject.GroupedMessages group) {
        ArrayList<Section> out = new ArrayList<>();
        if (mo == null || mo.messageOwner == null) {
            return out;
        }
        final TLRPC.Message m = mo.messageOwner;
        final MessagesController mc = MessagesController.getInstance(account);
        final long dialogId = mo.getDialogId();
        final TLRPC.Peer fromPeer = mo.getFromPeer();
        final long fromId = fromPeer != null ? DialogObject.getPeerDialogId(fromPeer) : 0;
        final TLRPC.User sender = fromPeer != null && fromPeer.user_id != 0 ? mc.getUser(fromPeer.user_id) : null;

        // ---------- Holat: tahrir soni va o'chirilganmi (eng tepada, ko'zga tashlanadi) ----------
        Section status = new Section("Holat");
        final int editCount = ParadoksEdits.countHistory(account, m);
        StringBuilder editText = new StringBuilder();
        int editEmphasis;
        if (editCount > 0) {
            editText.append(editCount).append(" marta tahrirlangan");
            if (m.edit_date != 0) {
                editText.append("\nOxirgisi: ").append(formatDate(m.edit_date));
            }
            editEmphasis = EMPH_INFO;
        } else if (m.edit_date != 0) {
            editText.append("Kamida 1 marta tahrirlangan\n(oldingi matnlari saqlanmagan)");
            editEmphasis = EMPH_INFO;
        } else {
            editText.append("Tahrirlanmagan");
            editEmphasis = EMPH_OK;
        }
        if (!ParadoksConfig.trackEdits()) {
            editText.append("\n(tahrirlarni kuzatish o'chiq)");
        }
        status.addEmph("Tahrirlar", editText.toString(), editEmphasis);
        final boolean wasDeleted = ParadoksDeleted.isDeleted(account, mo);
        StringBuilder deletedText = new StringBuilder();
        if (wasDeleted) {
            deletedText.append("Ha, o'chirilgan");
            final long deletedAt = ParadoksDeleted.deletedAt(account, mo);
            if (deletedAt > 0) {
                deletedText.append("\n").append(formatDate((int) deletedAt)).append(" (").append(ago((int) deletedAt)).append(")");
            }
        } else {
            deletedText.append("Yo'q");
            if (!ParadoksConfig.keepDeleted()) {
                deletedText.append("\n(o'chirilganlarni saqlash o'chiq, shuning uchun kuzatilmaydi)");
            }
        }
        status.addEmph("O'chirilgan", deletedText.toString(), wasDeleted ? EMPH_WARN : EMPH_OK);
        out.add(status);

        // ---------- Asosiy (eng muhimlari tepada) ----------
        Section main = new Section("Asosiy");
        String text = !TextUtils.isEmpty(m.message) ? m.message : null;
        main.add("Xabar", text != null ? text : "(matn yo'q — " + mediaTypeName(mo, m) + ")");
        main.add("Id", String.valueOf(m.id));
        StringBuilder from = new StringBuilder();
        String fromName = peerName(account, fromPeer);
        if (fromName != null) {
            from.append(fromName);
        }
        if (sender != null) {
            String username = UserObject.getPublicUsername(sender);
            if (!TextUtils.isEmpty(username)) {
                from.append("\n@").append(username);
            }
        }
        if (fromPeer != null) {
            from.append("\n").append(fromId);
        }
        main.add("Kimdan", from.length() > 0 ? from.toString() : "—");
        main.add("Chat", (peerName(account, m.peer_id) != null ? peerName(account, m.peer_id) + "\n" : "") + dialogId + "\n" + chatType(account, dialogId));
        main.add("Sana", formatDate(m.date));
        main.add("O'zgartirildi", m.edit_date != 0 ? formatDate(m.edit_date) : "Tahrirlanmagan");
        String reactionInfo = reactionDetails(account, m);
        if (reactionInfo != null) {
            main.add("Reaksiyalar", reactionInfo);
        }
        String link = messageLink(account, mo);
        if (link != null) {
            main.add("Havola", link);
        }
        out.add(main);

        // ---------- Yuboruvchi ----------
        Section who = new Section("Yuboruvchi");
        if (sender != null) {
            who.add("Ism", UserObject.getUserName(sender));
            who.add("ID", String.valueOf(sender.id));
            StringBuilder names = new StringBuilder();
            if (sender.usernames != null) {
                for (TLRPC.TL_username u : sender.usernames) {
                    if (u != null && u.active && !TextUtils.isEmpty(u.username)) {
                        if (names.length() > 0) {
                            names.append("\n");
                        }
                        names.append("@").append(u.username);
                    }
                }
            }
            if (names.length() == 0 && !TextUtils.isEmpty(sender.username)) {
                names.append("@").append(sender.username);
            }
            who.add("Username", names.length() > 0 ? names.toString() : "yo'q");
            if (!TextUtils.isEmpty(sender.phone)) {
                who.add("Telefon", "+" + sender.phone);
            }
            ArrayList<String> kinds = new ArrayList<>();
            if (sender.bot) kinds.add("Bot");
            if (sender.premium) kinds.add("Premium");
            if (sender.verified) kinds.add("Verified");
            if (sender.scam) kinds.add("Scam");
            if (sender.fake) kinds.add("Fake");
            if (sender.deleted) kinds.add("O'chirilgan");
            if (sender.contact) kinds.add("Kontakt");
            if (sender.support) kinds.add("Support");
            if (sender.restricted) kinds.add("Cheklangan");
            who.add("Belgilar", kinds.isEmpty() ? "yo'q" : TextUtils.join(", ", kinds));
            if (sender.photo != null) {
                who.add("Profil rasmi DC", String.valueOf(sender.photo.dc_id));
            }
            who.addAction("Profilni ochish", UserObject.getUserName(sender), ACTION_OPEN_SENDER, sender.id);
        } else if (fromPeer != null) {
            who.add("Nomi", fromName != null ? fromName : "—");
            who.add("ID", String.valueOf(fromId));
            if (fromPeer.channel_id != 0 || fromPeer.chat_id != 0) {
                TLRPC.Chat chat = mc.getChat(fromPeer.channel_id != 0 ? fromPeer.channel_id : fromPeer.chat_id);
                if (chat != null) {
                    String username = ChatObject.getPublicUsername(chat);
                    who.add("Username", !TextUtils.isEmpty(username) ? "@" + username : "yo'q");
                    who.add("Turi", chat.megagroup ? "Superguruh" : (ChatObject.isChannel(chat) ? "Kanal" : "Guruh"));
                }
            }
        }
        if (!TextUtils.isEmpty(m.from_rank)) {
            who.add("Guruhdagi unvon", m.from_rank);
        }
        if (!TextUtils.isEmpty(m.post_author)) {
            who.add("Imzo (post_author)", m.post_author);
        }
        if (m.via_bot_id != 0) {
            TLRPC.User bot = mc.getUser(m.via_bot_id);
            who.add("Via bot", (bot != null ? UserObject.getUserName(bot) + " " : "") + m.via_bot_id);
        } else if (!TextUtils.isEmpty(m.via_bot_name)) {
            who.add("Via bot", "@" + m.via_bot_name);
        }
        if (!who.rows.isEmpty()) {
            out.add(who);
        }

        // ---------- Xabar tafsilotlari ----------
        Section det = new Section("Xabar tafsilotlari");
        det.add("Media turi", mediaTypeName(mo, m));
        int textLength = m.message != null ? m.message.length() : 0;
        det.add("Matn uzunligi", String.valueOf(textLength));
        det.add("UTF-8 hajmi", (m.message != null ? m.message.getBytes(StandardCharsets.UTF_8).length : 0) + " bayt");
        int entityCount = m.entities != null ? m.entities.size() : 0;
        det.add("Entity'lar", String.valueOf(entityCount));
        if (entityCount > 0) {
            LinkedHashMap<String, Integer> kinds = new LinkedHashMap<>();
            ArrayList<String> links = new ArrayList<>();
            for (TLRPC.MessageEntity entity : m.entities) {
                String name = entityName(entity);
                kinds.put(name, kinds.containsKey(name) ? kinds.get(name) + 1 : 1);
                if (entity instanceof TLRPC.TL_messageEntityTextUrl && !TextUtils.isEmpty(entity.url)) {
                    links.add(entity.url);
                } else if (entity instanceof TLRPC.TL_messageEntityUrl && m.message != null
                        && entity.offset >= 0 && entity.length > 0 && entity.offset + entity.length <= m.message.length()) {
                    links.add(m.message.substring(entity.offset, entity.offset + entity.length));
                }
            }
            StringBuilder kindText = new StringBuilder();
            for (Map.Entry<String, Integer> e : kinds.entrySet()) {
                if (kindText.length() > 0) {
                    kindText.append(", ");
                }
                kindText.append(e.getKey()).append(": ").append(e.getValue());
            }
            det.add("Entity turlari", kindText.toString());
            if (!links.isEmpty()) {
                det.add("Linklar (" + links.size() + ")", TextUtils.join("\n", links));
            }
        }
        TLRPC.Document document = mo.getDocument();
        if (document != null) {
            det.add("Fayl ID", String.valueOf(document.id));
            if (document.mime_type != null) {
                det.add("Fayl formati", document.mime_type);
            }
            if (document.size > 0) {
                det.add("Fayl hajmi", AndroidUtilities.formatFileSize(document.size) + " (" + document.size + " bayt)");
            }
            det.add("Fayl DC", String.valueOf(document.dc_id));
            if (document.attributes != null) {
                for (TLRPC.DocumentAttribute attribute : document.attributes) {
                    if (!TextUtils.isEmpty(attribute.file_name)) {
                        det.add("Fayl nomi", attribute.file_name);
                    }
                    if (attribute.duration > 0) {
                        det.add("Davomiyligi", String.format(Locale.US, "%.1f s", attribute.duration));
                    }
                    if (attribute.w > 0 && attribute.h > 0) {
                        det.add("O'lchami", attribute.w + "×" + attribute.h);
                    }
                    if (!TextUtils.isEmpty(attribute.alt)) {
                        det.add("Emoji (alt)", attribute.alt);
                    }
                    if (attribute.stickerset != null && !TextUtils.isEmpty(attribute.stickerset.short_name)) {
                        det.add("Stiker to'plami", attribute.stickerset.short_name);
                    }
                }
            }
        } else if (m.media != null && m.media.photo != null) {
            det.add("Rasm ID", String.valueOf(m.media.photo.id));
            det.add("Rasm DC", String.valueOf(m.media.photo.dc_id));
            if (m.media.photo.sizes != null) {
                det.add("Rasm o'lchamlari soni", String.valueOf(m.media.photo.sizes.size()));
            }
        }
        if (m.media != null && m.media.webpage != null && !TextUtils.isEmpty(m.media.webpage.url)) {
            det.add("Web sahifa", m.media.webpage.url);
        }
        if (m.grouped_id != 0) {
            det.add("Albom ID", String.valueOf(m.grouped_id));
            if (group != null && group.messages != null) {
                det.add("Albomdagi xabarlar", String.valueOf(group.messages.size()));
            }
        }
        det.add("Ichki tur (type)", String.valueOf(mo.type));
        out.add(det);

        // ---------- Vaqt ----------
        Section time = new Section("Vaqt");
        time.add("Yuborilgan", formatDate(m.date));
        time.add("Unix (yuborilgan)", String.valueOf(m.date));
        time.add("Necha vaqt oldin", ago(m.date));
        if (m.edit_date != 0) {
            time.add("Tahrirlangan", formatDate(m.edit_date));
            time.add("Unix (tahrir)", String.valueOf(m.edit_date));
        } else {
            time.add("Tahrirlangan", "Yo'q");
        }
        if (m.fwd_from != null) {
            time.add("Forward sanasi", formatDate(m.fwd_from.date));
        } else {
            time.add("Forward sanasi", "N/A");
        }
        if (m.ttl_period != 0) {
            time.add("Avto-o'chirish (TTL)", duration(m.ttl_period));
        }
        if (m.expire_date != 0) {
            time.add("Amal qilish muddati", formatDate(m.expire_date));
        }
        if (ParadoksEdits.hasHistory(account, m)) {
            time.add("Tahrirlar tarixi", "Bor");
        }
        out.add(time);

        // ---------- Javob va forward ----------
        Section rel = new Section("Javob va forward");
        if (m.reply_to != null) {
            if (m.reply_to.reply_to_msg_id != 0) {
                rel.add("Javob (xabar ID)", String.valueOf(m.reply_to.reply_to_msg_id));
            }
            if (m.reply_to.reply_to_top_id != 0) {
                rel.add("Thread / topic ID", String.valueOf(m.reply_to.reply_to_top_id));
            }
            if (m.reply_to.reply_to_peer_id != null) {
                rel.add("Javob (boshqa chat)", peerWithId(account, m.reply_to.reply_to_peer_id));
            }
            if (m.reply_to.forum_topic) {
                rel.add("Forum topic", "Ha");
            }
            if (!TextUtils.isEmpty(m.reply_to.quote_text)) {
                rel.add("Iqtibos", m.reply_to.quote_text);
            }
        } else {
            rel.add("Javob", "Yo'q");
        }
        if (m.fwd_from != null) {
            TLRPC.MessageFwdHeader fwd = m.fwd_from;
            String fwdFrom = peerWithId(account, fwd.from_id);
            if (fwdFrom == null && !TextUtils.isEmpty(fwd.from_name)) {
                fwdFrom = fwd.from_name + "\n(yashirin profil)";
            }
            rel.add("Forward manbasi", fwdFrom != null ? fwdFrom : "—");
            if (fwd.channel_post != 0) {
                rel.add("Original post ID", String.valueOf(fwd.channel_post));
            }
            if (!TextUtils.isEmpty(fwd.post_author)) {
                rel.add("Original imzo", fwd.post_author);
            }
            if (fwd.saved_from_peer != null) {
                rel.add("Saqlangan joyi", peerWithId(account, fwd.saved_from_peer) + (fwd.saved_from_msg_id != 0 ? "\nxabar " + fwd.saved_from_msg_id : ""));
            }
            if (!TextUtils.isEmpty(fwd.psa_type)) {
                rel.add("PSA turi", fwd.psa_type);
            }
            if (fwd.imported) {
                rel.add("Import qilingan", "Ha");
            }
        }
        if (m.replies != null) {
            rel.add("Javoblar soni", String.valueOf(m.replies.replies));
            if (m.replies.comments && m.replies.channel_id != 0) {
                rel.add("Muhokama kanali ID", String.valueOf(m.replies.channel_id));
            }
        }
        if (!rel.rows.isEmpty()) {
            out.add(rel);
        }

        // ---------- Ko'rsatkichlar ----------
        Section stats = new Section("Ko'rsatkichlar");
        if (m.views > 0) {
            stats.add("Ko'rishlar", String.valueOf(m.views));
        }
        if (m.forwards > 0) {
            stats.add("Forwardlar", String.valueOf(m.forwards));
        }
        if (m.reactions != null && m.reactions.results != null && !m.reactions.results.isEmpty()) {
            stats.add("Kim bosganini ko'rish mumkin", yn(m.reactions.can_see_list));
        }
        if (m.paid_message_stars > 0) {
            stats.add("Pullik xabar (stars)", String.valueOf(m.paid_message_stars));
        }
        if (!stats.rows.isEmpty()) {
            out.add(stats);
        }

        // ---------- Flaglar ----------
        Section flags = new Section("Flaglar");
        flags.add("Tahrirlangan", yn(m.edit_date != 0));
        flags.add("Forward qilingan", yn(m.fwd_from != null));
        flags.add("Qadalgan", yn(m.pinned));
        flags.add("Himoyalangan (noforwards)", yn(m.noforwards));
        flags.add("Ovozsiz (silent)", yn(m.silent));
        flags.add("Chiquvchi (out)", yn(m.out));
        flags.add("O'qilmagan", yn(m.unread));
        flags.add("Mention qilingan", yn(m.mentioned));
        flags.add("Media o'qilmagan", yn(m.media_unread));
        flags.add("Kanal posti", yn(m.post));
        flags.add("Rejalashtirilgan", yn(m.from_scheduled));
        flags.add("Legacy", yn(m.legacy));
        flags.add("Tahrir yashirilgan", yn(m.edit_hide));
        flags.add("Media yuqorida (invert)", yn(m.invert_media));
        flags.add("Reaksiya mumkin", yn(m.reactions_are_possible));
        if (m.restriction_reason != null && !m.restriction_reason.isEmpty()) {
            StringBuilder reasons = new StringBuilder();
            for (TLRPC.RestrictionReason r : m.restriction_reason) {
                if (reasons.length() > 0) {
                    reasons.append("\n");
                }
                reasons.append(r.platform).append(": ").append(r.reason);
            }
            flags.add("Cheklov sababi", reasons.toString());
        }
        if (m.effect != 0) {
            flags.add("Effekt ID", String.valueOf(m.effect));
        }
        if (m.factcheck != null) {
            flags.add("Fact-check", "Bor");
        }
        out.add(flags);

        // ---------- Texnik ----------
        Section tech = new Section("Texnik");
        tech.add("TL klass", m.getClass().getSimpleName());
        tech.add("flags", "0x" + Integer.toHexString(m.flags) + "  (" + m.flags + ")");
        tech.add("flags2", "0x" + Integer.toHexString(m.flags2) + "  (" + m.flags2 + ")");
        tech.add("Dialog ID", String.valueOf(dialogId));
        tech.add("Yuborish holati (send_state)", String.valueOf(m.send_state));
        if (m.random_id != 0) {
            tech.add("random_id", String.valueOf(m.random_id));
        }
        if (m.local_id != 0) {
            tech.add("local_id", String.valueOf(m.local_id));
        }
        if (m.layer != 0) {
            tech.add("Layer", String.valueOf(m.layer));
        }
        if (!TextUtils.isEmpty(m.attachPath)) {
            tech.add("Lokal fayl yo'li", m.attachPath);
        }
        out.add(tech);

        // ---------- Xom obyekt ----------
        Section raw = new Section("Xom ma'lumot");
        raw.addAction("{ }  Xom Telegram obyekti", "JSON ko'rinishida ochish", ACTION_RAW_JSON, 0);
        out.add(raw);
        return out;
    }

    public static String toText(List<Section> sections) {
        StringBuilder sb = new StringBuilder();
        for (Section section : sections) {
            if (section.rows.size() == 1 && section.rows.get(0).action == ACTION_RAW_JSON) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("== ").append(section.title).append(" ==\n");
            for (Row row : section.rows) {
                if (row.action != ACTION_NONE) {
                    continue;
                }
                sb.append(row.label).append(": ").append(row.value.replace("\n", " | ")).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ JSON

    private static final int MAX_DEPTH = 8;
    private static final int MAX_LENGTH = 120_000;
    private static final List<String> FIRST_FIELDS = Arrays.asList("id", "message", "date", "edit_date", "from_id", "peer_id");

    public static String toJson(Object root) {
        StringBuilder sb = new StringBuilder();
        try {
            dump(root, sb, 0, new IdentityHashMap<>());
        } catch (Throwable t) {
            sb.append("\n/* xatolik: ").append(t).append(" */");
        }
        if (sb.length() > MAX_LENGTH) {
            sb.setLength(MAX_LENGTH);
            sb.append("\n… (qisqartirildi)");
        }
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static boolean isEmptyValue(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Boolean) {
            return !((Boolean) v);
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() == 0;
        }
        if (v instanceof CharSequence) {
            return ((CharSequence) v).length() == 0;
        }
        if (v instanceof byte[]) {
            return ((byte[]) v).length == 0;
        }
        if (v instanceof Collection) {
            return ((Collection<?>) v).isEmpty();
        }
        if (v instanceof Map) {
            return ((Map<?, ?>) v).isEmpty();
        }
        return false;
    }

    private static boolean isSupported(Object v) {
        return v instanceof Boolean || v instanceof Number || v instanceof CharSequence || v instanceof byte[]
                || v instanceof Collection || v instanceof Map || v instanceof TLObject;
    }

    private static void dump(Object o, StringBuilder sb, int depth, IdentityHashMap<Object, Boolean> seen) throws IllegalAccessException {
        if (sb.length() > MAX_LENGTH) {
            return;
        }
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Boolean || o instanceof Number) {
            sb.append(o);
        } else if (o instanceof CharSequence) {
            quote(sb, o.toString());
        } else if (o instanceof byte[]) {
            byte[] bytes = (byte[]) o;
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(bytes.length, 24); i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            quote(sb, "bytes[" + bytes.length + "] " + hex + (bytes.length > 24 ? "…" : ""));
        } else if (o instanceof Collection) {
            Collection<?> list = (Collection<?>) o;
            if (depth >= MAX_DEPTH) {
                quote(sb, "[" + list.size() + " ta element]");
                return;
            }
            sb.append("[");
            boolean first = true;
            for (Object item : list) {
                if (!isSupported(item)) {
                    continue;
                }
                sb.append(first ? "\n" : ",\n");
                first = false;
                indent(sb, depth + 1);
                dump(item, sb, depth + 1, seen);
            }
            if (!first) {
                sb.append("\n");
                indent(sb, depth);
            }
            sb.append("]");
        } else if (o instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) o;
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!isSupported(e.getValue())) {
                    continue;
                }
                sb.append(first ? "\n" : ",\n");
                first = false;
                indent(sb, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                dump(e.getValue(), sb, depth + 1, seen);
            }
            if (!first) {
                sb.append("\n");
                indent(sb, depth);
            }
            sb.append("}");
        } else if (o instanceof TLObject) {
            String name = o.getClass().getSimpleName();
            if (depth >= MAX_DEPTH || seen.containsKey(o)) {
                quote(sb, "<" + name + ">");
                return;
            }
            seen.put(o, Boolean.TRUE);
            ArrayList<Field> fields = new ArrayList<>();
            for (Field f : o.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getDeclaringClass() == TLObject.class) {
                    continue;
                }
                if ("replyMessage".equals(f.getName()) || "replyStory".equals(f.getName())) {
                    continue;
                }
                fields.add(f);
            }
            sortFields(fields);
            sb.append("{\n");
            indent(sb, depth + 1);
            quote(sb, "_");
            sb.append(": ");
            quote(sb, name);
            for (Field f : fields) {
                Object value = f.get(o);
                if (isEmptyValue(value) || !isSupported(value)) {
                    continue;
                }
                sb.append(",\n");
                indent(sb, depth + 1);
                quote(sb, f.getName());
                sb.append(": ");
                dump(value, sb, depth + 1, seen);
            }
            sb.append("\n");
            indent(sb, depth);
            sb.append("}");
            seen.remove(o);
        }
    }

    private static void sortFields(ArrayList<Field> fields) {
        java.util.Collections.sort(fields, new Comparator<Field>() {
            @Override
            public int compare(Field a, Field b) {
                int ia = FIRST_FIELDS.indexOf(a.getName());
                int ib = FIRST_FIELDS.indexOf(b.getName());
                if (ia >= 0 || ib >= 0) {
                    if (ia < 0) return 1;
                    if (ib < 0) return -1;
                    return Integer.compare(ia, ib);
                }
                return a.getName().compareTo(b.getName());
            }
        });
    }
}
