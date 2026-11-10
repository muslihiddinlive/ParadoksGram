package uz.paradoks.gram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

/**
 * Bridge between the music studio, which keeps playing after its screen is closed, and the player
 * bar on top of the chats ({@link org.telegram.ui.Components.FragmentContextView}).
 */
public final class ParadoksMusicBg {

    public interface Listener {
        void onStudioChanged();
    }

    private static ParadoksMusicLabActivity instance;
    private static final ArrayList<Listener> listeners = new ArrayList<>();

    private ParadoksMusicBg() {
    }

    // ---- called by the studio

    static void register(ParadoksMusicLabActivity studio) {
        instance = studio;
        notifyChanged();
    }

    static void unregister(ParadoksMusicLabActivity studio) {
        if (instance == studio) {
            instance = null;
        }
        notifyChanged();
    }

    /** A fresh studio is opening: a studio left running in the background must not play together with it. */
    static void stopOthers(ParadoksMusicLabActivity self) {
        ParadoksMusicLabActivity other = instance;
        if (other != null && other != self) {
            other.stopBackground();
        }
    }

    static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<Listener> copy = new ArrayList<>(listeners);
            for (Listener listener : copy) {
                try {
                    listener.onStudioChanged();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        });
    }

    // ---- called by the player bar

    /** True while the studio screen is closed but the studio is still alive (playing or paused). */
    public static boolean isActive() {
        ParadoksMusicLabActivity studio = instance;
        return studio != null && studio.isBackgroundAlive();
    }

    public static boolean isPlaying() {
        ParadoksMusicLabActivity studio = instance;
        return studio != null && studio.isBackgroundPlaying();
    }

    public static String title() {
        ParadoksMusicLabActivity studio = instance;
        return studio != null ? studio.backgroundTitle() : "";
    }

    public static void toggle() {
        ParadoksMusicLabActivity studio = instance;
        if (studio != null) {
            studio.toggleFromBar();
        }
    }

    /** Another player (e.g. a voice message) has started: pause the studio. */
    public static void pausePlayback() {
        ParadoksMusicLabActivity studio = instance;
        if (studio != null && studio.isBackgroundPlaying()) {
            studio.toggleFromBar();
        }
    }

    public static void stop() {
        ParadoksMusicLabActivity studio = instance;
        if (studio != null) {
            studio.stopBackground();
        }
    }

    /** Opens the running studio again on top of {@code from}. */
    public static boolean open(BaseFragment from) {
        ParadoksMusicLabActivity studio = instance;
        return studio != null && from != null && studio.revive(from);
    }

    public static void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
