package org.telegram.messenger;

import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * ParadoksGram: port of the adaptive download balancing used by the Windows client
 * (Telegram/SourceFiles/storage/download_manager_mtproto.cpp).
 *
 * Per account and data center it keeps a list of download "sessions" (connections, DOWNLOAD_CONNECTION_TYPES[i]).
 * It starts with a few sessions and a small in-flight window per session. While requests keep succeeding the window
 * grows and sessions are added up to the maximum; slow requests (timeouts) shrink the number of sessions again.
 *
 * Bytes in flight are not tracked here: FileLoadOperation derives them from its live request lists, so a lost
 * callback can never leave the balancer stuck.
 */
public class ParadoksDownloadBalancer {

    public static final int PART = 128 * 1024;
    private static final int START_WAITED = 8 * PART;
    private static final int MAX_WAITED = 32 * PART;
    private static final int START_SESSIONS = 4;
    private static final long BAD_REQUEST_DURATION_MS = 8000;
    private static final long RETRY_ADD_SESSION_MS = 8000;
    private static final int RETRY_ADD_SESSION_SUCCESSES = 3;
    private static final int MAX_TRACKED_REMOVES = 64;
    private static final int MAX_TRACKED_SUCCESSES = RETRY_ADD_SESSION_SUCCESSES * MAX_TRACKED_REMOVES;
    private static final int REMOVE_SESSION_AFTER_TIMEOUTS = 4;

    private static class Session {
        int maxWaited = START_WAITED;
        int successes;
    }

    private static class Dc {
        final ArrayList<Session> sessions = new ArrayList<>();
        int timeouts;
        int removeIndex = -1;
        int removeTimes;
        long lastRemove;

        Dc() {
            for (int a = 0; a < START_SESSIONS; a++) {
                sessions.add(new Session());
            }
        }
    }

    private static final HashMap<Long, Dc> dcs = new HashMap<>();

    /** In-flight bytes of the session the last successful acquire() chose, including that new request. */
    public static long lastAmountAtStart;

    private static int maxSessions() {
        return ConnectionsManager.DOWNLOAD_CONNECTION_TYPES.length;
    }

    private static Dc get(int account, int dcId) {
        long key = ((long) account << 32) | (dcId & 0xffffffffL);
        Dc dc = dcs.get(key);
        if (dc == null) {
            dc = new Dc();
            dcs.put(key, dc);
        }
        return dc;
    }

    /**
     * Picks the session for the next part, or -1 when every session window is full.
     *
     * @param inFlight bytes currently waiting for an answer, per session index (length = max sessions)
     */
    public static synchronized int acquire(int account, int dcId, int partSize, long[] inFlight) {
        Dc dc = get(account, dcId);
        int best = -1;
        long bestValue = Long.MAX_VALUE;
        for (int a = 0; a < dc.sessions.size(); a++) {
            Session session = dc.sessions.get(a);
            long value = inFlight[a] < session.maxWaited ? inFlight[a] : MAX_WAITED;
            if (value < bestValue) {
                bestValue = value;
                best = a;
            }
        }
        if (best < 0) {
            return -1;
        }
        Session session = dc.sessions.get(best);
        if (inFlight[best] + partSize > session.maxWaited) {
            return -1;
        }
        lastAmountAtStart = inFlight[best] + partSize;
        return best;
    }

    public static synchronized void onSuccess(int account, int dcId, int index, long amountAtStart, long timeAtStart, int partSize) {
        Dc dc = get(account, dcId);
        if (index < 0 || index >= dc.sessions.size()) {
            return;
        }
        Session data = dc.sessions.get(index);
        long now = SystemClock.elapsedRealtime();
        boolean overloaded = timeAtStart <= dc.lastRemove || amountAtStart > data.maxWaited;
        long duration = now - timeAtStart;
        if (overloaded) {
            return;
        }
        if (duration >= BAD_REQUEST_DURATION_MS) {
            sessionTimedOut(dc);
            return;
        }
        if (amountAtStart == data.maxWaited && data.maxWaited < MAX_WAITED) {
            data.maxWaited = Math.min(data.maxWaited + partSize, MAX_WAITED);
        }
        data.successes = Math.min(data.successes + 1, MAX_TRACKED_SUCCESSES);
        int need = (dc.removeTimes + 1) * RETRY_ADD_SESSION_SUCCESSES;
        for (int a = 0; a < dc.sessions.size(); a++) {
            if (dc.sessions.get(a).successes < need) {
                return;
            }
        }
        for (int a = 0; a < dc.sessions.size(); a++) {
            dc.sessions.get(a).successes = 0;
        }
        if (dc.timeouts > 0) {
            dc.timeouts--;
            return;
        } else if (dc.sessions.size() >= maxSessions()) {
            return;
        }
        long delay = (dc.removeTimes + 1) * RETRY_ADD_SESSION_MS;
        if (dc.lastRemove != 0 && now < dc.lastRemove + delay) {
            return;
        }
        dc.sessions.add(new Session());
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("download balancer: dc " + dcId + " sessions -> " + dc.sessions.size());
        }
    }

    private static void sessionTimedOut(Dc dc) {
        for (int a = 0; a < dc.sessions.size(); a++) {
            dc.sessions.get(a).successes = 0;
        }
        if (dc.sessions.size() <= START_SESSIONS || ++dc.timeouts < REMOVE_SESSION_AFTER_TIMEOUTS) {
            return;
        }
        dc.timeouts = 0;
        int index = dc.sessions.size() - 1;
        if (dc.removeIndex == index) {
            dc.removeTimes = Math.min(dc.removeTimes + 1, MAX_TRACKED_REMOVES);
        } else {
            dc.removeIndex = index;
            dc.removeTimes = 1;
        }
        dc.sessions.remove(index);
        dc.lastRemove = SystemClock.elapsedRealtime();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("download balancer: session removed, now " + dc.sessions.size());
        }
    }
}
