package uz.paradoks.gram;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Developer tools for mini-apps: bridge log (what the mini-app and Telegram say to each other),
 * initData viewer, console, network log, data cleaner, theme and viewport simulator, DevTools switch.
 */
public final class ParadoksWebDev {

    /** Menu id used in the mini-app menus. */
    public static final int MENU_ID = 9001;
    public static final int MENU_COPY_ID = 9002;
    public static final String MENU_COPY_TEXT = "Mini-appni klonlash (ZIP)";

    private static final int MAX = 500;
    private static final int MAX_TEXT = 3000;
    private static final ArrayDeque<String> BRIDGE = new ArrayDeque<>();
    private static final ArrayDeque<String> CONSOLE = new ArrayDeque<>();
    private static final ArrayDeque<String> NETWORK = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile String url = "";
    private static WeakReference<WebView> lastView = new WeakReference<>(null);

    private static void add(ArrayDeque<String> list, String line) {
        if (line.length() > MAX_TEXT) {
            line = line.substring(0, MAX_TEXT) + "…";
        }
        String stamped = TIME.format(new Date()) + "  " + line;
        synchronized (list) {
            list.addLast(stamped);
            while (list.size() > MAX) {
                list.removeFirst();
            }
        }
    }

    private static String dump(ArrayDeque<String> list, String empty) {
        synchronized (list) {
            if (list.isEmpty()) {
                return empty;
            }
            StringBuilder builder = new StringBuilder();
            for (String line : list) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        }
    }

    /** Mini-app -> Telegram. */
    public static void bridgeIn(String type, String data) {
        add(BRIDGE, "← app  " + type + (data == null || data.isEmpty() || data.equals("{}") ? "" : "  " + data));
    }

    /** Telegram -> mini-app. */
    public static void bridgeOut(String event, String data) {
        add(BRIDGE, "→ app  " + event + (data == null || data.isEmpty() || data.equals("{}") ? "" : "  " + data));
    }

    public static void console(String level, String message, String source, int line) {
        add(CONSOLE, "[" + level + "] " + message + (source == null || source.isEmpty() ? "" : "  (" + source + ":" + line + ")"));
    }

    public static void network(String method, String requestUrl) {
        add(NETWORK, (method == null ? "GET" : method) + "  " + requestUrl);
    }

    public static void setPage(String pageUrl, WebView view) {
        if (pageUrl != null) {
            url = pageUrl;
        }
        if (view != null) {
            lastView = new WeakReference<>(view);
        }
    }

    // ---- views ----

    private static String initDataText() {
        String current = url;
        if (current == null || current.isEmpty()) {
            return "Mini-app hali ochilmagan.";
        }
        try {
            Uri uri = Uri.parse(current);
            String fragment = uri.getEncodedFragment();
            StringBuilder out = new StringBuilder();
            out.append("Manzil: ").append(uri.buildUpon().fragment(null).build()).append("\n\n");
            if (fragment == null || !fragment.contains("tgWebAppData=")) {
                return out.append("initData topilmadi (manzilda #tgWebAppData= yo'q).").toString();
            }
            Uri parsed = Uri.parse("http://x/?" + fragment);
            String raw = parsed.getQueryParameter("tgWebAppData");
            out.append("initData (raw):\n").append(raw).append("\n\n");
            if (raw != null) {
                Uri data = Uri.parse("http://x/?" + raw);
                Set<String> names = data.getQueryParameterNames();
                for (String name : names) {
                    String value = data.getQueryParameter(name);
                    if (value != null && value.startsWith("{")) {
                        try {
                            value = "\n" + new JSONObject(value).toString(2);
                        } catch (Throwable ignore) {
                        }
                    }
                    out.append(name).append(" = ").append(value).append('\n');
                }
            }
            String version = parsed.getQueryParameter("tgWebAppVersion");
            if (version != null) {
                out.append("\ntgWebAppVersion = ").append(version).append('\n');
            }
            String theme = parsed.getQueryParameter("tgWebAppThemeParams");
            if (theme != null) {
                try {
                    theme = new JSONObject(theme).toString(2);
                } catch (Throwable ignore) {
                }
                out.append("\ntgWebAppThemeParams =\n").append(theme).append('\n');
            }
            return out.toString();
        } catch (Throwable e) {
            return "initData o'qilmadi: " + e;
        }
    }

