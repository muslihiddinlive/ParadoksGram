package uz.paradoks.gram;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.app.AlertDialog;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Builds a structured error report (device, time, place, likely causes, log) and sends it, after the user
 * agrees, to a Telegram relay (Cloudflare Worker "telegram-form-worker"). The bot token never lives in the app.
 *
 * The report contains no message texts, contacts or phone numbers, and no GPS position. "Place" means the place
 * inside the app (screen / stack frame) plus coarse region data (SIM/network country, time zone). The relay itself
 * adds the country and IP of the request.
 */
public final class ParadoksErrorReport {

    /** Address of the deployed relay worker, e.g. "https://telegram-form.<account>.workers.dev". Empty = sending disabled. */
    public static final String ENDPOINT = "https://buildnoticer.soglomhayotila.workers.dev/report";

    private static final int MAX_LOG_LINES = 400;
    private static final int MAX_LOG_CHARS = 120000;

    public static boolean isConfigured() {
        return ENDPOINT != null && !ENDPOINT.isEmpty();
    }

    /** Short summary that goes into the Telegram message. */
    public static String buildSummary(Context context, String kind, String details) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tur: ").append(kind).append('\n');
        sb.append("Qurilma: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        sb.append("Ilova: ").append(BuildVars.BUILD_VERSION_STRING).append(" (").append(context.getPackageName()).append(")\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        sb.append("Vaqt: ").append(f.format(new Date())).append(" [").append(TimeZone.getDefault().getID()).append("]\n");
        sb.append("Qayerda: ").append(where(details)).append('\n');
        sb.append("Hudud (taxminiy): ").append(region(context)).append('\n');
        sb.append("Tarmoq: ").append(network(context)).append('\n');
        sb.append("\nTaxminiy sabablar:\n");
        for (String cause : guessCauses(context, kind, details)) {
            sb.append("- ").append(cause).append('\n');
        }
        return sb.toString();
    }

