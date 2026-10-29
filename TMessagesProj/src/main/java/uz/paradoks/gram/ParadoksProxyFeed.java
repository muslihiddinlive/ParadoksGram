package uz.paradoks.gram;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Proxy list feed: downloads a list of proxy links (one per line) from an HTTPS address the user chose, adds them to
 * Telegram's own proxy list and, when the app stays on "Connecting...", checks them and switches to the fastest one.
 *
 * Supported lines: tg://proxy?server=..&amp;port=..&amp;secret=.. , https://t.me/proxy?.. , tg://socks?server=..&amp;port=..&amp;user=..&amp;pass=.. ,
 * https://t.me/socks?.. . Lines starting with # are comments. Telegram's built-in "proxy rotation" keeps working on top of this.
 * Everything is off until the user turns it on and sets the address; the feed only ever adds proxies, never removes the user's own.
 */
public final class ParadoksProxyFeed implements NotificationCenter.NotificationCenterDelegate {

    private static final long REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long STUCK_MS = 30_000;
    private static final long SWITCH_COOLDOWN_MS = 10 * 60 * 1000L;
    private static final int MAX_PROXIES = 30;
    private static final int MAX_BYTES = 256 * 1024;

    private static ParadoksProxyFeed instance;
    private static volatile boolean fetching;

    private long badSince;
    private long lastSwitch;
    private final Runnable check = this::onStuck;

    private ParadoksProxyFeed() {
    }

    /** Called once at app start. Cheap when the feature is off. */
    public static void start() {
        if (instance != null) {
            return;
        }
        instance = new ParadoksProxyFeed();
        NotificationCenter.getGlobalInstance().addObserver(instance, NotificationCenter.didUpdateConnectionState);
        AndroidUtilities.runOnUIThread(ParadoksProxyFeed::refresh, 20_000);
    }

    // ------------------------------------------------------------------ connection watcher

