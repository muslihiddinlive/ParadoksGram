package uz.paradoks.gram;

import androidx.collection.LongSparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * ParadoksGram's own ads. The list comes from our ads server, the view and click counters go
 * back to it. Nothing about the user, the chat or any Telegram data is sent: only the ad id,
 * the kind of event and a random anonymous install id.
 *
 * The ads reuse the sponsored-message pipeline of the chat screen (so they appear at the end of
 * a channel and at the top of a bot chat) but they are drawn with the label "Reklama", they never
 * touch Telegram's ad requests, and their views and clicks are reported to our server instead.
 */
public final class ParadoksAdsRemote {

    private static final String ID_PREFIX = "PDXAD:";
    private static final long DEFAULT_TTL_MS = 30L * 60 * 1000;
    private static final int MAX_BODY = 256 * 1024;
    private static final Object LOCK = new Object();
    private static final Random RANDOM = new Random();

    private static final class Ad {
        int id;
        String title;
        String text;
        String button;
        String url;
        boolean channel;
        boolean bot;
        int weight = 1;
        int dailyCap = 3;
    }

    private static ArrayList<Ad> ads = new ArrayList<>();
    private static boolean cacheLoaded;
    private static long lastFetch;
    private static long ttlMs = DEFAULT_TTL_MS;
    private static boolean fetching;

    public static boolean isEnabled() {
        return ParadoksConfig.ownAdsEnabled() && !ParadoksConfig.adServerUrl().isEmpty();
    }

