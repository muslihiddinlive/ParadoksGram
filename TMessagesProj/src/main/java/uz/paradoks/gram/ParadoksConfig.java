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

    /** Silently pre-downloads incoming media (Wi-Fi only) so it can be kept if the message is deleted. */
    public static boolean preSaveMedia() {
        return prefs().getBoolean("pre_save_media", true);
    }

    public static void setPreSaveMedia(boolean value) {
        prefs().edit().putBoolean("pre_save_media", value).apply();
    }

    /** Chat/channel/bot that receives forwarded copies of incoming media (0 = disabled). Per account. */
    public static long dbTarget(int account) {
        return prefs().getLong("deleted_db_target_" + account, 0);
    }

    public static void setDbTarget(int account, long dialogId) {
        prefs().edit().putLong("deleted_db_target_" + account, dialogId).apply();
    }

    /** User accepted the risks of using bots / private chats as a database. */
    public static boolean dbExtraConsent() {
        return prefs().getBoolean("deleted_db_extra_consent", false);
    }

    public static void setDbExtraConsent(boolean value) {
        prefs().edit().putBoolean("deleted_db_extra_consent", value).apply();
    }

    /** Local notification "X deleted a message". */
    public static boolean notifyDeleted() {
        return prefs().getBoolean("notify_deleted", false);
    }

    public static void setNotifyDeleted(boolean value) {
        prefs().edit().putBoolean("notify_deleted", value).apply();
    }

    /** Saved deleted media is removed after this many days (0 = never). */
    public static int mediaRetentionDays() {
        return prefs().getInt("media_retention_days", 0);
    }

    /** Saved deleted media above this size in MB is trimmed, oldest first (0 = no limit). */
    public static int mediaMaxMb() {
        return prefs().getInt("media_max_mb", 0);
    }

    public static void setMediaCleanup(int days, int maxMb) {
        prefs().edit().putInt("media_retention_days", days).putInt("media_max_mb", maxMb).apply();
    }

    /** Highest build number whose "update available" dialog was already shown. */
    public static int updateAnnounced() {
        return prefs().getInt("update_announced", 0);
    }

    public static void setUpdateAnnounced(int build) {
        prefs().edit().putInt("update_announced", build).apply();
    }

    /** Turns on Telegram's blur-behind effect even on phones it considers too weak (and on Android below 12). */
    public static boolean forceBlur() {
        return prefs().getBoolean("force_blur", false);
    }

    public static void setForceBlur(boolean value) {
        prefs().edit().putBoolean("force_blur", value).apply();
    }

    /** Show backed-up media of deleted messages inside the chat where they were deleted. */
    public static boolean restoreBackupMedia() {
        return prefs().getBoolean("restore_backup_media", true);
    }

    public static void setRestoreBackupMedia(boolean value) {
        prefs().edit().putBoolean("restore_backup_media", value).apply();
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
    public static void setGhostOnline(boolean v) {
        setGhostPart("ghost_online", v);
        ParadoksGhost.onOnlineSwitched(v);
    }
    public static void setGhostStories(boolean v) { setGhostPart("ghost_stories", v); }

    /** Which ghost parts were on before the top-bar button switched everything off (bit mask). */
    public static int ghostSavedMask() {
        return prefs().getInt("ghost_saved_mask", 0);
    }

    public static void setGhostSavedMask(int mask) {
        prefs().edit().putInt("ghost_saved_mask", mask).apply();
    }

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

    // ---- own ads ----
    /** Address of the ads server, e.g. https://paradoks-ads.onrender.com . Empty = own ads are off. */
    public static final String DEFAULT_AD_SERVER = "";

    public static boolean ownAdsEnabled() {
        return prefs().getBoolean("own_ads", true);
    }

    public static void setOwnAdsEnabled(boolean v) {
        prefs().edit().putBoolean("own_ads", v).apply();
    }

    public static String adServerUrl() {
        String url = prefs().getString("ad_server", DEFAULT_AD_SERVER).trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static void setAdServerUrl(String url) {
        prefs().edit().putString("ad_server", url == null ? "" : url.trim()).remove("ad_cache").apply();
    }

    public static String adCache() {
        return prefs().getString("ad_cache", null);
    }

    public static void setAdCache(String json) {
        prefs().edit().putString("ad_cache", json).apply();
    }

    /** Random id of this installation. It is not linked to the Telegram account. */
    public static String installId() {
        String id = prefs().getString("install_id", null);
        if (id == null) {
            byte[] raw = new byte[12];
            new java.security.SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            id = sb.toString();
            prefs().edit().putString("install_id", id).apply();
        }
        return id;
    }

    public static int adSeenToday(int adId, String day) {
        return prefs().getInt("adseen_" + adId + "_" + day, 0);
    }

    public static void adSeenIncrement(int adId, String day) {
        String key = "adseen_" + adId + "_" + day;
        prefs().edit().putInt(key, prefs().getInt(key, 0) + 1).apply();
    }

    /** With ads hidden: show them in a chat only after the user reacts or comments there. */
    public static boolean adOnInteraction() {
        return prefs().getBoolean("ad_on_interaction", false);
    }

    public static void setAdOnInteraction(boolean v) {
        prefs().edit().putBoolean("ad_on_interaction", v).apply();
    }

    public static void setHideSponsored(boolean v) {
        prefs().edit().putBoolean("hide_sponsored", v).apply();
    }

    // ---- hidden chats ----
    public static java.util.Set<String> hiddenChats() {
        return new java.util.HashSet<>(prefs().getStringSet("hidden_chats", new java.util.HashSet<String>()));
    }

    public static void setHiddenChats(java.util.Set<String> set) {
        prefs().edit().putStringSet("hidden_chats", new java.util.HashSet<>(set)).apply();
    }

    /** Show hidden chats in global search results (default: off). */
    public static boolean showHiddenInSearch() {
        return prefs().getBoolean("hidden_in_search", false);
    }

    public static void setShowHiddenInSearch(boolean value) {
        prefs().edit().putBoolean("hidden_in_search", value).apply();
    }

    public static boolean hasHiddenWasMuted(String key) {
        return prefs().contains("hidden_muted_" + key);
    }

    /** Whether the chat was already muted before it was hidden (so unhiding must not unmute it). */
    public static boolean hiddenWasMuted(String key) {
        return prefs().getBoolean("hidden_muted_" + key, false);
    }

    public static void setHiddenWasMuted(String key, boolean muted) {
        prefs().edit().putBoolean("hidden_muted_" + key, muted).apply();
    }

    public static void clearHiddenWasMuted(String key) {
        prefs().edit().remove("hidden_muted_" + key).apply();
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
