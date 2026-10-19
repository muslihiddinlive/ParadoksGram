package uz.paradoks.gram;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.ArrayList;

/**
 * Shows the IDs of stickers, (custom) emoji and GIFs: document id, sticker set id / short name,
 * type, size. Opened from the message long-press menu and from the sticker/GIF preview menu.
 */
public final class ParadoksIds {

    private ParadoksIds() {
    }

    /** True if the message is a sticker, animated emoji or GIF, or its text contains custom emoji. */
    public static boolean hasIds(MessageObject object) {
        try {
            if (object == null) {
                return false;
            }
            if (object.isSticker() || object.isAnimatedSticker() || object.isAnimatedEmoji()
                    || object.isAnimatedEmojiStickers() || object.isGif()) {
                return object.getDocument() != null;
            }
            return !customEmojiIds(object.messageOwner).isEmpty();
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static void showForMessage(Context context, MessageObject object) {
        if (object == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        TLRPC.Document document = object.getDocument();
        if (document != null && (object.isSticker() || object.isAnimatedSticker() || object.isAnimatedEmoji()
                || object.isAnimatedEmojiStickers() || object.isGif())) {
            sb.append(describe(document));
        }
        ArrayList<Long> custom = customEmojiIds(object.messageOwner);
        if (!custom.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("Xabar matnidagi maxfiy (custom) emojilar:");
            for (int i = 0; i < custom.size(); i++) {
                sb.append("\n").append(i + 1).append(". ID: ").append(custom.get(i));
            }
        }
        show(context, sb.toString());
    }

    public static void showForDocument(Context context, TLRPC.Document document) {
        if (document != null) {
            show(context, describe(document));
        }
    }

    private static ArrayList<Long> customEmojiIds(TLRPC.Message message) {
        ArrayList<Long> ids = new ArrayList<>();
        if (message != null && message.entities != null) {
            for (TLRPC.MessageEntity entity : message.entities) {
                if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                    long id = ((TLRPC.TL_messageEntityCustomEmoji) entity).document_id;
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    private static String describe(TLRPC.Document document) {
        String type = "Fayl";
        String alt = null;
        TLRPC.InputStickerSet set = null;
        if (document.attributes != null) {
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                    type = "Maxsus (custom) emoji";
                    alt = attribute.alt;
                    set = attribute.stickerset;
                } else if (attribute instanceof TLRPC.TL_documentAttributeSticker) {
                    type = "Stiker";
                    alt = attribute.alt;
                    set = attribute.stickerset;
                }
            }
        }
        if (MessageObject.isAnimatedEmoji(document)) {
            type = "Animatsiyalangan emoji";
        } else if (type.equals("Fayl") && MessageObject.isGifDocument(document)) {
            type = "GIF";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Turi: ").append(type);
        if (alt != null && !alt.isEmpty()) {
            sb.append("\nEmoji: ").append(alt);
        }
        sb.append("\nDocument ID: ").append(document.id);
        if (set != null) {
            if (set.id != 0) {
                sb.append("\nStiker to'plami ID: ").append(set.id);
            }
            if (set.short_name != null && !set.short_name.isEmpty()) {
                sb.append("\nTo'plam nomi: ").append(set.short_name);
            }
        }
        if (document.mime_type != null) {
            sb.append("\nFormat: ").append(document.mime_type);
        }
        if (document.size > 0) {
            sb.append("\nHajmi: ").append(AndroidUtilities.formatFileSize(document.size));
        }
        sb.append("\nDC: ").append(document.dc_id);
        return sb.toString();
    }

    private static void show(Context context, String text) {
        if (context == null || text == null || text.isEmpty()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("ID ma'lumotlari");
        builder.setMessage(text);
        builder.setPositiveButton("Nusxa olish", (d, w) -> AndroidUtilities.addToClipboard(text));
        builder.setNegativeButton("Yopish", null);
        builder.show();
    }
}
