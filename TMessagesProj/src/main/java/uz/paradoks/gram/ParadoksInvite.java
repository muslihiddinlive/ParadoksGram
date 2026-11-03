package uz.paradoks.gram;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ShareAlert;

/**
 * "Do'stlarni taklif qilish": opens the regular Telegram share sheet (groups, channels, private
 * chats, bots...) and sends the ParadoksGram invitation text with the download link to the chosen
 * chats. Same text and behaviour as ParadoksGram-Windows.
 */
public final class ParadoksInvite {

    public static final String LINK = "https://ParadoksGram.pages.dev";

    private ParadoksInvite() {
    }

    public static String text() {
        return "Salom, men ParadoksGramdan foydalanyapman. Menga qo'shiling! Uni bu yerdan yuklab oling: " + LINK;
    }

    public static void open(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new ShareAlert(fragment.getParentActivity(), null, text(), false, LINK, false, fragment.getResourceProvider()));
    }
}