    /** Sends the report in a background thread and tells the user how it went. */
    public static void sendAsync(final Activity activity, final String kind, final String details, final boolean includeAccount) {
        if (!isConfigured()) {
            Toast.makeText(activity, "Hisobot manzili sozlanmagan", Toast.LENGTH_LONG).show();
            return;
        }
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try {
                String summary = buildSummary(app, kind, details);
                String log = scrub(details + "\n\n===== LOGCAT =====\n" + readLogcat());
                ok = post(summary, log, includeAccount ? accountText() : "");
            } catch (Throwable ignore) {
            }
            final boolean result = ok;
            activity.runOnUiThread(() -> Toast.makeText(activity, result ? "Hisobot yuborildi" : "Yuborib bo'lmadi (internet?)", Toast.LENGTH_LONG).show());
        }, "paradoks-report").start();
    }

    /**
     * Asks the user what may be sent. Account data (ID, username, phone number) goes along only when the
     * user ticks the box; nothing is sent before "Yuborish" is pressed.
     */
    public static void confirmAndSend(final Activity activity, final String kind, final String details) {
        try {
            int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(pad, pad / 2, pad, 0);
            TextView text = new TextView(activity);
            text.setText("Yuboriladi: qurilma, vaqt (soniyagacha), xato joyi, taxminiy hudud, taxminiy sabablar va texnik log. "
                    + "Server shahar va provayder nomini o'zi qo'shadi.\n\nXabar matnlari va kontaktlar yuborilmaydi. GPS joylashuv ham yuborilmaydi.");
            layout.addView(text);
            final CheckBox box = new CheckBox(activity);
            box.setText("Akkaunt ma'lumotlarim ham yuborilsin (ID, username, telefon raqam)");
            box.setChecked(false);
            layout.addView(box);
            new AlertDialog.Builder(activity)
                    .setTitle("Hisobot yuborish")
                    .setView(layout)
                    .setPositiveButton("Yuborish", (d, w) -> sendAsync(activity, kind, details, box.isChecked()))
                    .setNegativeButton("Bekor qilish", null)
                    .show();
        } catch (Throwable ignore) {
        }
    }

    /** Account data of the current account, only used when the user agreed. */
    private static String accountText() {
        try {
            TLRPC.User user = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
            if (user == null) {
                return "";
            }
            String username = UserObject.getPublicUsername(user);
            StringBuilder sb = new StringBuilder();
            sb.append("ID: ").append(user.id).append('\n');
            sb.append("Ism: ").append(user.first_name == null ? "" : user.first_name).append(' ').append(user.last_name == null ? "" : user.last_name).append('\n');
            sb.append("Username: ").append(username == null || username.isEmpty() ? "-" : "@" + username).append('\n');
            sb.append("Telefon: ").append(user.phone == null || user.phone.isEmpty() ? "-" : "+" + user.phone);
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean post(String summary, String log, String account) throws Exception {
        String boundary = "----paradoks" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "name", "ParadoksGram Android");
        field(body, boundary, "contact", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        field(body, boundary, "message", summary.length() > 2500 ? summary.substring(0, 2500) : summary);
        if (!account.isEmpty()) {
            field(body, boundary, "user", account);
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"paradoks-log.txt\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(log.getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toByteArray());
        }
        int code = c.getResponseCode();
        c.disconnect();
        return code >= 200 && code < 300;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /** Place inside the app: exception line and the first stack frame, when the text is a stack trace. */
    private static String where(String details) {
        if (details == null) {
            return "noma'lum";
        }
        String head = null;
        String frame = null;
        for (String line : details.split("\n")) {
            String t = line.trim();
            if (head == null && (t.contains("Exception") || t.contains("Error"))) {
                head = t;
            }
            if (frame == null && t.startsWith("at ")) {
                frame = t;
            }
            if (head != null && frame != null) {
                break;
            }
        }
        if (head == null && frame == null) {
            return "ulanish holati (ekran: asosiy)";
        }
        return (head == null ? "" : head) + (frame == null ? "" : "  ->  " + frame);
    }

    private static String region(Context context) {
        String sim = "-", net = "-";
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                if (!tm.getSimCountryIso().isEmpty()) sim = tm.getSimCountryIso().toUpperCase(Locale.US);
                if (!tm.getNetworkCountryIso().isEmpty()) net = tm.getNetworkCountryIso().toUpperCase(Locale.US);
            }
        } catch (Throwable ignore) {
        }
        return "SIM=" + sim + ", tarmoq=" + net + ", vaqt mintaqasi=" + TimeZone.getDefault().getID() + ", til=" + Locale.getDefault();
    }

    private static String network(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps == null) {
                sb.append("internet yo'q");
            } else {
                sb.append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobil" : "boshqa");
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) sb.append(", VPN yoqilgan");
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) sb.append(", internet tasdiqlanmagan");
            }
        } catch (Throwable e) {
            sb.append("aniqlab bo'lmadi");
        }
        try {
            sb.append(SharedConfig.isProxyEnabled() ? ", proxy yoqilgan" : ", proxy yo'q");
        } catch (Throwable ignore) {
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) sb.append(", batareya tejash rejimi");
        } catch (Throwable ignore) {
        }
        return sb.toString();
    }

    private static ArrayList<String> guessCauses(Context context, String kind, String details) {
        ArrayList<String> out = new ArrayList<>();
        String d = details == null ? "" : details;
        String net = network(context);
        if (net.startsWith("internet yo'q")) out.add("Telefonda internet yo'q yoki tarmoq uzilgan");
        if (net.contains("internet tasdiqlanmagan")) out.add("Tarmoq ulangan, lekin internetga chiqmayapti (provayder yoki Wi-Fi muammosi)");
        if (net.contains("VPN")) out.add("VPN yoqilgan: u Telegram serverlariga yo'lni buzishi mumkin");
        if (net.contains("proxy yoqilgan")) out.add("Eski yoki ishlamaydigan proxy sozlangan bo'lishi mumkin");
        if (net.contains("batareya tejash")) out.add("Batareya tejash rejimi ilovani fonda cheklayapti");
        if (kind.contains("ulanish")) {
            out.add("Provayder Telegram IP manzillarini sekinlatgan yoki bloklagan bo'lishi mumkin (boshqa tarmoq bilan sinab ko'ring)");
            out.add("Telegram serverlarida vaqtincha nosozlik bo'lishi mumkin (rasmiy Telegram ham ulanmasa, shu)");
            out.add("Juda ko'p parallel ulanish: server vaqtincha cheklagan bo'lishi mumkin (yuklash sozlamalarini kamaytirib ko'ring)");
        }
        if (d.contains("OutOfMemoryError")) out.add("Xotira yetmadi: parallel yuklash yoki katta fayl");
        if (d.contains("UnsatisfiedLinkError")) out.add("Native kutubxona yuklanmadi (qurilma arxitekturasi yoki buzilgan o'rnatish)");
        if (d.contains("SocketTimeoutException") || d.contains("UnknownHostException") || d.contains("ConnectException")) out.add("Tarmoq/DNS muammosi yoki server javob bermayapti");
        if (d.contains("SecurityException")) out.add("Ruxsat yetishmayapti");
        if (d.contains("ForegroundServiceStartNotAllowedException") || d.contains("ForegroundServiceDidNotStart")) out.add("Android fondan xizmat ishga tushirishga ruxsat bermadi");
        if (d.contains("NullPointerException")) out.add("Dasturdagi xato: bo'sh qiymat ishlatilgan (kod xatosi)");
        if (d.contains("REASON_ANR") || d.contains("reason=6")) out.add("Ilova javob bermay qotib qoldi (ANR)");
        if (out.isEmpty()) out.add("Aniq sabab topilmadi, logni ko'ring");
        return out;
    }

    private static String readLogcat() {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", String.valueOf(MAX_LOG_LINES), "-v", "threadtime"});
            try (InputStream in = p.getInputStream(); BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < MAX_LOG_CHARS) {
                    sb.append(line).append('\n');
                }
            }
            p.destroy();
        } catch (Throwable e) {
            sb.append("logcat o'qib bo'lmadi: ").append(e);
        }
        return sb.toString();
    }

    /** Removes bot tokens and long digit strings (phone numbers, ids) from the log. */
    private static String scrub(String text) {
        return text.replaceAll("\\d{6,}:[A-Za-z0-9_-]{30,}", "<token>").replaceAll("\\+?\\d{9,15}", "<son>");
    }

    private ParadoksErrorReport() {
    }
}
