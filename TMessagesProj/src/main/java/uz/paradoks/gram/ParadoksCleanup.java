package uz.paradoks.gram;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

/** Keeps the folder of saved deleted media from growing forever (age and size limits). */
final class ParadoksCleanup {

    private static final class Entry {
        int account;
        long dialogKey;
        int mid;
        File file;
        long time;
        long size;
    }

    private ParadoksCleanup() {
    }

    /** Runs on the database thread. Only the media files go; texts and radar records stay. */
    static void run() {
        final int days = ParadoksConfig.mediaRetentionDays();
        final int maxMb = ParadoksConfig.mediaMaxMb();
        if (days <= 0 && maxMb <= 0) {
            return;
        }
        ParadoksDb.async(() -> {
            long now = System.currentTimeMillis();
            ArrayList<Entry> entries = new ArrayList<>();
            for (Object[] row : ParadoksDb.get().queryAllMedia()) {
                Entry entry = new Entry();
                entry.account = (Integer) row[0];
                entry.dialogKey = (Long) row[1];
                entry.mid = (Integer) row[2];
                entry.file = new File((String) row[3]);
                if (!entry.file.exists()) {
                    ParadoksDb.get().deleteMedia(entry.account, entry.dialogKey, entry.mid);
                    continue;
                }
                entry.time = entry.file.lastModified();
                entry.size = entry.file.length();
                entries.add(entry);
            }
            if (days > 0) {
                long limit = now - days * 24L * 3600L * 1000L;
                for (int i = entries.size() - 1; i >= 0; i--) {
                    if (entries.get(i).time < limit) {
                        remove(entries.remove(i));
                    }
                }
            }
            if (maxMb > 0) {
                Collections.sort(entries, (a, b) -> Long.compare(a.time, b.time));
                long total = 0;
                for (Entry entry : entries) {
                    total += entry.size;
                }
                long max = maxMb * 1024L * 1024L;
                for (int i = 0; i < entries.size() && total > max; i++) {
                    total -= entries.get(i).size;
                    remove(entries.get(i));
                }
            }
        });
    }

    private static void remove(Entry entry) {
        try {
            entry.file.delete();
        } catch (Throwable ignore) {
        }
        ParadoksDb.get().deleteMedia(entry.account, entry.dialogKey, entry.mid);
    }

    /** Total size of the saved-media folder in bytes. */
    static long usedBytes() {
        long total = 0;
        try {
            File[] files = ParadoksMedia.baseDir().listFiles();
            if (files != null) {
                for (File file : files) {
                    total += file.length();
                }
            }
        } catch (Throwable ignore) {
        }
        return total;
    }
}