    private static boolean isBad(int state) {
        return state == ConnectionsManager.ConnectionStateConnecting
                || state == ConnectionsManager.ConnectionStateWaitingForNetwork
                || state == ConnectionsManager.ConnectionStateConnectingToProxy;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didUpdateConnectionState || !ParadoksConfig.proxyFeedEnabled()) {
            return;
        }
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (isBad(state)) {
            if (badSince == 0) {
                badSince = SystemClock.elapsedRealtime();
                AndroidUtilities.runOnUIThread(check, STUCK_MS);
            }
        } else {
            badSince = 0;
            AndroidUtilities.cancelRunOnUIThread(check);
        }
    }

    private void onStuck() {
        if (!ParadoksConfig.proxyFeedEnabled() || badSince == 0) {
            return;
        }
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        long now = SystemClock.elapsedRealtime();
        if (!isBad(state) || now - lastSwitch < SWITCH_COOLDOWN_MS) {
            return;
        }
        lastSwitch = now;
        // a stale list is a likely reason for failing: refresh it first, then test and switch
        refreshThen(ParadoksProxyFeed::connectToFastest);
    }

    // ------------------------------------------------------------------ download

    /** Downloads and merges the list when the feature is on and the list is older than six hours. */
    public static void refresh() {
        refreshThen(null);
    }

    private static void refreshThen(Runnable afterMerge) {
        if (!ParadoksConfig.proxyFeedEnabled()) {
            return;
        }
        final String url = ParadoksConfig.proxyFeedUrl();
        if (url.isEmpty()) {
            return;
        }
        long age = System.currentTimeMillis() - ParadoksConfig.proxyFeedLastFetch();
        if (afterMerge == null && age >= 0 && age < REFRESH_INTERVAL_MS) {
            return;
        }
        if (fetching) {
            return;
        }
        fetching = true;
        new Thread(() -> {
            List<ProxySettings> found = new ArrayList<>();
            String error = null;
            try {
                found = parseList(download(url));
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final List<ProxySettings> result = found;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                fetching = false;
                if (finalError != null) {
                    ParadoksConfig.setProxyFeedStatus("Xato: " + finalError);
                } else {
                    int added = merge(result);
                    ParadoksConfig.setProxyFeedLastFetch(System.currentTimeMillis());
                    ParadoksConfig.setProxyFeedStatus("Ro'yxatda " + result.size() + " ta proxy, yangi qo'shilgani: " + added);
                }
                if (afterMerge != null) {
                    afterMerge.run();
                }
            });
        }, "ParadoksProxyFeed").start();
    }

    private static String download(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("server javobi: " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_BYTES) {
                        throw new Exception("ro'yxat juda katta");
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    // ------------------------------------------------------------------ parsing

    static List<ProxySettings> parseList(String body) {
        List<ProxySettings> result = new ArrayList<>();
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            ProxySettings settings = parseLink(line);
            if (settings != null && settings.isValid() && !result.contains(settings)) {
                result.add(settings);
                if (result.size() >= MAX_PROXIES) {
                    break;
                }
            }
        }
        return result;
    }

    private static ProxySettings parseLink(String link) {
        try {
            Uri uri = Uri.parse(link);
            String scheme = uri.getScheme();
            String kind;
            if ("tg".equalsIgnoreCase(scheme)) {
                kind = uri.getHost();
            } else if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && ("t.me".equalsIgnoreCase(uri.getHost()) || "telegram.me".equalsIgnoreCase(uri.getHost()))) {
                String path = uri.getPath();
                kind = path == null ? null : path.replace("/", "");
            } else {
                return null;
            }
            if (kind == null) {
                return null;
            }
            String server = uri.getQueryParameter("server");
            String portText = uri.getQueryParameter("port");
            if (TextUtils.isEmpty(server) || TextUtils.isEmpty(portText)) {
                return null;
            }
            int port = Integer.parseInt(portText.trim());
            if (port <= 0 || port > 65535) {
                return null;
            }
            ProxySettings.Builder builder = ProxySettings.builder().setAddress(server.trim()).setPort(port);
            if ("proxy".equalsIgnoreCase(kind)) {
                String secret = uri.getQueryParameter("secret");
                if (TextUtils.isEmpty(secret)) {
                    return null;
                }
                return builder.setType(ProxySettings.Type.MTPROTO).setSecret(secret.trim()).build();
            } else if ("socks".equalsIgnoreCase(kind)) {
                String user = uri.getQueryParameter("user");
                String pass = uri.getQueryParameter("pass");
                return builder.setType(ProxySettings.Type.SOCKS5)
                        .setUser(user == null ? "" : user)
                        .setPassword(pass == null ? "" : pass).build();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** Adds the proxies Telegram does not know yet. Returns how many were new. Must run on the UI thread. */
    private static int merge(List<ProxySettings> list) {
        SharedConfig.loadProxyList();
        int before = SharedConfig.proxyList.size();
        for (ProxySettings settings : list) {
            SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
        }
        return Math.max(0, SharedConfig.proxyList.size() - before);
    }

    // ------------------------------------------------------------------ switching

    /** Tests every proxy in Telegram's list and switches to the one with the lowest ping. */
    private static void connectToFastest() {
        if (!ParadoksConfig.proxyFeedEnabled()) {
            return;
        }
        final List<SharedConfig.ProxyInfo> candidates = new ArrayList<>(SharedConfig.proxyList);
        if (candidates.isEmpty()) {
            return;
        }
        final int[] pending = {candidates.size()};
        final int account = UserConfig.selectedAccount;
        for (final SharedConfig.ProxyInfo info : candidates) {
            if (info.checking) {
                pending[0]--;
                continue;
            }
            info.checking = true;
            ConnectionsManager.getInstance(account).checkProxy(info.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                info.availableCheckTime = SystemClock.elapsedRealtime();
                info.checking = false;
                if (time == -1) {
                    info.available = false;
                    info.ping = 0;
                } else {
                    info.ping = time;
                    info.available = true;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, info);
                if (--pending[0] <= 0) {
                    switchToBest(candidates);
                }
            }));
        }
        if (pending[0] <= 0) {
            switchToBest(candidates);
        }
    }

    private static void switchToBest(List<SharedConfig.ProxyInfo> candidates) {
        SharedConfig.ProxyInfo best = null;
        for (SharedConfig.ProxyInfo info : candidates) {
            if (info.available && info.ping > 0 && (best == null || info.ping < best.ping)) {
                best = info;
            }
        }
        if (best == null) {
            ParadoksConfig.setProxyFeedStatus("Ishlaydigan proxy topilmadi");
            return;
        }
        if (SharedConfig.isProxyEnabled() && SharedConfig.currentProxy == best) {
            return;
        }
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        best.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.commit();
        SharedConfig.currentProxy = best;
        ConnectionsManager.setProxySettings(true, best.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        ParadoksConfig.setProxyFeedStatus("Ulandi: " + best.settings.getAddress() + ":" + best.settings.getPort() + " (" + best.ping + " ms)");
    }

    /** Settings button "Hozir yangilash": download, merge and switch to the fastest, whatever the age of the list. */
    public static void refreshNow() {
        lastSwitchReset();
        refreshThen(ParadoksProxyFeed::connectToFastest);
    }

    private static void lastSwitchReset() {
        if (instance != null) {
            instance.lastSwitch = 0;
        }
    }
}
