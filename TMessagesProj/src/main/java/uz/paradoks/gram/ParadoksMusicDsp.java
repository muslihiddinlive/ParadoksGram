package uz.paradoks.gram;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Native (pure Java) port of the "Music Vibe Lab" web audio chain: decoder, effects
 * (karaoke, bass boost, 5-band equalizer, 8D, echo, reverb, slow / nightcore), spectrum analysis
 * and WAV / M4A (AAC) writers. The same chain is used for live playback and for exporting.
 */
public final class ParadoksMusicDsp {

    private ParadoksMusicDsp() {
    }

    /** Longest track that is decoded into memory (stereo 16 bit => ~10 MB per minute at 44.1 kHz). */
    public static final int MAX_SECONDS = 15 * 60;

    // ------------------------------------------------------------------------------------------
    // Decoded audio
    // ------------------------------------------------------------------------------------------

    public static final class Pcm {
        /** Interleaved stereo, 16 bit. */
        public short[] data;
        public int frames;
        public int sampleRate;

        public float durationSeconds() {
            return sampleRate > 0 ? frames / (float) sampleRate : 0f;
        }
    }

    public interface Progress {
        void onProgress(float fraction);
    }

    /** Opens the file through its descriptor first (works with any file name), then by path. */
    private static MediaExtractor openExtractor(String path) throws IOException {
        java.io.FileInputStream fis = null;
        MediaExtractor ex = new MediaExtractor();
        try {
            fis = new java.io.FileInputStream(path);
            ex.setDataSource(fis.getFD());
            return ex;
        } catch (Throwable first) {
            try {
                ex.release();
            } catch (Throwable ignore) {
            }
            MediaExtractor second = new MediaExtractor();
            try {
                second.setDataSource(path);
                return second;
            } catch (Throwable e) {
                try {
                    second.release();
                } catch (Throwable ignore) {
                }
                throw new IOException("Faylni ochib bo'lmadi (" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + ")");
            }
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    public static Pcm decode(String path, Progress progress) throws IOException {
        return decode(path, progress, null);
    }

    /** Decodes any audio file Android can play into 16 bit stereo PCM. Blocking; call from a worker thread. */
    public static Pcm decode(String path, Progress progress, Cancel cancel) throws IOException {
        MediaExtractor extractor = openExtractor(path);
        MediaCodec codec = null;
        try {
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                throw new IOException("Audio trek topilmadi");
            }
            extractor.selectTrack(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            boolean floatPcm = false;

            int expected = durationUs > 0 ? (int) Math.min((long) MAX_SECONDS * sampleRate, durationUs * sampleRate / 1_000_000L + sampleRate) : sampleRate * 60;
            short[] out;
            try {
                out = new short[Math.max(expected, sampleRate) * 2];
            } catch (OutOfMemoryError oom) {
                throw new IOException("Xotira yetarli emas (trek juda uzun)");
            }
            int frames = 0;
            final int maxFrames = MAX_SECONDS * sampleRate;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long lastReport = 0;
            int idle = 0;
            while (!outputDone && frames < maxFrames) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new IOException("Bekor qilindi");
                }
                boolean moved = false;
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        moved = true;
                    }
                }
                // just fed input: do not block on the output, keep the codec busy
                int outIndex = codec.dequeueOutputBuffer(info, moved ? 0 : 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 24 && nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floatPcm = nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT;
                    }
                    moved = true;
                } else if (outIndex >= 0) {
                    moved = true;
                    ByteBuffer buf = codec.getOutputBuffer(outIndex);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        buf.order(ByteOrder.LITTLE_ENDIAN);
                        int ch = Math.max(1, channels);
                        int bytesPer = floatPcm ? 4 : 2;
                        int n = info.size / bytesPer / ch;
                        if ((frames + n) * 2 > out.length) {
                            int newLen = Math.max(out.length * 3 / 2, (frames + n) * 2 + 4096);
                            newLen = Math.min(newLen, (maxFrames + 4096) * 2);
                            try {
                                short[] bigger = new short[newLen];
                                System.arraycopy(out, 0, bigger, 0, frames * 2);
                                out = bigger;
                            } catch (OutOfMemoryError oom) {
                                throw new IOException("Xotira yetarli emas (trek juda uzun)");
                            }
                        }
                        n = Math.min(n, (out.length / 2) - frames);
                        if (!floatPcm && ch == 2) {
                            // bulk copy of interleaved 16-bit stereo
                            buf.asShortBuffer().get(out, frames * 2, n * 2);
                        } else for (int i = 0; i < n; i++) {
                            short l, r;
                            if (floatPcm) {
                                l = floatToShort(buf.getFloat());
                                r = l;
                                if (ch >= 2) {
                                    r = floatToShort(buf.getFloat());
                                    for (int k = 2; k < ch; k++) {
                                        buf.getFloat();
                                    }
                                }
                            } else {
                                l = buf.getShort();
                                r = l;
                                if (ch >= 2) {
                                    r = buf.getShort();
                                    for (int k = 2; k < ch; k++) {
                                        buf.getShort();
                                    }
                                }
                            }
                            out[(frames + i) * 2] = l;
                            out[(frames + i) * 2 + 1] = r;
                        }
                        frames += n;
                        if (progress != null && durationUs > 0 && info.presentationTimeUs - lastReport > 250_000) {
                            lastReport = info.presentationTimeUs;
                            progress.onProgress(Math.min(1f, info.presentationTimeUs / (float) durationUs));
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
                // some decoders never raise END_OF_STREAM: stop after ~5 s without any activity once the input is exhausted
                idle = moved ? 0 : idle + 1;
                if (inputDone && idle > 500) {
                    break;
                }
            }
            if (frames <= 0) {
                throw new IOException("Audio dekodlanmadi");
            }
            Pcm pcm = new Pcm();
            pcm.data = out;
            pcm.frames = frames;
            pcm.sampleRate = sampleRate;
            return pcm;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignore) {
            }
            extractor.release();
        }
    }

    private static short floatToShort(float v) {
        int s = Math.round(v * 32767f);
        return (short) Math.max(-32768, Math.min(32767, s));
    }

    // ------------------------------------------------------------------------------------------
    // Parameters
    // ------------------------------------------------------------------------------------------

    public static final int[] EQ_FREQ = {60, 250, 1000, 4000, 12000};

    public static final class Params {
        public volatile boolean karaoke, bass, eq, d8, echo, reverb, slow, nightcore;
        public volatile float bassDb = 10f;       // 0..18
        public volatile float d8Speed = 0.15f;    // Hz
        public volatile float echoTime = 0.38f;   // s
        public volatile float reverbMix = 0.4f;   // 0..1
        public final float[] eqGain = new float[5]; // dB, -12..12

        public float speed() {
            return slow ? 0.8f : nightcore ? 1.25f : 1f;
        }

        public boolean anyEffect() {
            return karaoke || bass || eq || d8 || echo || reverb || slow || nightcore;
        }

        public Params copy() {
            Params p = new Params();
            p.karaoke = karaoke;
            p.bass = bass;
            p.eq = eq;
            p.d8 = d8;
            p.echo = echo;
            p.reverb = reverb;
            p.slow = slow;
            p.nightcore = nightcore;
            p.bassDb = bassDb;
            p.d8Speed = d8Speed;
            p.echoTime = echoTime;
            p.reverbMix = reverbMix;
            System.arraycopy(eqGain, 0, p.eqGain, 0, 5);
            return p;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------------------------------------

    private static final class Biquad {
        static final int LOWSHELF = 0, PEAKING = 1, HIGHSHELF = 2;
        float b0 = 1, b1, b2, a1, a2;
        float z1l, z2l, z1r, z2r;
        int type = -1;
        float freq = -1, gain = Float.NaN, rate = -1;

        void configure(int type, float freq, float gainDb, float rate) {
            if (type == this.type && freq == this.freq && gainDb == this.gain && rate == this.rate) {
                return;
            }
            this.type = type;
            this.freq = freq;
            this.gain = gainDb;
            this.rate = rate;
            double A = Math.pow(10, gainDb / 40.0);
            double w0 = 2 * Math.PI * Math.min(freq, rate * 0.45) / rate;
            double cos = Math.cos(w0), sin = Math.sin(w0);
            double sqA = Math.sqrt(A);
            double nb0, nb1, nb2, na0, na1, na2;
            if (type == PEAKING) {
                double alpha = sin / 2.0; // Q = 1
                nb0 = 1 + alpha * A;
                nb1 = -2 * cos;
                nb2 = 1 - alpha * A;
                na0 = 1 + alpha / A;
                na1 = -2 * cos;
                na2 = 1 - alpha / A;
            } else {
                double alpha = sin * Math.sqrt(0.5); // shelf slope S = 1
                if (type == LOWSHELF) {
                    nb0 = A * ((A + 1) - (A - 1) * cos + 2 * sqA * alpha);
                    nb1 = 2 * A * ((A - 1) - (A + 1) * cos);
                    nb2 = A * ((A + 1) - (A - 1) * cos - 2 * sqA * alpha);
                    na0 = (A + 1) + (A - 1) * cos + 2 * sqA * alpha;
                    na1 = -2 * ((A - 1) + (A + 1) * cos);
                    na2 = (A + 1) + (A - 1) * cos - 2 * sqA * alpha;
                } else {
                    nb0 = A * ((A + 1) + (A - 1) * cos + 2 * sqA * alpha);
                    nb1 = -2 * A * ((A - 1) + (A + 1) * cos);
                    nb2 = A * ((A + 1) + (A - 1) * cos - 2 * sqA * alpha);
                    na0 = (A + 1) - (A - 1) * cos + 2 * sqA * alpha;
                    na1 = 2 * ((A - 1) - (A + 1) * cos);
                    na2 = (A + 1) - (A - 1) * cos - 2 * sqA * alpha;
                }
            }
            b0 = (float) (nb0 / na0);
            b1 = (float) (nb1 / na0);
            b2 = (float) (nb2 / na0);
            a1 = (float) (na1 / na0);
            a2 = (float) (na2 / na0);
        }

        void process(float[] l, float[] r, int n) {
            for (int i = 0; i < n; i++) {
                float x = l[i];
                float y = b0 * x + z1l;
                z1l = b1 * x - a1 * y + z2l;
                z2l = b2 * x - a2 * y;
                l[i] = y;
                x = r[i];
                y = b0 * x + z1r;
                z1r = b1 * x - a1 * y + z2r;
                z2r = b2 * x - a2 * y;
                r[i] = y;
            }
        }
    }

    /** Schroeder / Freeverb style reverberator (the web version used a noise impulse response). */
    private static final class Reverb {
        private final float[][] comb = new float[8][];
        private final int[] combPos = new int[8];
        private final float[] combStore = new float[8];
        private final float[][] allpass = new float[4][];
        private final int[] allpassPos = new int[4];
        private static final int[] COMB = {1116, 1188, 1277, 1356, 1139, 1211, 1300, 1379};
        private static final int[] ALL = {556, 441, 579, 464};

        Reverb(int rate) {
            float k = rate / 44100f;
            for (int i = 0; i < 8; i++) {
                comb[i] = new float[Math.max(8, (int) (COMB[i] * k))];
            }
            for (int i = 0; i < 4; i++) {
                allpass[i] = new float[Math.max(8, (int) (ALL[i] * k))];
            }
        }

        void clear() {
            for (float[] c : comb) java.util.Arrays.fill(c, 0f);
            for (float[] a : allpass) java.util.Arrays.fill(a, 0f);
            java.util.Arrays.fill(combStore, 0f);
        }

        void process(float[] l, float[] r, int n, float mix) {
            final float room = 0.86f, damp = 0.25f, wetGain = mix * 3.2f, dry = 1f - mix * 0.5f;
            for (int i = 0; i < n; i++) {
                float in = (l[i] + r[i]) * 0.015f;
                float outL = 0, outR = 0;
                for (int c = 0; c < 8; c++) {
                    float[] buf = comb[c];
                    float v = buf[combPos[c]];
                    combStore[c] = v * (1 - damp) + combStore[c] * damp;
                    buf[combPos[c]] = in + combStore[c] * room;
                    if (++combPos[c] >= buf.length) combPos[c] = 0;
                    if (c < 4) outL += v; else outR += v;
                }
                for (int a = 0; a < 4; a++) {
                    float[] buf = allpass[a];
                    float bufOutL = buf[allpassPos[a]];
                    float input = a < 2 ? outL : outR;
                    float o = -input + bufOutL;
                    buf[allpassPos[a]] = input + bufOutL * 0.5f;
                    if (++allpassPos[a] >= buf.length) allpassPos[a] = 0;
                    if (a < 2) outL = o; else outR = o;
                }
                l[i] = l[i] * dry + outL * wetGain;
                r[i] = r[i] * dry + outR * wetGain;
            }
        }
    }

    /** Stateful effect chain. One instance per playback / export. */
    public static final class Chain {
        private final int rate;
        private final Biquad bass = new Biquad();
        private final Biquad[] eq = {new Biquad(), new Biquad(), new Biquad(), new Biquad(), new Biquad()};
        private double lfoPhase;
        private final float[] echoL, echoR;
        private int echoPos;
        private boolean echoWas, reverbWas;
        private final Reverb reverb;

        public Chain(int sampleRate) {
            this.rate = sampleRate;
            echoL = new float[sampleRate * 2 + 8];
            echoR = new float[sampleRate * 2 + 8];
            reverb = new Reverb(sampleRate);
        }

        public void process(float[] l, float[] r, int n, Params p) {
            if (p.karaoke) {
                for (int i = 0; i < n; i++) {
                    float m = (l[i] - r[i]) * 0.7071f;
                    l[i] = m;
                    r[i] = m;
                }
            }
            if (p.bass) {
                bass.configure(Biquad.LOWSHELF, 140f, p.bassDb, rate);
                bass.process(l, r, n);
            }
            if (p.eq) {
                for (int b = 0; b < 5; b++) {
                    eq[b].configure(b == 0 ? Biquad.LOWSHELF : b == 4 ? Biquad.HIGHSHELF : Biquad.PEAKING, EQ_FREQ[b], p.eqGain[b], rate);
                    eq[b].process(l, r, n);
                }
            }
            if (p.d8) {
                double inc = 2 * Math.PI * p.d8Speed / rate;
                for (int i = 0; i < n; i++) {
                    float pan = (float) Math.sin(lfoPhase);
                    lfoPhase += inc;
                    if (lfoPhase > Math.PI * 2) lfoPhase -= Math.PI * 2;
                    float x = pan <= 0 ? pan + 1 : pan;
                    float gl = (float) Math.cos(x * Math.PI / 2), gr = (float) Math.sin(x * Math.PI / 2);
                    float il = l[i], ir = r[i];
                    if (pan <= 0) {
                        l[i] = il + ir * gl;
                        r[i] = ir * gr;
                    } else {
                        l[i] = il * gl;
                        r[i] = ir + il * gr;
                    }
                }
            }
            if (p.echo) {
                if (!echoWas) {
                    java.util.Arrays.fill(echoL, 0f);
                    java.util.Arrays.fill(echoR, 0f);
                    echoWas = true;
                }
                int len = echoL.length;
                int delay = Math.max(1, Math.min(len - 1, (int) (p.echoTime * rate)));
                for (int i = 0; i < n; i++) {
                    int rp = echoPos - delay;
                    if (rp < 0) rp += len;
                    float yl = echoL[rp], yr = echoR[rp];
                    echoL[echoPos] = l[i] + yl * 0.45f;
                    echoR[echoPos] = r[i] + yr * 0.45f;
                    l[i] += yl * 0.55f;
                    r[i] += yr * 0.55f;
                    if (++echoPos >= len) echoPos = 0;
                }
            } else {
                echoWas = false;
            }
            if (p.reverb) {
                if (!reverbWas) {
                    reverb.clear();
                    reverbWas = true;
                }
                reverb.process(l, r, n, p.reverbMix);
            } else {
                reverbWas = false;
            }
            // soft limiter (the web chain used a compressor when bass / eq were on)
            for (int i = 0; i < n; i++) {
                l[i] = soft(l[i]);
                r[i] = soft(r[i]);
            }
        }

        private static float soft(float x) {
            float a = Math.abs(x);
            if (a <= 0.9f) return x;
            float y = 0.9f + 0.1f * (float) Math.tanh((a - 0.9f) / 0.1f);
            return x < 0 ? -y : y;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Player / renderer engine
    // ------------------------------------------------------------------------------------------

    /** Reads a {@link Pcm} at the speed asked by {@link Params} and runs the effect chain. */
    public static final class Engine {
        public final Pcm pcm;
        public final Params params;
        public final Chain chain;
        public volatile double pos; // source frame
        /** When true the original sound is played (no effects, normal speed) - quick A/B comparison. */
        public volatile boolean bypass;

        public Engine(Pcm pcm, Params params) {
            this.pcm = pcm;
            this.params = params;
            this.chain = new Chain(pcm.sampleRate);
        }

        /** Fills up to n frames; returns the number of frames produced (0 at the end of the track). */
        public int fill(float[] l, float[] r, int n) {
            final float speed = bypass ? 1f : params.speed();
            final short[] d = pcm.data;
            final int last = pcm.frames - 1;
            double p = pos;
            int count = 0;
            for (; count < n; count++) {
                if (p >= last) break;
                int i0 = (int) p;
                float f = (float) (p - i0);
                int a = i0 * 2, b = a + 2;
                l[count] = (d[a] + (d[b] - d[a]) * f) / 32768f;
                r[count] = (d[a + 1] + (d[b + 1] - d[a + 1]) * f) / 32768f;
                p += speed;
            }
            pos = p;
            if (count > 0 && !bypass) chain.process(l, r, count, params);
            return count;
        }

        /** Processes silence through the chain (echo / reverb tail when exporting). */
        public void fillSilence(float[] l, float[] r, int n) {
            java.util.Arrays.fill(l, 0, n, 0f);
            java.util.Arrays.fill(r, 0, n, 0f);
            chain.process(l, r, n, params);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Spectrum analysis (visualizer)
    // ------------------------------------------------------------------------------------------

    public static final class Spectrum {
        public static final int SIZE = 2048;
        private final float[] re = new float[SIZE], im = new float[SIZE], win = new float[SIZE];
        private final float[] cos = new float[SIZE / 2], sin = new float[SIZE / 2];
        /** magnitude in dB for each bin (SIZE / 2) */
        public final float[] db = new float[SIZE / 2];
        /** last time-domain block (mono, -1..1) for the wave style */
        public final float[] wave = new float[SIZE];

        public Spectrum() {
            for (int i = 0; i < SIZE; i++) {
                win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (SIZE - 1)));
            }
            for (int i = 0; i < SIZE / 2; i++) {
                cos[i] = (float) Math.cos(2 * Math.PI * i / SIZE);
                sin[i] = (float) -Math.sin(2 * Math.PI * i / SIZE);
            }
        }

        /** Analyses the last SIZE samples ending at {@code end} (exclusive) of the interleaved stereo buffer. */
        public void analyze(float[] l, float[] r, int end) {
            for (int i = 0; i < SIZE; i++) {
                int k = end - SIZE + i;
                float v = k >= 0 && k < l.length ? (l[k] + r[k]) * 0.5f : 0f;
                wave[i] = v;
                re[i] = v * win[i];
                im[i] = 0;
            }
            fft();
            for (int i = 0; i < SIZE / 2; i++) {
                float m = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]) / (SIZE / 4f);
                db[i] = m > 1e-7f ? 20f * (float) Math.log10(m) : -140f;
            }
        }

        public void clear() {
            java.util.Arrays.fill(db, -140f);
            java.util.Arrays.fill(wave, 0f);
        }

        private void fft() {
            final int n = SIZE;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) {
                    float t = re[i]; re[i] = re[j]; re[j] = t;
                    t = im[i]; im[i] = im[j]; im[j] = t;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                int half = len >> 1, step = n / len;
                for (int i = 0; i < n; i += len) {
                    for (int j = 0, k = 0; j < half; j++, k += step) {
                        float wr = cos[k], wi = sin[k];
                        int a = i + j, b = a + half;
                        float xr = re[b] * wr - im[b] * wi;
                        float xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr;
                        im[b] = im[a] - xi;
                        re[a] += xr;
                        im[a] += xi;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // BPM (same onset / autocorrelation idea as the web version)
    // ------------------------------------------------------------------------------------------

    public static int estimateBpm(Pcm pcm) {
        final int B = 256;
        int n = Math.min(pcm.frames, pcm.sampleRate * 90);
        int blocks = n / B;
        if (blocks < 64) return 0;
        float[] e = new float[blocks];
        float prev = 0;
        for (int bI = 0; bI < blocks; bI++) {
            double s = 0;
            for (int k = 0; k < B; k++) {
                float v = pcm.data[(bI * B + k) * 2] / 32768f;
                s += v * v;
            }
            float rms = (float) Math.sqrt(s / B);
            e[bI] = Math.max(0, rms - prev);
            prev = rms;
        }
        double fps = pcm.sampleRate / (double) B;
        double best = 0;
        int bestLag = 0;
        for (int lag = (int) Math.floor(fps * 60 / 180); lag <= (int) Math.ceil(fps * 60 / 70); lag++) {
            if (lag < 1 || lag >= blocks) continue;
            double s = 0;
            for (int i = 0; i + lag < blocks; i++) s += e[i] * e[i + lag];
            s /= (blocks - lag);
            if (s > best) {
                best = s;
                bestLag = lag;
            }
        }
        return bestLag > 0 ? (int) Math.round(fps * 60 / bestLag) : 0;
    }

    // ------------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------------

    public interface Sink {
        void write(short[] interleaved, int frames) throws IOException;

        void finish() throws IOException;

        void abort();
    }

    public static final class WavSink implements Sink {
        private final RandomAccessFile file;
        private final int rate;
        private long dataBytes;
        private final byte[] tmp = new byte[8192];

        public WavSink(File out, int sampleRate) throws IOException {
            this.rate = sampleRate;
            file = new RandomAccessFile(out, "rw");
            file.setLength(0);
            file.write(new byte[44]);
        }

        @Override
        public void write(short[] d, int frames) throws IOException {
            int total = frames * 2, i = 0;
            while (i < total) {
                int chunk = Math.min(total - i, tmp.length / 2), o = 0;
                for (int k = 0; k < chunk; k++) {
                    short s = d[i + k];
                    tmp[o++] = (byte) s;
                    tmp[o++] = (byte) (s >> 8);
                }
                file.write(tmp, 0, o);
                i += chunk;
            }
            dataBytes += total * 2L;
        }

        @Override
        public void finish() throws IOException {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt((int) (36 + dataBytes)).put("WAVE".getBytes());
            h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(rate).putInt(rate * 4).putShort((short) 4).putShort((short) 16);
            h.put("data".getBytes()).putInt((int) dataBytes);
            file.seek(0);
            file.write(h.array());
            file.close();
        }

        @Override
        public void abort() {
            try {
                file.close();
            } catch (IOException ignore) {
            }
        }
    }

    /** AAC in an .m4a container (Android has no MP3 encoder). */
    public static final class M4aSink implements Sink {
        private final MediaCodec codec;
        private final MediaMuxer muxer;
        private final int rate;
        private int trackIndex = -1;
        private boolean muxerStarted;
        private long totalFrames;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        public M4aSink(File out, int sampleRate, int bitrate) throws IOException {
            this.rate = sampleRate;
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        @Override
        public void write(short[] d, int frames) throws IOException {
            int offset = 0; // in shorts
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
                n &= ~1; // whole frames
                for (int i = 0; i < n; i++) buf.putShort(d[offset + i]);
                long pts = totalFrames * 1_000_000L / rate;
                codec.queueInputBuffer(idx, 0, n * 2, pts, 0);
                totalFrames += n / 2;
                offset += n;
                drain(false);
            }
        }

        @Override
        public void finish() throws IOException {
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

    public interface Cancel {
        boolean isCancelled();
    }

    /** Renders the whole track through the effect chain into the sink. Blocking. Returns false when cancelled. */
    public static boolean render(Pcm pcm, Params params, Sink sink, Progress progress, Cancel cancel) throws IOException {
        Params p = params.copy();
        Engine engine = new Engine(pcm, p);
        final int block = 4096;
        float[] l = new float[block], r = new float[block];
        short[] out = new short[block * 2];
        double total = pcm.frames;
        while (true) {
            if (cancel != null && cancel.isCancelled()) return false;
            int n = engine.fill(l, r, block);
            if (n <= 0) break;
            toShorts(l, r, n, out);
            sink.write(out, n);
            if (progress != null) progress.onProgress((float) Math.min(1.0, engine.pos / total));
        }
        if (p.echo || p.reverb) { // let the tails ring out (4 s like the web version)
            int tail = pcm.sampleRate * 4;
            while (tail > 0) {
                if (cancel != null && cancel.isCancelled()) return false;
                int n = Math.min(block, tail);
                engine.fillSilence(l, r, n);
                toShorts(l, r, n, out);
                sink.write(out, n);
                tail -= n;
            }
        }
        return true;
    }

    public static void toShorts(float[] l, float[] r, int n, short[] out) {
        for (int i = 0; i < n; i++) {
            out[i * 2] = clip(l[i]);
            out[i * 2 + 1] = clip(r[i]);
        }
    }

    private static short clip(float v) {
        int s = Math.round(v * 32767f);
        return (short) (s > 32767 ? 32767 : s < -32768 ? -32768 : s);
    }
}
