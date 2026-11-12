package uz.paradoks.gram;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** Output formats of the music studio: MP3 (built-in encoder), WAV, M4A (AAC), OGG (Opus) and FLAC. */
public final class ParadoksAudioFormats {

    public static final int MP3 = 0, ORIGINAL = 1, WAV = 2, M4A = 3, OGG = 4, FLAC = 5;
    public static final int COUNT = 6;

    private ParadoksAudioFormats() {
    }

    /** True when this device can encode Opus into an .ogg file (Android 10+). */
    public static boolean oggSupported() {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if ("audio/opus".equalsIgnoreCase(t)) return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** File extension (with the dot) of a source file, lower case; ".mp3" when there is none. */
    public static String extOf(String path) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        int slash = path == null ? -1 : path.lastIndexOf('/');
        if (dot < 0 || dot < slash || path.length() - dot > 6) return ".mp3";
        return path.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** The concrete format that "original format" stands for when the audio has to be re-encoded. */
    public static int resolveOriginal(String ext) {
        switch (ext) {
            case ".wav":
            case ".wave":
            case ".aif":
            case ".aiff":
                return WAV;
            case ".flac":
                return FLAC;
            case ".m4a":
            case ".aac":
            case ".mp4":
            case ".3gp":
                return M4A;
            case ".ogg":
            case ".oga":
            case ".opus":
                return oggSupported() ? OGG : M4A;
            default:
                return MP3;
        }
    }

    public static String extFor(int format) {
        switch (format) {
            case WAV:
                return ".wav";
            case M4A:
                return ".m4a";
            case OGG:
                return ".ogg";
            case FLAC:
                return ".flac";
            default:
                return ".mp3";
        }
    }

    public static String mimeFor(int format) {
        switch (format) {
            case WAV:
                return "audio/wav";
            case M4A:
                return "audio/mp4";
            case OGG:
                return "audio/ogg";
            case FLAC:
                return "audio/flac";
            default:
                return "audio/mpeg";
        }
    }

    public static String nameFor(int format) {
        switch (format) {
            case WAV:
                return "WAV";
            case M4A:
                return "M4A";
            case OGG:
                return "OGG";
            case FLAC:
                return "FLAC";
            default:
                return "MP3";
        }
    }

    /** Whether the format is lossy and therefore has a bitrate. */
    public static boolean lossy(int format) {
        return format == MP3 || format == M4A || format == OGG;
    }

    /** Opens a sink for a concrete (already resolved) format. */
    public static ParadoksMusicDsp.Sink open(int format, File out, int rate, int kbps, String title) throws IOException {
        switch (format) {
            case WAV:
                return new ParadoksMusicDsp.WavSink(out, rate);
            case M4A:
                return new ParadoksMusicDsp.M4aSink(out, rate, kbps * 1000);
            case OGG:
                return new OggSink(out, rate, kbps * 1000);
            case FLAC:
                return new FlacSink(out, rate);
            default:
                return new ParadoksMp3Encoder.Sink(out, rate, kbps, title);
        }
    }

    // ------------------------------------------------------------------------------------------
    // OGG / Opus through MediaCodec + MediaMuxer (Android 10+)
    // ------------------------------------------------------------------------------------------

    static final class OggSink implements ParadoksMusicDsp.Sink {
        private final MediaCodec codec;
        private final MediaMuxer muxer;
        private final ParadoksResampler resampler;
        private final int rate;
        private int trackIndex = -1;
        private boolean muxerStarted;
        private long totalFrames;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        OggSink(File out, int srcRate, int bitrate) throws IOException {
            if (Build.VERSION.SDK_INT < 29) throw new IOException("OGG: Android 10+ kerak");
            this.rate = srcRate == 8000 || srcRate == 12000 || srcRate == 16000 || srcRate == 24000 || srcRate == 48000 ? srcRate : 48000;
            MediaFormat f = MediaFormat.createAudioFormat("audio/opus", rate, 2);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
            codec = MediaCodec.createEncoderByType("audio/opus");
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
            resampler = new ParadoksResampler(srcRate, rate, this::encode);
        }

        @Override
        public void write(short[] d, int frames) throws IOException {
            resampler.write(d, frames);
        }

        private void encode(short[] d, int frames) {
            int offset = 0;
            int total = frames * 2;
            while (offset < total) {
                int idx = codec.dequeueInputBuffer(10_000);
                if (idx < 0) {
                    drain(false);
                    continue;
                }
                ByteBuffer buf = codec.getInputBuffer(idx);
                buf.clear();
                buf.order(ByteOrder.LITTLE_ENDIAN);
                int n = Math.min(total - offset, buf.capacity() / 2);
                n &= ~1;
                for (int i = 0; i < n; i++) buf.putShort(d[offset + i]);
                codec.queueInputBuffer(idx, 0, n * 2, totalFrames * 1_000_000L / rate, 0);
                totalFrames += n / 2;
                offset += n;
                drain(false);
            }
        }

        @Override
        public void finish() throws IOException {
            resampler.flush();
            int idx;
            while ((idx = codec.dequeueInputBuffer(10_000)) < 0) {
                drain(false);
            }
            codec.queueInputBuffer(idx, 0, 0, totalFrames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
            codec.stop();
            codec.release();
            if (muxerStarted) muxer.stop();
            muxer.release();
        }

        private void drain(boolean untilEnd) {
            while (true) {
                int out = codec.dequeueOutputBuffer(info, untilEnd ? 10_000 : 0);
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEnd) return;
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (out >= 0) {
                    ByteBuffer data = codec.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && muxerStarted && data != null) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(trackIndex, data, info);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                }
            }
        }

        @Override
        public void abort() {
            try {
                codec.stop();
            } catch (Throwable ignore) {
            }
            try {
                codec.release();
            } catch (Throwable ignore) {
            }
            try {
                if (muxerStarted) muxer.stop();
            } catch (Throwable ignore) {
            }
            try {
                muxer.release();
            } catch (Throwable ignore) {
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // FLAC (lossless): fixed predictors + Rice coding, independent stereo channels
    // ------------------------------------------------------------------------------------------

    static final class FlacSink implements ParadoksMusicDsp.Sink {
        private static final int BLOCK = 4096;

        private final File file;
        private final BufferedOutputStream os;
        private final int rate;
        private final int[][] ch = new int[2][BLOCK];
        private int fill;
        private long frameNo, totalSamples;
        private final byte[] frameBuf = new byte[BLOCK * 2 * 4 + 1024];
        private final int[] resid = new int[BLOCK];
        private final int[] best = new int[BLOCK];

        FlacSink(File out, int sampleRate) throws IOException {
            this.file = out;
            this.rate = sampleRate;
            os = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024);
            os.write(streamInfoBlock(0));
        }

        private byte[] streamInfoBlock(long total) {
            ByteBuffer b = ByteBuffer.allocate(42).order(ByteOrder.BIG_ENDIAN);
            b.put((byte) 'f').put((byte) 'L').put((byte) 'a').put((byte) 'C');
            b.put((byte) 0x80).put((byte) 0).put((byte) 0).put((byte) 34);
            b.putShort((short) BLOCK).putShort((short) BLOCK);
            b.put(new byte[6]); // frame sizes unknown
            long v = ((long) rate << 44) | (1L << 41) | (15L << 36) | total;
            b.putLong(v);
            b.put(new byte[16]); // MD5 unknown
            return b.array();
        }

        @Override
        public void write(short[] d, int frames) throws IOException {
            for (int i = 0; i < frames; i++) {
                ch[0][fill] = d[i * 2];
                ch[1][fill] = d[i * 2 + 1];
                if (++fill == BLOCK) {
                    writeFrame(BLOCK);
                    fill = 0;
                }
            }
        }

        @Override
        public void finish() throws IOException {
            if (fill > 0) {
                writeFrame(fill);
                fill = 0;
            }
            os.close();
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(0);
                raf.write(streamInfoBlock(totalSamples));
            }
        }

        @Override
        public void abort() {
            try {
                os.close();
            } catch (IOException ignore) {
            }
        }

        private void writeFrame(int n) throws IOException {
            Bits w = new Bits(frameBuf);
            w.put(0xFFF8, 16);                  // sync + fixed block size
            w.put(n == BLOCK ? 12 : 7, 4);      // 4096, or "16 bit value at the end of the header"
            w.put(0, 4);                        // sample rate: from STREAMINFO
            w.put(1, 4);                        // two independent channels
            w.put(4, 3);                        // 16 bits per sample
            w.put(0, 1);
            utf8(w, frameNo);
            if (n != BLOCK) w.put(n - 1, 16);
            w.put(crc8(frameBuf, 0, w.bytePos()), 8);
            for (int c = 0; c < 2; c++) subframe(w, ch[c], n);
            w.align();
            int len = w.bytePos();
            int crc = crc16(frameBuf, 0, len);
            frameBuf[len] = (byte) (crc >> 8);
            frameBuf[len + 1] = (byte) crc;
            os.write(frameBuf, 0, len + 2);
            frameNo++;
            totalSamples += n;
        }

        private static void utf8(Bits w, long v) {
            if (v < 0x80) {
                w.put((int) v, 8);
                return;
            }
            int n = v < 0x800 ? 2 : v < 0x10000 ? 3 : v < 0x200000 ? 4 : v < 0x4000000 ? 5 : 6;
            w.put(((0xFF00 >> n) & 0xFF) | (int) (v >> (6 * (n - 1))), 8);
            for (int i = n - 2; i >= 0; i--) w.put(0x80 | (int) ((v >> (6 * i)) & 0x3F), 8);
        }

        private void subframe(Bits w, int[] x, int n) {
            boolean constant = true;
            for (int i = 1; i < n; i++) {
                if (x[i] != x[0]) {
                    constant = false;
                    break;
                }
            }
            if (constant) {
                w.put(0, 8);        // 0 + CONSTANT + no wasted bits
                w.put(x[0], 16);
                return;
            }
            int bestOrder = -1;
            long bestBits = 16L * n; // verbatim
            int bestPart = 0;
            final int maxOrder = Math.min(4, n - 1);
            for (int order = 0; order <= maxOrder; order++) {
                residual(x, n, order, resid);
                int cnt = n - order;
                for (int p = 0; p <= 6; p++) {
                    if ((n & ((1 << p) - 1)) != 0 || (n >> p) <= order) break;
                    long bits = riceBits(resid, order, n, p, null) + 16L * order;
                    if (bits < bestBits) {
                        bestBits = bits;
                        bestOrder = order;
                        bestPart = p;
                        System.arraycopy(resid, 0, best, 0, cnt);
                    }
                }
            }
            if (bestOrder < 0) {
                w.put(1 << 1, 8);   // VERBATIM
                for (int i = 0; i < n; i++) w.put(x[i], 16);
                return;
            }
            w.put((8 + bestOrder) << 1, 8);
            for (int i = 0; i < bestOrder; i++) w.put(x[i], 16);
            w.put(0, 2);            // Rice, 4 bit parameters
            w.put(bestPart, 4);
            riceBits(best, bestOrder, n, bestPart, w);
        }

        private static void residual(int[] x, int n, int order, int[] r) {
            for (int i = order; i < n; i++) {
                int v;
                switch (order) {
                    case 0:
                        v = x[i];
                        break;
                    case 1:
                        v = x[i] - x[i - 1];
                        break;
                    case 2:
                        v = x[i] - 2 * x[i - 1] + x[i - 2];
                        break;
                    case 3:
                        v = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3];
                        break;
                    default:
                        v = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4];
                        break;
                }
                r[i - order] = v;
            }
        }

