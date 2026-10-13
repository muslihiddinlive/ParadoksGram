package uz.paradoks.gram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * "Ads only on interaction" mode.
 *
 * While ads are hidden, a chat stays ad-free. When the user reacts to a post or writes a
 * comment, a request reaches Telegram anyway (the user is no longer anonymous), so that chat
 * is allowed to load and show its sponsored messages. Telegram's own code then reports the
 * view only when the ad is actually on screen, so no impression is counted without being
 * shown. Ghost mode is never touched. The permission lasts until that chat is closed.
 */
public final class ParadoksAds {

    private static final Set<String> ALLOWED = Collections.synchronizedSet(new HashSet<String>());

    private static String key(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    /** Used by MessagesController.getSponsoredMessages: true = return no ads. */
    public static boolean shouldHide(int account, long dialogId) {
        if (!ParadoksConfig.hideSponsored()) {
            return false;
        }
        return !(ParadoksConfig.adOnInteraction() && ALLOWED.contains(key(account, dialogId)));
    }

    /** The user reacted or commented: let this chat (the channel or bot) show its ads. */
    public static void onInteraction(int account, long dialogId) {
        if (dialogId == 0 || !ParadoksConfig.hideSponsored() || !ParadoksConfig.adOnInteraction()) {
            return;
        }
        if (ALLOWED.add(key(account, dialogId))) {
            // Starts loading; ChatActivity inserts the ads when didLoadSponsoredMessages arrives.
            AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).getSponsoredMessages(dialogId));
        }
    }

    /** The chat was closed: the next visit starts ad-free again. */
    public static void clear(int account, long dialogId) {
        ALLOWED.remove(key(account, dialogId));
    }

    private ParadoksAds() {
    }
}
