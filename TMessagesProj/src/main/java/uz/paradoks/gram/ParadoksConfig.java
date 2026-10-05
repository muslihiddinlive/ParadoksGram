package uz.paradoks.gram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** ParadoksGram settings storage. */
public final class ParadoksConfig {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("paradoks", Context.MODE_PRIVATE);
    }

    public static boolean showId() {
        return prefs().getBoolean("show_id", true);
    }

    public static void setShowId(boolean value) {
        prefs().edit().putBoolean("show_id", value).apply();
    }

    /** 0 = off, 1 = robot, 2 = cave, 3 = villager */
    public static int getVoiceEffect() {
        return prefs().getInt("voice_effect", 0);
    }

    public static void setVoiceEffect(int value) {
        prefs().edit().putInt("voice_effect", value).apply();
    }

    private ParadoksConfig() {
    }
}