    private static final String LIGHT_THEME = "{\"theme_params\":{\"bg_color\":\"#ffffff\",\"text_color\":\"#000000\",\"hint_color\":\"#707579\","
            + "\"link_color\":\"#3390ec\",\"button_color\":\"#3390ec\",\"button_text_color\":\"#ffffff\",\"secondary_bg_color\":\"#f4f4f5\","
            + "\"header_bg_color\":\"#ffffff\",\"accent_text_color\":\"#3390ec\",\"section_bg_color\":\"#ffffff\","
            + "\"section_header_text_color\":\"#3390ec\",\"subtitle_text_color\":\"#707579\",\"destructive_text_color\":\"#e53935\","
            + "\"bottom_bar_bg_color\":\"#ffffff\",\"section_separator_color\":\"#e7e8ec\"}}";
    private static final String DARK_THEME = "{\"theme_params\":{\"bg_color\":\"#212121\",\"text_color\":\"#ffffff\",\"hint_color\":\"#aaaaaa\","
            + "\"link_color\":\"#8774e1\",\"button_color\":\"#8774e1\",\"button_text_color\":\"#ffffff\",\"secondary_bg_color\":\"#181818\","
            + "\"header_bg_color\":\"#212121\",\"accent_text_color\":\"#8774e1\",\"section_bg_color\":\"#212121\","
            + "\"section_header_text_color\":\"#8774e1\",\"subtitle_text_color\":\"#aaaaaa\",\"destructive_text_color\":\"#e53935\","
            + "\"bottom_bar_bg_color\":\"#212121\",\"section_separator_color\":\"#0f0f0f\"}}";

    private static void sendEvent(Context context, String event, String json) {
        WebView view = lastView.get();
        if (view == null) {
            toast(context, "Ochiq mini-app topilmadi");
            return;
        }
        view.evaluateJavascript("window.Telegram && window.Telegram.WebView && window.Telegram.WebView.receiveEvent('" + event + "', " + json + ");", null);
        bridgeOut(event + " (qo'lda)", json);
        toast(context, "Yuborildi: " + event);
    }

