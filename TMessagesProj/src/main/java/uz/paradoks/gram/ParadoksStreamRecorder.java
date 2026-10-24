package uz.paradoks.gram;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records live streams in the background. The stream arrives as small segments (about one second each) that
 * Telegram's client downloads one by one; every segment is also written to disk. When the stream ends the
 * segments are joined into one video and sent to Saved Messages.
 *
 * Chats that have "restrict saving content" turned on are never recorded.
 */
public final class ParadoksStreamRecorder {

    private static final long IDLE_MS = 60_000;
    private static final long KEEP_RAW_MS = 3L * 24 * 3600 * 1000;

    private static final class Part {
        long ts;
        long duration;
        int channel;
        int quality;
        File file;
        long size;
    }

    private static final class Session {
        int account;
        long callId;
        String title;
        File dir;
        long lastPartTime;
        final ArrayList<Part> parts = new ArrayList<>();
        final TreeMap<String, Integer> formats = new TreeMap<>();
        long bytes;
    }

    private static final Map<String, Session> sessions = new HashMap<>();
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static boolean tickerRunning;

    private ParadoksStreamRecorder() {
    }

    /** Called for every downloaded live-stream segment (network thread). */
    public static void onPart(int account, TLRPC.Chat chat, long callId, long timestamp, long duration, int videoChannel, int quality, ByteBuffer buffer, int size) {
        try {
            if (!ParadoksConfig.recordStreams() || buffer == null || size <= 0) {
                return;
            }
            if (chat != null && chat.noforwards) {
                return; // protected content is never recorded
            }
            final byte[] data = new byte[size];
            ByteBuffer copy = buffer.duplicate();
            copy.position(0);
            copy.get(data, 0, Math.min(size, copy.remaining()));
            final String title = chat != null && chat.title != null ? chat.title : "Efir";
            io.execute(() -> save(account, title, callId, timestamp, duration, videoChannel, quality, data));
        } catch (Throwable ignore) {
        }
    }

    private static void save(int account, String title, long callId, long timestamp, long duration, int channel, int quality, byte[] data) {
        try {
            String key = account + "_" + callId;
            Session session;
            synchronized (sessions) {
                session = sessions.get(key);
                if (session == null) {
                    session = new Session();
                    session.account = account;
                    session.callId = callId;
                    session.title = title;
                    session.dir = new File(root(), key);
                    session.dir.mkdirs();
                    sessions.put(key, session);
                    startTicker();
                }
            }
            String name = (channel == 0 ? "a_" : "v" + channel + "_q" + quality + "_") + timestamp + ".part";
            File file = new File(session.dir, name);
            if (file.exists()) {
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
            }
            Part part = new Part();
            part.ts = timestamp;
            part.duration = duration;
            part.channel = channel;
            part.quality = quality;
            part.file = file;
            part.size = data.length;
            String format = (channel == 0 ? "audio" : "video") + ": " + sniff(data);
            synchronized (sessions) {
                session.parts.add(part);
                session.lastPartTime = System.currentTimeMillis();
                session.bytes += data.length;
                Integer count = session.formats.get(format);
                session.formats.put(format, count == null ? 1 : count + 1);
            }
        } catch (Throwable ignore) {
        }
    }

    private static File root() {
        File base = ApplicationLoader.applicationContext.getExternalFilesDir(null);
        if (base == null) {
            base = ApplicationLoader.applicationContext.getFilesDir();
        }
        File dir = new File(base, "paradoks_streams");
        dir.mkdirs();
        return dir;
    }

