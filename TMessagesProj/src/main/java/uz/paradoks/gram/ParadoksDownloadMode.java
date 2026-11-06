package uz.paradoks.gram;

import android.os.SystemClock;

/**
 * Download mode: 0 = standard, 1 = weak internet (smaller parts, more of them in flight),
 * 2 = auto (follows the measured speed; applied to downloads that start afterwards).
 */
public final class ParadoksDownloadMode {

    public static final int STANDARD = 0;
    public static final int WEAK = 1;
    public static final int AUTO = 2;

    private static final long WINDOW_MS = 4000;
    private static final double WEAK_BELOW = 300 * 1024; // bytes per second
    private static final double OK_ABOVE = 600 * 1024;

    private static final Object LOCK = new Object();
    private static final long[] times = new long[256];
    private static final int[] sizes = new int[256];
    private static int head;
    private static int count;
    private static boolean autoWeak;
    private static long autoChecked;

    private ParadoksDownloadMode() {
    }

    public static void note(int bytes) {
        synchronized (LOCK) {
            int i = (head + count) % times.length;
            if (count == times.length) {
                head = (head + 1) % times.length;
                i = (head + count - 1) % times.length;
            } else {
                count++;
            }
            times[i] = SystemClock.elapsedRealtime();
            sizes[i] = bytes;
        }
    }

    /** Bytes per second over the last few seconds, 0 if nothing is downloading. */
    public static double speed() {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            while (count > 0 && now - times[head] > WINDOW_MS) {
                head = (head + 1) % times.length;
                count--;
            }
            if (count == 0) {
                return 0;
            }
            long sum = 0;
            for (int k = 0; k < count; k++) {
                sum += sizes[(head + k) % times.length];
            }
            return sum * 1000.0 / WINDOW_MS;
        }
    }

    /** True when downloads should use smaller parts. */
    public static boolean weak() {
        int mode = ParadoksConfig.downloadMode();
        if (mode == WEAK) {
            return true;
        }
        if (mode != AUTO) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (now - autoChecked < 3000) {
                return autoWeak;
            }
            autoChecked = now;
        }
        double s = speed();
        synchronized (LOCK) {
            if (s > 0 && s < WEAK_BELOW) {
                autoWeak = true;
            } else if (s > OK_ABOVE) {
                autoWeak = false;
            }
            return autoWeak;
        }
    }
}
