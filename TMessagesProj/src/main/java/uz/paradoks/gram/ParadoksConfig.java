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

    public static boolean keepDeleted() {
        return prefs().getBoolean("keep_deleted", true);
    }

    public static void setKeepDeleted(boolean value) {
        prefs().edit().putBoolean("keep_deleted", value).apply();
    }

    public static boolean keepDeletedMedia() {
        return prefs().getBoolean("keep_deleted_media", true);
    }

    public static void setKeepDeletedMedia(boolean value) {
        prefs().edit().putBoolean("keep_deleted_media", value).apply();
    }

    public static boolean trackEdits() {
        return prefs().getBoolean("track_edits", true);
    }

    public static void setTrackEdits(boolean value) {
        prefs().edit().putBoolean("track_edits", value).apply();
    }

    /** Older versions had one ghost toggle; carry it over to the four separate ones once. */
    private static void migrateGhost() {
        SharedPreferences p = prefs();
        if (p.contains("ghost_split")) {
            return;
        }
        boolean old = p.getBoolean("ghost_mode", false);
        p.edit().putBoolean("ghost_read", old).putBoolean("ghost_typing", old)
                .putBoolean("ghost_online", old).putBoolean("ghost_stories", old)
                .putBoolean("ghost_split", true).apply();
    }

    private static boolean ghostPart(String key) {
        migrateGhost();
        return prefs().getBoolean(key, false) && ghostInWindow();
    }

    private static boolean ghostPartRaw(String key) {
        migrateGhost();
        return prefs().getBoolean(key, false);
    }

    private static void setGhostPart(String key, boolean value) {
        migrateGhost();
        prefs().edit().putBoolean(key, value).apply();
    }

    /** True when ghost "read receipts" are on and active right now (schedule respected). */
    public static boolean ghostRead() { return ghostPart("ghost_read"); }
    public static boolean ghostTyping() { return ghostPart("ghost_typing"); }
    public static boolean ghostOnline() { return ghostPart("ghost_online"); }
    public static boolean ghostStories() { return ghostPart("ghost_stories"); }

    /** Raw switch values for the settings page (ignores the schedule window). */
    public static boolean ghostReadSwitch() { return ghostPartRaw("ghost_read"); }
    public static boolean ghostTypingSwitch() { return ghostPartRaw("ghost_typing"); }
    public static boolean ghostOnlineSwitch() { return ghostPartRaw("ghost_online"); }
    public static boolean ghostStoriesSwitch() { return ghostPartRaw("ghost_stories"); }

    public static void setGhostRead(boolean v) { setGhostPart("ghost_read", v); }
    public static void setGhostTyping(boolean v) { setGhostPart("ghost_typing", v); }
    public static void setGhostOnline(boolean v) { setGhostPart("ghost_online", v); }
    public static void setGhostStories(boolean v) { setGhostPart("ghost_stories", v); }

    /** Ghost schedule: when enabled, the switches above only work between start and end. */
    public static boolean ghostScheduleEnabled() {
        return prefs().getBoolean("ghost_sched", false);
    }

    public static void setGhostScheduleEnabled(boolean v) {
        prefs().edit().putBoolean("ghost_sched", v).apply();
    }

    /** Minutes since midnight. */
    public static int ghostScheduleStart() {
        return prefs().getInt("ghost_sched_start", 22 * 60);
    }

    public static int ghostScheduleEnd() {
        return prefs().getInt("ghost_sched_end", 8 * 60);
    }

    public static void setGhostSchedule(int startMinutes, int endMinutes) {
        prefs().edit().putInt("ghost_sched_start", startMinutes).putInt("ghost_sched_end", endMinutes).apply();
    }

    private static boolean ghostInWindow() {
        if (!ghostScheduleEnabled()) {
            return true;
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        int now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int start = ghostScheduleStart();
        int end = ghostScheduleEnd();
        if (start == end) {
            return true;
        }
        return start < end ? (now >= start && now < end) : (now >= start || now < end);
    }

    /** Hide "Sponsored" messages in channels and bots. */
    public static boolean hideSponsored() {
        return prefs().getBoolean("hide_sponsored", true);
    }

    public static void setHideSponsored(boolean v) {
        prefs().edit().putBoolean("hide_sponsored", v).apply();
    }

    // ---- chat lock ----
    public static String lockHash() {
        return prefs().getString("lock_hash", null);
    }

    public static String lockSalt() {
        return prefs().getString("lock_salt", null);
    }

    public static void setLockPin(String salt, String hash) {
        prefs().edit().putString("lock_salt", salt).putString("lock_hash", hash).apply();
    }

    public static void clearLockPin() {
        prefs().edit().remove("lock_salt").remove("lock_hash").remove("lock_chats").apply();
    }

    public static java.util.Set<String> lockedChats() {
        return new java.util.HashSet<>(prefs().getStringSet("lock_chats", new java.util.HashSet<String>()));
    }

    public static void setLockedChats(java.util.Set<String> set) {
        prefs().edit().putStringSet("lock_chats", new java.util.HashSet<>(set)).apply();
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