    /** Container guess from the first bytes of a segment. */
    private static String sniff(byte[] d) {
        try {
            if (d.length >= 12) {
                String four = new String(d, 4, 4, "ISO-8859-1");
                if (four.equals("ftyp") || four.equals("styp") || four.equals("moof") || four.equals("moov") || four.equals("mdat")) {
                    return "mp4 (" + four + ")";
                }
            }
            if (d.length >= 4) {
                if (d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') {
                    return "ogg";
                }
                if ((d[0] & 0xff) == 0x1A && (d[1] & 0xff) == 0x45 && (d[2] & 0xff) == 0xDF && (d[3] & 0xff) == 0xA3) {
                    return "webm/mkv";
                }
            }
            StringBuilder hex = new StringBuilder("noma'lum [");
            for (int i = 0; i < Math.min(8, d.length); i++) {
                hex.append(String.format(Locale.US, "%02x", d[i] & 0xff));
            }
            return hex.append(']').toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ end of stream

    private static void startTicker() {
        if (tickerRunning) {
            return;
        }
        tickerRunning = true;
        AndroidUtilities.runOnUIThread(ParadoksStreamRecorder::tick, 15000);
    }

    private static void tick() {
        ArrayList<Session> finished = new ArrayList<>();
        synchronized (sessions) {
            long now = System.currentTimeMillis();
            for (Session session : new ArrayList<>(sessions.values())) {
                if (session.lastPartTime != 0 && now - session.lastPartTime > IDLE_MS) {
                    sessions.remove(session.account + "_" + session.callId);
                    finished.add(session);
                }
            }
            tickerRunning = !sessions.isEmpty();
        }
        for (Session session : finished) {
            io.execute(() -> finish(session));
        }
        if (tickerRunning) {
            AndroidUtilities.runOnUIThread(ParadoksStreamRecorder::tick, 15000);
        }
    }

    /** The call screen was closed. */
    public static void finishAll() {
        ArrayList<Session> finished;
        synchronized (sessions) {
            finished = new ArrayList<>(sessions.values());
            sessions.clear();
            tickerRunning = false;
        }
        for (Session session : finished) {
            io.execute(() -> finish(session));
        }
    }

    /** Removes raw segments that were kept after a failed attempt, older than three days. */
    static void cleanupOld() {
        io.execute(() -> {
            try {
                File[] dirs = root().listFiles();
                if (dirs == null) {
                    return;
                }
                long limit = System.currentTimeMillis() - KEEP_RAW_MS;
                for (File dir : dirs) {
                    if (dir.lastModified() < limit) {
                        deleteRecursive(dir);
                    }
                }
            } catch (Throwable ignore) {
            }
        });
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    private static void finish(Session session) {
        final ArrayList<Part> parts;
        synchronized (sessions) {
            parts = new ArrayList<>(session.parts);
        }
        if (parts.size() < 3) {
            deleteRecursive(session.dir);
            return;
        }
        String result = null;
        File output = null;
        long seconds = 0;
        try {
            Collections.sort(parts, (a, b) -> Long.compare(a.ts, b.ts));
            ArrayList<Part> audio = new ArrayList<>();
            // one (channel, quality) pair for the whole video: the one with most segments
            HashMap<String, ArrayList<Part>> videoGroups = new HashMap<>();
            for (Part part : parts) {
                if (part.channel == 0) {
                    audio.add(part);
                } else {
                    String key = part.channel + "_" + part.quality;
                    ArrayList<Part> group = videoGroups.get(key);
                    if (group == null) {
                        group = new ArrayList<>();
                        videoGroups.put(key, group);
                    }
                    group.add(part);
                }
            }
            ArrayList<Part> video = new ArrayList<>();
            for (ArrayList<Part> group : videoGroups.values()) {
                if (group.size() > video.size()) {
                    video = group;
                }
            }
            long first = Long.MAX_VALUE;
            long last = 0;
            for (Part part : parts) {
                first = Math.min(first, part.ts);
                last = Math.max(last, part.ts + Math.max(part.duration, 1000));
            }
            seconds = Math.max(1, (last - first) / 1000);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date(System.currentTimeMillis()));
            String safeTitle = session.title.replaceAll("[^A-Za-z0-9._-]", "_");
            File outDir = new File(root(), "out");
            outDir.mkdirs();
            output = mux(audio, video, new File(outDir, "Efir_" + safeTitle + "_" + stamp), first);
        } catch (Throwable t) {
            result = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        }

        final File finalOutput = output;
        final String failure = result;
        final long duration = seconds;
        final String formats = session.formats.toString();
        final long size = session.bytes;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                AccountInstance instance = AccountInstance.getInstance(session.account);
                long self = UserConfig.getInstance(session.account).getClientUserId();
                if (finalOutput != null && finalOutput.exists() && finalOutput.length() > 0) {
                    ArrayList<String> paths = new ArrayList<>();
                    paths.add(finalOutput.getAbsolutePath());
                    String caption = "📡 Efir yozuvi: " + session.title + "\n" + (duration / 60) + " daqiqa " + (duration % 60) + " soniya";
                    String mime = finalOutput.getName().endsWith(".webm") ? "video/webm" : "video/mp4";
                    SendMessagesHelper.prepareSendingDocuments(instance, paths, paths, null, caption, mime, self, null, null, null, null, null, true, 0, null, null, 0, false, 0);
                    io.execute(() -> deleteRecursive(session.dir)); // raw segments are no longer needed
                } else {
                    SendMessagesHelper.prepareSendingText(instance, "📡 Efir yozib olindi, lekin bitta videoga birlashtirib bo'lmadi.\n"
                            + "Chat: " + session.title + "\n"
                            + "Bo'laklar: " + session.parts.size() + ", hajmi: " + AndroidUtilities.formatFileSize(size) + "\n"
                            + "Format: " + formats + "\n"
                            + "Sabab: " + failure + "\n"
                            + "Bu hisobotni dasturchiga yuboring: formatga qarab birlashtirishni to'g'rilaymiz. Bo'laklar telefonda saqlanib turibdi (3 kun).", self, true, 0, 0, 0);
                }
            } catch (Throwable ignore) {
            }
        });
    }

    // ------------------------------------------------------------------ joining the segments

    private static File mux(ArrayList<Part> audio, ArrayList<Part> video, File outputBase, long base) throws Exception {
        MediaFormat audioFormat = firstFormat(audio, "audio/");
        MediaFormat videoFormat = firstFormat(video, "video/");
        if (audioFormat == null && videoFormat == null) {
            throw new Exception("bo'laklarni o'qib bo'lmadi (MediaExtractor format topmadi)");
        }
        String audioMime = audioFormat != null ? audioFormat.getString(MediaFormat.KEY_MIME) : "";
        String videoMime = videoFormat != null ? videoFormat.getString(MediaFormat.KEY_MIME) : "";
        boolean webmFirst = videoMime.equals("video/x-vnd.on2.vp8") || videoMime.equals("video/x-vnd.on2.vp9")
                || (videoMime.isEmpty() && (audioMime.equals("audio/opus") || audioMime.equals("audio/vorbis")));
        int[] containers = webmFirst
                ? new int[]{MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4}
                : new int[]{MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM};
        Exception last = null;
        for (int container : containers) {
            File out = new File(outputBase.getAbsolutePath() + (container == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM ? ".webm" : ".mp4"));
            MediaMuxer muxer = null;
            try {
                muxer = new MediaMuxer(out.getAbsolutePath(), container);
                int audioTrack = audioFormat != null ? muxer.addTrack(audioFormat) : -1;
                int videoTrack = videoFormat != null ? muxer.addTrack(videoFormat) : -1;
                muxer.start();
                if (audioTrack >= 0) {
                    writeParts(muxer, audio, audioTrack, "audio/", base);
                }
                if (videoTrack >= 0) {
                    writeParts(muxer, video, videoTrack, "video/", base);
                }
                muxer.stop();
                muxer.release();
                return out;
            } catch (Exception e) {
                last = e;
                try {
                    if (muxer != null) {
                        muxer.release();
                    }
                } catch (Throwable ignore) {
                }
                out.delete();
            }
        }
        throw last != null ? last : new Exception("birlashtirib bo'lmadi");
    }

    private static MediaFormat firstFormat(ArrayList<Part> parts, String prefix) {
        for (int i = 0; i < Math.min(parts.size(), 8); i++) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(parts.get(i).file.getAbsolutePath());
                for (int t = 0; t < extractor.getTrackCount(); t++) {
                    MediaFormat format = extractor.getTrackFormat(t);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith(prefix)) {
                        return format;
                    }
                }
            } catch (Throwable ignore) {
            } finally {
                extractor.release();
            }
        }
        return null;
    }

    private static void writeParts(MediaMuxer muxer, ArrayList<Part> parts, int track, String prefix, long base) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastPts = -1;
        for (Part part : parts) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(part.file.getAbsolutePath());
                int index = -1;
                for (int t = 0; t < extractor.getTrackCount(); t++) {
                    String mime = extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith(prefix)) {
                        index = t;
                        break;
                    }
                }
                if (index < 0) {
                    continue;
                }
                extractor.selectTrack(index);
                long firstSample = -1;
                while (true) {
                    buffer.clear();
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        break;
                    }
                    long time = extractor.getSampleTime();
                    if (firstSample < 0) {
                        firstSample = time;
                    }
                    long pts = (part.ts - base) * 1000L + (time - firstSample);
                    if (pts <= lastPts) {
                        pts = lastPts + 1; // timestamps of one track must grow
                    }
                    lastPts = pts;
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = pts;
                    info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    muxer.writeSampleData(track, buffer, info);
                    extractor.advance();
                }
            } catch (Throwable ignore) {
                // a damaged segment is skipped, the rest still gets joined
            } finally {
                extractor.release();
            }
        }
    }
}
