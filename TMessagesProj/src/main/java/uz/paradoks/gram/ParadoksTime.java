package uz.paradoks.gram;

import android.text.format.DateFormat;

import org.telegram.messenger.ApplicationLoader;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Clock time of a message with seconds (HH:mm:ss or h:mm:ss a). */
public final class ParadoksTime {

    private static SimpleDateFormat format;
    private static boolean format24;

    public static synchronized String clock(long millis) {
        boolean is24 = DateFormat.is24HourFormat(ApplicationLoader.applicationContext);
        if (format == null || format24 != is24) {
            format24 = is24;
            format = new SimpleDateFormat(is24 ? "HH:mm:ss" : "h:mm:ss a", Locale.US);
        }
        return format.format(new Date(millis));
    }

    private ParadoksTime() {
    }
}
