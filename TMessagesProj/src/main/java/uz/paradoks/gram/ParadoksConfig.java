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
        return prefs().getBoolean("pre_save_media", false);
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

    /** Record live streams in the background and send the result to Saved Messages when the stream ends. */
    public static boolean recordStreams() {
        return prefs().getBoolean("record_streams", false);
    }

    public static void setRecordStreams(boolean value) {
        prefs().edit().putBoolean("record_streams", value).apply();
    }

    /** PDF / Word / Excel files open inside the app instead of asking for an external app. */
    public static boolean docViewerEnabled() {
        return prefs().getBoolean("doc_viewer", true);
    }

    public static void setDocViewerEnabled(boolean value) {
        prefs().edit().putBoolean("doc_viewer", value).apply();
    }

    /** A document opened in the viewer is also copied to Downloads/Telegram (once). */
    public static boolean docAutoSave() {
        return prefs().getBoolean("doc_auto_save", true);
    }

    public static void setDocAutoSave(boolean value) {
        prefs().edit().putBoolean("doc_auto_save", value).apply();
    }

    /** Proxy list feed: download proxies from an HTTPS list and switch to the fastest when stuck on "Connecting...". */
    public static boolean proxyFeedEnabled() {
        return prefs().getBoolean("proxy_feed", false);
    }

    public static void setProxyFeedEnabled(boolean v) {
        prefs().edit().putBoolean("proxy_feed", v).apply();
    }

    public static String proxyFeedUrlRaw() {
        return stripSlashes(prefs().getString("proxy_feed_url", ""));
    }

    /** The feed address if it is HTTPS, otherwise empty. */
    public static String proxyFeedUrl() {
        String url = proxyFeedUrlRaw();
        return isAllowedServerUrl(url) ? url : "";
    }

    public static void setProxyFeedUrl(String url) {
        prefs().edit().putString("proxy_feed_url", url == null ? "" : url.trim()).putLong("proxy_feed_last", 0).apply();
    }

    public static long proxyFeedLastFetch() {
        return prefs().getLong("proxy_feed_last", 0);
    }

    public static void setProxyFeedLastFetch(long time) {
        prefs().edit().putLong("proxy_feed_last", time).apply();
    }

    public static String proxyFeedStatus() {
        return prefs().getString("proxy_feed_status", "");
    }

    public static void setProxyFeedStatus(String text) {
        prefs().edit().putString("proxy_feed_status", text == null ? "" : text).apply();
    }

    /** Bedrock server whose status is shown in settings. */
    public static String mcHost() {
        return prefs().getString("mc_host", "").trim();
    }

    public static int mcPort() {
        return prefs().getInt("mc_port", 19132);
    }

    public static void setMcServer(String host, int port) {
        prefs().edit().putString("mc_host", host == null ? "" : host.trim()).putInt("mc_port", port).apply();
    }

    /** Ask before an outgoing call. */
    public static boolean confirmCalls() {
        return prefs().getBoolean("confirm_calls", false);
    }

    public static void setConfirmCalls(boolean v) {
        prefs().edit().putBoolean("confirm_calls", v).apply();
    }

    /** Lotin / Kirill converter in the message menu and the input field selection menu. */
    public static boolean translitEnabled() {
        return prefs().getBoolean("translit", true);
    }

    public static void setTranslitEnabled(boolean v) {
        prefs().edit().putBoolean("translit", v).apply();
    }

    private static String stripSlashes(String raw) {
        String url = raw == null ? "" : raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** Trims, removes trailing slashes and adds "https://" when the scheme is missing. */
    public static String normalizeServerUrl(String raw) {
        String url = stripSlashes(raw);
        if (!url.isEmpty() && !url.contains("://")) {
            url = "https://" + url;
        }
        return url;
    }

    /**
     * Server addresses carry a device token and the account's actions (or the ads shown to the user), so plain HTTP would
     * let anyone on the network read the token or inject content. Only HTTPS is allowed, plus HTTP to localhost / the
     * Android emulator host for development.
     */
    public static boolean isAllowedServerUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return false;
            }
            if ("https".equalsIgnoreCase(scheme)) {
                return true;
            }
            return "http".equalsIgnoreCase(scheme)
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "10.0.2.2".equals(host));
        } catch (Exception e) {
            return false;
        }
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

    public static void setGhostRead(boolean v) {
        setGhostPart("ghost_read", v);
        ParadoksGhost.onReadSettingsChanged();
    }
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
        ParadoksGhost.onReadSettingsChanged();
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
        ParadoksGhost.onReadSettingsChanged();
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

    /** Ad server address as saved, even if it is not secure (used only to show it in settings). */
    public static String adServerUrlRaw() {
        return stripSlashes(prefs().getString("ad_server", DEFAULT_AD_SERVER));
    }

    /** Ad server address that is safe to talk to (HTTPS only, see {@link #isAllowedServerUrl}). Empty otherwise. */
    public static String adServerUrl() {
        String url = adServerUrlRaw();
        return isAllowedServerUrl(url) ? url : "";
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

    /** Liquid glass look of outgoing message bubbles: 0 off, 1 light, 2 medium, 3 strong. */
    public static int glassBubbleLevel() {
        return prefs().getInt("glass_bubbles", 2);
    }

    /** Liquid glass also on incoming bubbles (default: on). */
    public static boolean glassIncoming() {
        return prefs().getBoolean("glass_incoming", true);
    }

    public static void setGlassIncoming(boolean value) {
        prefs().edit().putBoolean("glass_incoming", value).apply();
    }

    /** Biggest incoming file that is pre-downloaded, in MB (default 25). */
    public static int preSaveMaxMb() {
        return prefs().getInt("pre_save_max_mb", 25);
    }

    public static void setPreSaveMaxMb(int value) {
        prefs().edit().putInt("pre_save_max_mb", value).apply();
    }

    /** 0 colorless (default), 1 blue, 2 purple, 3 green, 4 pink, 5 gold. */
    public static int glassTint() {
        return prefs().getInt("glass_tint", 0);
    }

    public static void setGlassTint(int value) {
        prefs().edit().putInt("glass_tint", value).apply();
    }

    /** Edge brightness / sheen: 0 off, 1 soft, 2 normal, 3 bright. */
    public static int glassEdge() {
        return prefs().getInt("glass_edge", 2);
    }

    public static void setGlassEdge(int value) {
        prefs().edit().putInt("glass_edge", value).apply();
    }

    /** Liquid glass look of the round floating buttons (default: off). */
    public static boolean glassButtons() {
        return prefs().getBoolean("glass_buttons", false);
    }

    public static void setGlassButtons(boolean value) {
        prefs().edit().putBoolean("glass_buttons", value).apply();
    }

    public static boolean glassRefract() {
        return prefs().getBoolean("glass_refract", false);
    }

    public static void setGlassRefract(boolean value) {
        prefs().edit().putBoolean("glass_refract", value).apply();
    }

    public static void setGlassBubbleLevel(int level) {
        prefs().edit().putInt("glass_bubbles", level).apply();
    }

    /** 0 standard, 1 weak internet, 2 auto. */
    public static int downloadMode() {
        return prefs().getInt("download_mode", 0);
    }

    public static void setDownloadMode(int mode) {
        prefs().edit().putInt("download_mode", mode).apply();
    }

    /** Remove EXIF/GPS/XMP from pictures before sending (default: on). */
    public static boolean stripMetadata() {
        return prefs().getBoolean("strip_metadata", true);
    }

    public static void setStripMetadata(boolean value) {
        prefs().edit().putBoolean("strip_metadata", value).apply();
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

    /** Unlock hidden/locked chats with a fingerprint or face as well (default on, when the phone supports it). */
    public static boolean lockBiometric() {
        return prefs().getBoolean("lock_biometric", true);
    }

    public static void setLockBiometric(boolean value) {
        prefs().edit().putBoolean("lock_biometric", value).apply();
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
    // ParadoksGram: contact stories bar visibility (default off)
    public static boolean showContactStories() {
        return prefs().getBoolean("show_contact_stories", false);
    }

    public static void setShowContactStories(boolean value) {
        prefs().edit().putBoolean("show_contact_stories", value).apply();
    }

    // ---- folder tabs (see ParadoksFolders) ----
    /** Folders (tabs) that show only unread chats. Entries look like "account:filterId" (filterId 0 = All chats). */
    public static java.util.Set<String> unreadOnlyFolders() {
        return new java.util.HashSet<>(prefs().getStringSet("folders_unread_only", new java.util.HashSet<String>()));
    }

    public static void setUnreadOnlyFolders(java.util.Set<String> set) {
        prefs().edit().putStringSet("folders_unread_only", new java.util.HashSet<>(set)).apply();
    }

    /** Server id of the folder the app opens on (0 = All chats), or -1 when none was chosen. Per account. */
    public static int defaultFolder(int account) {
        return prefs().getInt("folder_default_" + account, -1);
    }

    public static void setDefaultFolder(int account, int filterId) {
        SharedPreferences.Editor editor = prefs().edit();
        if (filterId < 0) {
            editor.remove("folder_default_" + account);
        } else {
            editor.putInt("folder_default_" + account, filterId);
        }
        editor.apply();
    }
}