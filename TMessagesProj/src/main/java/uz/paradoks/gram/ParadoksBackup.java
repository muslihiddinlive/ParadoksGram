package uz.paradoks.gram;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Export / import of ParadoksGram's own settings ("paradoks" SharedPreferences) as a JSON file,
 * so a person can move their settings to a new phone or new install.
 *
 * Only the keys listed in {@link #SCHEMA} are ever written to or read from a backup file: per-install
 * identifiers (device pairing token, install id), the AI bot's own cached id/username, and short-lived
 * counters are never included, so a backup file can neither re-pair the AI device link on another phone
 * nor silently restore stale cache. A restored file is also only ever read against this fixed schema:
 * an unknown key in the file is ignored, and a key's type always comes from the schema, never from the
 * file, so a hand-edited or foreign file cannot inject an arbitrary preference.
 */
public final class ParadoksBackup {

    public static final String FORMAT = "paradoksgram-settings-backup";
    public static final int VERSION = 1;

    private static final int T_BOOL = 0;
    private static final int T_INT = 1;
    private static final int T_LONG = 2;
    private static final int T_STRING = 3;
    private static final int T_STRING_SET = 4;

    private static final LinkedHashMap<String, Integer> SCHEMA = new LinkedHashMap<>();

    static {
        SCHEMA.put("show_id", T_BOOL);
        SCHEMA.put("keep_deleted", T_BOOL);
        SCHEMA.put("keep_deleted_media", T_BOOL);
        SCHEMA.put("pre_save_media", T_BOOL);
        SCHEMA.put("deleted_db_extra_consent", T_BOOL);
        SCHEMA.put("notify_deleted", T_BOOL);
        SCHEMA.put("media_retention_days", T_INT);
        SCHEMA.put("media_max_mb", T_INT);
        SCHEMA.put("force_blur", T_BOOL);
        SCHEMA.put("restore_backup_media", T_BOOL);
        SCHEMA.put("record_streams", T_BOOL);
        SCHEMA.put("show_ai_button", T_BOOL);
        SCHEMA.put("ai_server", T_STRING);
        SCHEMA.put("device_commands", T_BOOL);
        SCHEMA.put("track_edits", T_BOOL);
        SCHEMA.put("ghost_split", T_BOOL);
        SCHEMA.put("ghost_read", T_BOOL);
        SCHEMA.put("ghost_typing", T_BOOL);
        SCHEMA.put("ghost_online", T_BOOL);
        SCHEMA.put("ghost_stories", T_BOOL);
        SCHEMA.put("ghost_saved_mask", T_INT);
        SCHEMA.put("ghost_sched", T_BOOL);
        SCHEMA.put("ghost_sched_start", T_INT);
        SCHEMA.put("ghost_sched_end", T_INT);
        SCHEMA.put("hide_sponsored", T_BOOL);
        SCHEMA.put("own_ads", T_BOOL);
        SCHEMA.put("ad_server", T_STRING);
        SCHEMA.put("ad_on_interaction", T_BOOL);
        SCHEMA.put("hidden_chats", T_STRING_SET);
        SCHEMA.put("hidden_in_search", T_BOOL);
        SCHEMA.put("lock_hash", T_STRING);
        SCHEMA.put("lock_salt", T_STRING);
        SCHEMA.put("lock_chats", T_STRING_SET);
        SCHEMA.put("voice_effect", T_INT);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            SCHEMA.put("deleted_db_target_" + a, T_LONG);
        }
        // Deliberately NOT in the schema, so a backup file can never touch them either way:
        // device_token, device_paired (per-install AI pairing), install_id, ad_cache,
        // adseen_* (daily counters), update_announced, ai_bot_id/ai_bot_username/ai_started (re-learned
        // automatically), hidden_muted_* (transient, tied to chats that are hidden right now).
    }

    public static final class Result {
        public boolean ok;
        public String error;
        public int applied;
        public int skipped;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("paradoks", Context.MODE_PRIVATE);
    }

    /** Suggested file name, e.g. "paradoksgram-sozlamalar-2026-10-29.json". */
    public static String suggestedFileName() {
        return "paradoksgram-sozlamalar-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json";
    }

    public static int schemaSize() {
        return SCHEMA.size();
    }

    public static JSONObject buildExportJson() throws Exception {
        SharedPreferences p = prefs();
        JSONObject prefsObj = new JSONObject();
        for (java.util.Map.Entry<String, Integer> entry : SCHEMA.entrySet()) {
            String key = entry.getKey();
            if (!p.contains(key)) {
                continue;
            }
            switch (entry.getValue()) {
                case T_BOOL:
                    prefsObj.put(key, p.getBoolean(key, false));
                    break;
                case T_INT:
                    prefsObj.put(key, p.getInt(key, 0));
                    break;
                case T_LONG:
                    // as text: keeps 64-bit dialog ids exact in JSON
                    prefsObj.put(key, String.valueOf(p.getLong(key, 0)));
                    break;
                case T_STRING: {
                    String v = p.getString(key, null);
                    if (v != null) {
                        prefsObj.put(key, v);
                    }
                    break;
                }
                case T_STRING_SET: {
                    Set<String> v = p.getStringSet(key, null);
                    if (v != null) {
                        prefsObj.put(key, new JSONArray(v));
                    }
                    break;
                }
            }
        }
        JSONObject root = new JSONObject();
        root.put("app", "paradoksgram");
        root.put("kind", FORMAT);
        root.put("version", VERSION);
        root.put("build", ParadoksBuild.NUMBER);
        root.put("exported_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
        root.put("prefs", prefsObj);
        return root;
    }

    /** Parses and validates the top-level shape only; does not touch any settings yet. */
    public static JSONObject parse(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (!FORMAT.equals(root.optString("kind"))) {
            throw new IllegalArgumentException("Bu ParadoksGram sozlamalar fayli emas");
        }
        if (root.optJSONObject("prefs") == null) {
            throw new IllegalArgumentException("Fayl bo'sh yoki buzilgan");
        }
        return root;
    }

    /**
     * Applies a previously parsed backup. Only keys present in {@link #SCHEMA} are ever written, with
     * the type the schema (not the file) says they must have; everything else in the file is ignored.
     */
    public static Result restore(JSONObject root) {
        Result result = new Result();
        try {
            JSONObject prefsObj = root.getJSONObject("prefs");
            SharedPreferences.Editor editor = prefs().edit();
            Iterator<String> it = prefsObj.keys();
            while (it.hasNext()) {
                String key = it.next();
                Integer type = SCHEMA.get(key);
                if (type == null) {
                    result.skipped++;
                    continue;
                }
                try {
                    switch (type) {
                        case T_BOOL:
                            editor.putBoolean(key, prefsObj.getBoolean(key));
                            break;
                        case T_INT:
                            editor.putInt(key, prefsObj.getInt(key));
                            break;
                        case T_LONG:
                            editor.putLong(key, Long.parseLong(String.valueOf(prefsObj.get(key))));
                            break;
                        case T_STRING:
                            editor.putString(key, prefsObj.getString(key));
                            break;
                        case T_STRING_SET: {
                            JSONArray arr = prefsObj.getJSONArray(key);
                            HashSet<String> set = new HashSet<>();
                            for (int i = 0; i < arr.length(); i++) {
                                set.add(arr.getString(i));
                            }
                            editor.putStringSet(key, set);
                            break;
                        }
                    }
                    result.applied++;
                } catch (Throwable fieldError) {
                    result.skipped++;
                }
            }
            editor.apply();
            result.ok = true;
        } catch (Throwable e) {
            result.ok = false;
            result.error = String.valueOf(e.getMessage());
        }
        return result;
    }

    private ParadoksBackup() {
    }
}
