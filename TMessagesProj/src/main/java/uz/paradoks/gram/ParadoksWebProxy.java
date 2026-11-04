package uz.paradoks.gram;

import android.webkit.HttpAuthHandler;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

/**
 * Mini-apps and the in-app browser reach the internet through a proxy of the user's choice instead
 * of the device's own IP. Telegram's own connection is not touched. The proxy is fail-closed: when
 * it is switched on and does not answer, web pages do not load (they never fall back to the real IP).
 */
public final class ParadoksWebProxy {

    public static final class Parsed {
        public String scheme;
        public String host;
        public int port;
        public String user;
        public String pass;

        /** Proxy URL without credentials, as ProxyController wants it. */
        public String rule() {
            return scheme + "://" + host + ":" + port;
        }
    }

    /** Accepts [scheme://][user:pass@]host:port; scheme is http, https, socks4 or socks5 (default http). */
    public static Parsed parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty() || value.contains(" ")) {
            return null;
        }
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        try {
            java.net.URI uri = new java.net.URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("socks4") && !scheme.equals("socks5")) {
                return null;
            }
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535) {
                return null;
            }
            Parsed p = new Parsed();
            p.scheme = scheme;
            p.host = uri.getHost();
            p.port = uri.getPort();
            String info = uri.getUserInfo();
            if (info != null) {
                int colon = info.indexOf(':');
                p.user = colon >= 0 ? info.substring(0, colon) : info;
                p.pass = colon >= 0 ? info.substring(colon + 1) : "";
            }
            return p;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Switches the WebView proxy to the current settings. Safe to call any time. */
    public static void apply() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    return;
                }
                Parsed p = ParadoksConfig.webProxyEnabled() ? parse(ParadoksConfig.webProxy()) : null;
                ProxyController controller = ProxyController.getInstance();
                if (p == null) {
                    controller.clearProxyOverride(Runnable::run, () -> {
                    });
                    return;
                }
                ProxyConfig config = new ProxyConfig.Builder().addProxyRule(p.rule()).build();
                controller.setProxyOverride(config, Runnable::run, () -> {
                });
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Proxy login for web pages: answers the proxy's own authentication request. */
    public static boolean authorize(HttpAuthHandler handler, String host) {
        try {
            if (!ParadoksConfig.webProxyEnabled()) {
                return false;
            }
            Parsed p = parse(ParadoksConfig.webProxy());
            if (p == null || p.user == null || p.host == null || !p.host.equalsIgnoreCase(host)) {
                return false;
            }
            handler.proceed(p.user, p.pass == null ? "" : p.pass);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private ParadoksWebProxy() {
    }
}