    private static void clearData(Context context) {
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            WebView view = lastView.get();
            if (view != null) {
                view.clearCache(true);
                view.clearHistory();
            }
            toast(context, "Cookie, localStorage va kesh tozalandi");
        } catch (Throwable e) {
            toast(context, "Tozalab bo'lmadi: " + e);
        }
    }

    private static void reload(Context context) {
        WebView view = lastView.get();
        if (view == null) {
            toast(context, "Ochiq mini-app topilmadi");
            return;
        }
        view.reload();
    }

    /** Saves the open mini-app's public files into a ZIP and sends it to Saved Messages. */
    public static void copyPage(Context context) {
        if (url == null || url.isEmpty()) {
            toast(context, "Mini-app ochilmagan");
            return;
        }
        toast(context, "Sahifa saqlanmoqda, tayyor bo'lgach Saved Messages ga yuboriladi...");
        ParadoksWebCopy.start(url, new ParadoksWebCopy.Callback() {
            @Override
            public void log(String line) {
            }

            @Override
            public void done(java.io.File zip) {
                if (zip == null) {
                    toast(context, "Sahifani saqlab bo'lmadi");
                    return;
                }
                try {
                    int account = org.telegram.messenger.UserConfig.selectedAccount;
                    org.telegram.messenger.AccountInstance instance = org.telegram.messenger.AccountInstance.getInstance(account);
                    long self = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
                    java.util.ArrayList<String> paths = new java.util.ArrayList<>();
                    paths.add(zip.getAbsolutePath());
                    org.telegram.messenger.SendMessagesHelper.prepareSendingDocuments(instance, paths, paths, null,
                            "Mini-app nusxasi: " + zip.getName(), "application/zip", self, null, null, null, null, null, true, 0, null, null, 0, false, 0);
                    toast(context, "Saved Messages ga yuborilmoqda");
                } catch (Exception e) {
                    toast(context, "ZIP saqlandi: " + zip.getAbsolutePath());
                }
            }
        });
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    private static void copy(Context context, String text) {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("ParadoksGram", text));
            toast(context, "Nusxalandi");
        }
    }

    private static final int TAB_BRIDGE = 0, TAB_INIT = 1, TAB_CONSOLE = 2, TAB_NETWORK = 3, TAB_ENV = 4;

    /** Opens the developer panel. */
    public static void show(Context context) {
        if (context == null) {
            return;
        }
        final Dialog dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1621);
        final int pad = AndroidUtilities.dp(8);
        root.setPadding(pad, AndroidUtilities.statusBarHeight + pad, pad, pad);

        TextView title = new TextView(context);
        title.setText("Mini-app dasturchi paneli");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(pad, pad, pad, pad);
        root.addView(title);

        final TextView content = new TextView(context);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        content.setTextColor(0xFFE8EEF5);
        content.setTextIsSelectable(true);
        content.setPadding(pad, pad, pad, pad);
        final ScrollView scroll = new ScrollView(context);
        scroll.addView(content);

        final LinearLayout envPanel = new LinearLayout(context);
        envPanel.setOrientation(LinearLayout.VERTICAL);

        final int[] tab = {TAB_BRIDGE};
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] refresh = new Runnable[1];
        final Runnable render = () -> {
            boolean env = tab[0] == TAB_ENV;
            envPanel.setVisibility(env ? android.view.View.VISIBLE : android.view.View.GONE);
            scroll.setVisibility(env ? android.view.View.GONE : android.view.View.VISIBLE);
            if (env) {
                return;
            }
            String text;
            switch (tab[0]) {
                case TAB_INIT:
                    text = initDataText();
                    break;
                case TAB_CONSOLE:
                    text = dump(CONSOLE, "Konsol bo'sh. Mini-app console.log chaqirsa, shu yerda ko'rinadi.");
                    break;
                case TAB_NETWORK:
                    text = dump(NETWORK, "So'rovlar yo'q. Mini-appni oching.");
                    break;
                default:
                    text = dump(BRIDGE, "Hali hech narsa yo'q. Mini-appni oching.\n← app: mini-app Telegram'ga yuborgan;\n→ app: Telegram mini-appga yuborgan.");
                    break;
            }
            if (!text.contentEquals(content.getText())) {
                content.setText(text);
                if (tab[0] != TAB_INIT) {
                    scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
                }
            }
        };
        refresh[0] = () -> {
            render.run();
            handler.postDelayed(refresh[0], 1000);
        };

        HorizontalScrollView tabsScroll = new HorizontalScrollView(context);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Bridge", "initData", "Konsol", "Tarmoq", "Muhit"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button button = new Button(context);
            button.setText(names[i]);
            button.setAllCaps(false);
            button.setOnClickListener(v -> {
                tab[0] = index;
                content.setText("");
                render.run();
            });
            tabs.addView(button);
        }
        tabsScroll.addView(tabs);
        root.addView(tabsScroll);

        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // "Muhit": theme / viewport simulator, data cleaner, DevTools.
        TextView envNote = new TextView(context);
        envNote.setTextColor(0xFFAAB8C6);
        envNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        envNote.setPadding(pad, pad, pad, pad);
        envNote.setText("Ochiq mini-appga qo'lda hodisa yuborish va tozalash. DevTools yoqilsa, kompyuterda Chrome'da chrome://inspect orqali "
                + "(USB bilan ulab) mini-appning konsoli, tarmog'i va elementlarini ko'rasiz.");
        envPanel.addView(envNote);
        addEnvButton(context, envPanel, "Tema: yorug'", () -> sendEvent(context, "theme_changed", LIGHT_THEME));
        addEnvButton(context, envPanel, "Tema: tungi", () -> sendEvent(context, "theme_changed", DARK_THEME));
        addEnvButton(context, envPanel, "Viewport: kichik (balandlik 400)", () ->
                sendEvent(context, "viewport_changed", "{\"height\":" + 400 + ",\"is_state_stable\":true,\"is_expanded\":false}"));
        addEnvButton(context, envPanel, "Viewport: to'liq (balandlik 800)", () ->
                sendEvent(context, "viewport_changed", "{\"height\":800,\"is_state_stable\":true,\"is_expanded\":true}"));
        addEnvButton(context, envPanel, "Sahifani qayta yuklash", () -> reload(context));
        addEnvButton(context, envPanel, "Sahifani saqlash (ZIP, Saved Messages ga)", () -> copyPage(context));
        addEnvButton(context, envPanel, "Cookie, localStorage va keshni tozalash", () -> clearData(context));
        final Button devtools = new Button(context);
        devtools.setAllCaps(false);
        final Runnable devtoolsText = () -> devtools.setText("WebView DevTools: " + (SharedConfig.debugWebView ? "yoqilgan" : "o'chiq") + " (bosib almashtiring)");
        devtoolsText.run();
        devtools.setOnClickListener(v -> {
            SharedConfig.toggleDebugWebView();
            devtoolsText.run();
        });
        envPanel.addView(devtools);
        envPanel.setVisibility(android.view.View.GONE);
        root.addView(envPanel);

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.END);
        Button clear = new Button(context);
        clear.setText("Jurnalni tozalash");
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> {
            synchronized (BRIDGE) {
                BRIDGE.clear();
            }
            synchronized (CONSOLE) {
                CONSOLE.clear();
            }
            synchronized (NETWORK) {
                NETWORK.clear();
            }
            content.setText("");
            render.run();
        });
        Button copyButton = new Button(context);
        copyButton.setText("Nusxalash");
        copyButton.setAllCaps(false);
        copyButton.setOnClickListener(v -> copy(context, content.getText().toString()));
        Button close = new Button(context);
        close.setText("Yopish");
        close.setAllCaps(false);
        close.setOnClickListener(v -> dialog.dismiss());
        bottom.addView(clear);
        bottom.addView(copyButton);
        bottom.addView(close);
        root.addView(bottom);

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
        dialog.show();
        refresh[0].run();
    }

    private static void addEnvButton(Context context, LinearLayout parent, String text, Runnable action) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
    }

    private ParadoksWebDev() {
    }
}