    public static boolean isOurs(MessageObject message) {
        if (message == null || message.sponsoredId == null) {
            return false;
        }
        byte[] id = message.sponsoredId;
        byte[] prefix = ID_PREFIX.getBytes(StandardCharsets.UTF_8);
        if (id.length <= prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (id[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int adIdOf(MessageObject message) {
        try {
            String s = new String(message.sponsoredId, StandardCharsets.UTF_8);
            return Integer.parseInt(s.substring(ID_PREFIX.length()));
        } catch (Throwable e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- loading

    /** Refreshes the ad list in the background at most once per ttl. Cheap to call often. */
    public static void refreshIfNeeded() {
        if (!isEnabled()) {
            return;
        }
        synchronized (LOCK) {
            loadCacheLocked();
            long now = System.currentTimeMillis();
            if (fetching || now - lastFetch < ttlMs) {
                return;
            }
            fetching = true;
        }
        Utilities.globalQueue.postRunnable(ParadoksAdsRemote::fetch);
    }

    /** The server address changed: drop the old list and load a new one right away. */
    public static void forceRefresh() {
        synchronized (LOCK) {
            ads = new ArrayList<>();
            cacheLoaded = true;
            lastFetch = 0;
            fetching = false;
        }
        refreshIfNeeded();
    }

    private static void loadCacheLocked() {
        if (cacheLoaded) {
            return;
        }
        cacheLoaded = true;
        String cached = ParadoksConfig.adCache();
        if (cached != null) {
            ArrayList<Ad> parsed = parse(cached);
            if (parsed != null) {
                ads = parsed;
            }
        }
    }

    private static void fetch() {
        try {
            String base = ParadoksConfig.adServerUrl();
            String body = httpGet(base + "/ads");
            JSONObject root = new JSONObject(body);
            ArrayList<Ad> parsed = parse(body);
            synchronized (LOCK) {
                if (parsed != null) {
                    ads = parsed;
                    ParadoksConfig.setAdCache(body);
                }
                ttlMs = Math.max(60, root.optInt("ttl", 1800)) * 1000L;
                lastFetch = System.currentTimeMillis();
            }
        } catch (Throwable ignore) {
            synchronized (LOCK) {
                lastFetch = System.currentTimeMillis() - ttlMs + 5 * 60 * 1000L; // retry in about 5 minutes
            }
        } finally {
            synchronized (LOCK) {
                fetching = false;
            }
        }
    }

    private static ArrayList<Ad> parse(String json) {
        try {
            JSONArray array = new JSONObject(json).getJSONArray("ads");
            ArrayList<Ad> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Ad ad = new Ad();
                ad.id = o.getInt("id");
                ad.title = o.getString("title");
                ad.text = o.getString("text");
                ad.button = o.optString("button", "Ochish");
                ad.url = o.getString("url");
                ad.weight = Math.max(1, o.optInt("weight", 1));
                ad.dailyCap = Math.max(1, o.optInt("daily_cap", 3));
                JSONArray placements = o.optJSONArray("placements");
                if (placements != null) {
                    for (int p = 0; p < placements.length(); p++) {
                        String name = placements.getString(p);
                        if ("channel".equals(name)) {
                            ad.channel = true;
                        } else if ("bot".equals(name)) {
                            ad.bot = true;
                        }
                    }
                }
                if (!safeUrl(ad.url)) {
                    continue;
                }
                list.add(ad);
            }
            return list;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Only links that open a web page or a Telegram link. Anything else is dropped. */
    private static boolean safeUrl(String url) {
        String u = url.toLowerCase(Locale.US);
        return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("tg://");
    }

    // ---------------------------------------------------------------- choosing

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static Ad pick(boolean bot) {
        synchronized (LOCK) {
            loadCacheLocked();
            ArrayList<Ad> candidates = new ArrayList<>();
            int total = 0;
            String day = today();
            for (Ad ad : ads) {
                if (bot ? !ad.bot : !ad.channel) {
                    continue;
                }
                if (ParadoksConfig.adSeenToday(ad.id, day) >= ad.dailyCap) {
                    continue;
                }
                candidates.add(ad);
                total += ad.weight;
            }
            if (candidates.isEmpty()) {
                return null;
            }
            int r = RANDOM.nextInt(total);
            for (Ad ad : candidates) {
                r -= ad.weight;
                if (r < 0) {
                    return ad;
                }
            }
            return candidates.get(0);
        }
    }

    /** Returns our ad for this chat as a sponsored-style message (or an empty list). */
    public static ArrayList<MessageObject> build(int account, long dialogId, boolean bot) {
        ArrayList<MessageObject> result = new ArrayList<>();
        if (!isEnabled()) {
            return result;
        }
        refreshIfNeeded();
        Ad ad = pick(bot);
        if (ad == null) {
            return result;
        }
        try {
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.peer_id = MessagesController.getInstance(account).getPeer(dialogId);
            message.flags |= 256;
            message.date = ConnectionsManager.getInstance(account).getCurrentTime();
            message.id = -20000000 - ad.id;
            message.message = ad.text;
            MessageObject messageObject = new MessageObject(account, message,
                    new LongSparseArray<TLRPC.User>(), new LongSparseArray<TLRPC.Chat>(), true, true);
            messageObject.sponsoredId = (ID_PREFIX + ad.id).getBytes(StandardCharsets.UTF_8);
            messageObject.sponsoredTitle = ad.title;
            messageObject.customName = ad.title;
            messageObject.sponsoredUrl = ad.url;
            messageObject.sponsoredButtonText = ad.button;
            messageObject.sponsoredRecommended = false;
            messageObject.sponsoredCanReport = false;
            messageObject.setType();
            messageObject.textLayoutBlocks = new ArrayList<>();
            messageObject.generateThumbs(true);
            result.add(messageObject);
        } catch (Throwable ignore) {
            result.clear();
        }
        return result;
    }

    // ---------------------------------------------------------------- events

    /** The ad was really on screen. Called by ChatActivity instead of Telegram's view request. */
    public static void onView(MessageObject message) {
        int id = adIdOf(message);
        if (id < 0) {
            return;
        }
        ParadoksConfig.adSeenIncrement(id, today());
        sendEvent(id, "view");
    }

    public static void onClick(MessageObject message) {
        int id = adIdOf(message);
        if (id >= 0) {
            sendEvent(id, "click");
        }
    }

    private static void sendEvent(int adId, String kind) {
        if (!isEnabled()) {
            return;
        }
        final String base = ParadoksConfig.adServerUrl();
        final String body = "{\"ad_id\":" + adId + ",\"kind\":\"" + kind + "\",\"install_id\":\""
                + ParadoksConfig.installId() + "\"}";
        Utilities.globalQueue.postRunnable(() -> {
            try {
                httpPost(base + "/event", body);
            } catch (Throwable ignore) {
            }
        });
    }

    // ---------------------------------------------------------------- http

    private static String httpGet(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) {
                throw new Exception("http " + c.getResponseCode());
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BODY) {
                    throw new Exception("too large");
                }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void httpPost(String address, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.close();
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private ParadoksAdsRemote() {
    }
}