        /** Bits needed for the residual with partition order p; writes it when w != null. */
        private static long riceBits(int[] r, int order, int n, int p, Bits w) {
            final int parts = 1 << p, size = n >> p;
            long total = 6; // 2 bit method + 4 bit order are counted by the caller only for the header, keep it simple
            int pos = 0;
            for (int part = 0; part < parts; part++) {
                int cnt = part == 0 ? size - order : size;
                long sum = 0;
                for (int i = 0; i < cnt; i++) {
                    int v = r[pos + i];
                    sum += (v << 1) ^ (v >> 31);
                }
                int k0 = 0;
                if (cnt > 0) {
                    long mean = sum / cnt;
                    while ((1L << (k0 + 1)) <= mean && k0 < 14) k0++;
                }
                int bestK = k0;
                long bestB = Long.MAX_VALUE;
                for (int k = Math.max(0, k0 - 1); k <= Math.min(14, k0 + 1); k++) {
                    long b = 0;
                    for (int i = 0; i < cnt; i++) {
                        int v = r[pos + i];
                        int zz = (v << 1) ^ (v >> 31);
                        b += (zz >>> k) + 1 + k;
                    }
                    if (b < bestB) {
                        bestB = b;
                        bestK = k;
                    }
                }
                total += 4 + bestB;
                if (w != null) {
                    w.put(bestK, 4);
                    for (int i = 0; i < cnt; i++) {
                        int v = r[pos + i];
                        int zz = (v << 1) ^ (v >> 31);
                        int q = zz >>> bestK;
                        w.zeros(q);
                        w.put(1, 1);
                        if (bestK > 0) w.put(zz & ((1 << bestK) - 1), bestK);
                    }
                }
                pos += cnt;
            }
            return total;
        }

        private static int crc8(byte[] b, int from, int to) {
            int crc = 0;
            for (int i = from; i < to; i++) {
                crc ^= b[i] & 0xFF;
                for (int k = 0; k < 8; k++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
            return crc;
        }

        private static int crc16(byte[] b, int from, int to) {
            int crc = 0;
            for (int i = from; i < to; i++) {
                crc ^= (b[i] & 0xFF) << 8;
                for (int k = 0; k < 8; k++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
            return crc;
        }
    }

    /** Big endian bit writer. */
    static final class Bits {
        private final byte[] buf;
        private int bit;

        Bits(byte[] buf) {
            this.buf = buf;
            java.util.Arrays.fill(buf, (byte) 0);
        }

        void put(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) buf[bit >> 3] |= (byte) (0x80 >> (bit & 7));
                bit++;
            }
        }

        void zeros(int count) {
            bit += count;
        }

        void align() {
            bit = (bit + 7) & ~7;
        }

        int bytePos() {
            return (bit + 7) >> 3;
        }
    }
}
