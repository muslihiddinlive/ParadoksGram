package uz.paradoks.gram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Real-time voice effects for voice-message recording (16-bit mono PCM).
 * Sample rate: 48000 Hz (hard-coded in MediaController).
 * Buffer: AudioRecord.getMinBufferSize(48000, MONO, PCM16) bytes, typically 3840.
 * All effects are fully streaming — state survives across buffers.
 */
public final class ParadoksVoice {

    public static final int OFF      = 0;
    public static final int ROBOT    = 1;
    public static final int CAVE     = 2;
    public static final int VILLAGER = 3;

    private interface Effect {
        float process(float x);
    }

    private static Effect effect;
    private static int    activeMode = -1;
    private static int    activeRate = 0;

    public static synchronized void reset() {
        effect     = null;
        activeMode = -1;
        activeRate = 0;
    }

    /** Processes {@code len} bytes of PCM16 audio in place. */
    public static synchronized void process(ByteBuffer buffer, int len, int sampleRate) {
        int mode = ParadoksConfig.getVoiceEffect();
        if (mode == OFF || buffer == null || len < 2) return;

        if (effect == null || mode != activeMode || sampleRate != activeRate) {
            effect     = create(mode, sampleRate);
            activeMode = mode;
            activeRate = sampleRate;
        }
        if (effect == null) return;

        ByteOrder oldOrder = buffer.order();
        buffer.order(ByteOrder.nativeOrder());
        int count = len / 2;
        for (int i = 0; i < count; i++) {
            float x = buffer.getShort(i * 2) / 32768f;
            float y = clamp(effect.process(x));
            buffer.putShort(i * 2, (short)(y * 32767f));
        }
        buffer.order(oldOrder);
    }

    private static float clamp(float y) {
        return y > 1f ? 1f : (y < -1f ? -1f : y);
    }

    private static Effect create(int mode, int rate) {
        switch (mode) {
            case ROBOT:    return new Robot(rate);
            case CAVE:     return new Cave(rate);
            case VILLAGER: return new Villager(rate);
            default:       return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════ ROBOT
    /**
     * Robot voice — ring modulation.
     *
     * Voice × sawtooth (120 Hz) = metallic sidebands while preserving speech.
     * Envelope follower gates output when silent (no idle buzz).
     * Two bandpass filters (600 Hz, 1500 Hz) → metallic robot formants.
     * Light 1-pole LP removes harshness.
     */
    private static final class Robot implements Effect {
        // Buzz oscillator — 120 Hz fundamental
        private static final float BUZZ_HZ = 120f;
        private final float phaseInc;
        private float phase = 0f;

        // Envelope follower: fast attack, slow release
        private final float atkCoef, relCoef;
        private float env = 0f;

        // Bandpass filters
        private final Biquad bp1, bp2;

        // Output 1-pole low-pass ~3 kHz
        private float lp = 0f;
        private final float lpCoef;

        Robot(int rate) {
            phaseInc = BUZZ_HZ / rate;

            // Attack 3 ms, release 120 ms — at 48000 Hz
            atkCoef = coef(3f,   rate);
            relCoef = coef(120f, rate);

            // Metallic formants: 600 Hz Q=3, 1500 Hz Q=2.5
            bp1 = Biquad.bandpass(rate, 600f,  3.0f);
            bp2 = Biquad.bandpass(rate, 1500f, 2.5f);

            // LP 3000 Hz
            lpCoef = (float)(1.0 - Math.exp(-2.0 * Math.PI * 3000.0 / rate));
        }

        private static float coef(float ms, int rate) {
            return (float)(1.0 - Math.exp(-1.0 / (ms * 0.001 * rate)));
        }

        @Override
        public float process(float x) {
            // 1. Envelope follower
            float absX = x < 0 ? -x : x;
            float c    = absX > env ? atkCoef : relCoef;
            env += c * (absX - env);

            // 2. Sawtooth buzz (100% DC-free)
            phase += phaseInc;
            if (phase >= 1f) phase -= 1f;
            float buzz = 2f * phase - 1f; // [-1, 1]

            // 3. Ring-modulate: voice × buzz = metallic sidebands, speech preserved
            //    Gate by envelope so output is silent when not speaking
            float sig = x * buzz * env * 0.8f;

            // 4. Bandpass coloring (equal mix)
            float shaped = bp1.process(sig) * 0.5f + bp2.process(sig) * 0.5f;

            // 5. Low-pass smoothing
            lp += lpCoef * (shaped - lp);

            return lp;
        }
    }

    // ════════════════════════════════════════════════════════════════ CAVE
    /**
     * Cave reverb — Freeverb-style Schroeder (4 LP-combs + 2 allpass) + long echo.
     *
     * Tuned for 48000 Hz. Java zero-fills all delay buffers → no transient click.
     * Dry/wet mix: 45% dry + 50% reverb + 25% echo.
     */
    private static final class Cave implements Effect {
        private final Comb[]    combs;
        private final AllPass[] aps;
        private final float[]   echo;
        private int   echoPos  = 0;
        private float echoFeed = 0f;

        Cave(int rate) {
            // Prime delays in samples at 48000 Hz (scaled proportionally for other rates)
            // Original Freeverb delays * (rate / 44100)
            int[] delaySamples = {
                scale(1557, rate), scale(1617, rate),
                scale(1491, rate), scale(1422, rate)
            };
            combs = new Comb[delaySamples.length];
            for (int i = 0; i < delaySamples.length; i++) {
                combs[i] = new Comb(Math.max(2, delaySamples[i]), 0.80f, 0.22f);
            }
            aps = new AllPass[]{
                new AllPass(Math.max(2, scale(225, rate)), 0.5f),
                new AllPass(Math.max(2, scale(556, rate)), 0.5f)
            };
            // 320 ms echo
            echo = new float[Math.max(2, rate * 320 / 1000)];
        }

        private static int scale(int samples44k, int rate) {
            return (int)(samples44k * (long)rate / 44100);
        }

        @Override
        public float process(float x) {
            float wet = 0f;
            for (Comb c : combs) wet += c.process(x);
            wet *= 0.25f;
            for (AllPass a : aps) wet = a.process(wet);

            float e = echo[echoPos];
            // LP damp: τ=50ms at 48kHz → coef = 1-exp(-1/(0.05*48000)) ≈ 0.00042
            echoFeed += 0.00042f * (e - echoFeed);
            echo[echoPos] = x + 0.35f * echoFeed;
            if (++echoPos >= echo.length) echoPos = 0;

            // Mix coefficients sum to 1.0 — no clipping
            return clamp(0.40f * x + 0.42f * wet + 0.18f * echoFeed);
        }

        private static final class Comb {
            private final float[] buf;
            private int   pos   = 0;
            private final float fb, damp;
            private float store = 0f;

            Comb(int size, float fb, float damp) {
                buf = new float[size]; // zero-filled
                this.fb = fb; this.damp = damp;
            }

            float process(float x) {
                float out = buf[pos];
                store = out * (1f - damp) + store * damp;
                buf[pos] = x + store * fb;
                if (++pos >= buf.length) pos = 0;
                return out;
            }
        }

        private static final class AllPass {
            private final float[] buf;
            private int   pos  = 0;
            private final float gain;

            AllPass(int size, float gain) {
                buf = new float[size]; // zero-filled
                this.gain = gain;
            }

            float process(float x) {
                float d   = buf[pos];
                float out = d - gain * x;
                buf[pos]  = x + gain * d;
                if (++pos >= buf.length) pos = 0;
                return out;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════ VILLAGER
    /**
     * Minecraft Villager voice.
     *
     * Pitch: +3 semitones via circular-buffer resampling with linear interpolation.
     * readPtr advances by 1/RATIO ≈ 0.841 per sample (slower than write) →
     * time-compression → pitch UP when played at normal speed.
     *
     * Timbre: nasal peak at 800 Hz Q=2 +7 dB (the "hmm" honk) +
     *         high-shelf cut -6 dB at 5 kHz (boxy/muffled character).
     *
     * No LFO vibrato — Villager speech has steady pitch, not wobble.
     * Output level: 0.8 to stay well below clipping.
     */
    private static final class Villager implements Effect {
        // 2^(3/12) = 1.1892 — 3 semitones up
        // readPtr advances by 1/RATIO per sample (read SLOWER than write → pitch UP)
        private static final float RATIO     = 1.1892f;
        private static final float READ_STEP = 1f / RATIO; // ≈ 0.8409f

        private final float[] buf;
        private final int     mask;
        private int   writePtr = 0;
        private float readPtr  = 0f;

        private final Biquad nasal;
        private final Biquad shelf;

        Villager(int rate) {
            // 200 ms ring buffer, rounded up to power of 2
            int size = 1;
            while (size < rate / 5) size <<= 1;
            buf  = new float[size];
            mask = size - 1;
            // Start read pointer ~20 ms behind write (enough pre-fill for interpolation)
            readPtr = size - (int)(rate * 0.020f);

            // Nasal peak: 800 Hz, Q=2, +7 dB
            nasal = Biquad.peaking(rate, 800f, 2.0f, 7f);
            // High-shelf cut: -6 dB above 5000 Hz
            shelf = Biquad.highShelf(rate, 5000f, -6f);
        }

        @Override
        public float process(float x) {
            buf[writePtr & mask] = x;
            writePtr++;

            int   i0   = (int) readPtr;
            float frac = readPtr - i0;
            float s    = buf[i0 & mask] * (1f - frac) + buf[(i0 + 1) & mask] * frac;
            // Read SLOWER than write (READ_STEP < 1) → time-compress → pitch UP
            readPtr += READ_STEP;

            // Prevent pointer drift — lag = how far read lags behind write
            float lag = writePtr - readPtr;
            if (lag < 0)               lag += buf.length;
            if (lag < 8)               readPtr -= 16;   // read too close to write head
            if (lag > buf.length - 32) readPtr += 16;   // lag grown too large

            // Shape: nasal honk + muffled shelf
            float out = shelf.process(nasal.process(s));
            return clamp(out * 0.8f);
        }
    }

    // ═══════════════════════════════════════════════════════════ BIQUAD
    private static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float x1 = 0, x2 = 0, y1 = 0, y2 = 0;

        private Biquad(float b0, float b1, float b2, float a1, float a2) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2;
            this.a1 = a1; this.a2 = a2;
        }

        static Biquad peaking(int rate, float freq, float q, float gainDb) {
            double A     = Math.pow(10.0, gainDb / 40.0);
            double w0    = 2.0 * Math.PI * freq / rate;
            double alpha = Math.sin(w0) / (2.0 * q);
            double cosW  = Math.cos(w0);
            double a0inv = 1.0 / (1.0 + alpha / A);
            return new Biquad(
                (float)((1.0 + alpha * A) * a0inv),
                (float)((-2.0 * cosW)     * a0inv),
                (float)((1.0 - alpha * A) * a0inv),
                (float)((-2.0 * cosW)     * a0inv),
                (float)((1.0 - alpha / A) * a0inv));
        }

        static Biquad bandpass(int rate, float freq, float q) {
            double w0    = 2.0 * Math.PI * freq / rate;
            double alpha = Math.sin(w0) / (2.0 * q);
            double cosW  = Math.cos(w0);
            double a0inv = 1.0 / (1.0 + alpha);
            return new Biquad(
                (float)( alpha          * a0inv),
                0f,
                (float)(-alpha          * a0inv),
                (float)((-2.0 * cosW)   * a0inv),
                (float)((1.0 - alpha)   * a0inv));
        }

        static Biquad highShelf(int rate, float freq, float gainDb) {
            double A    = Math.pow(10.0, gainDb / 40.0);
            double w0   = 2.0 * Math.PI * freq / rate;
            double cosW = Math.cos(w0);
            double sinW = Math.sin(w0);
            double alpha = sinW / 2.0 * Math.sqrt((A + 1.0/A) * (1.0/1.0 - 1.0) + 2.0);
            // S=1 shelf slope simplification: alpha = sin(w0)/sqrt(2)
            double a2v  = sinW / Math.sqrt(2.0);
            double a0inv = 1.0 / (     (A+1) - (A-1)*cosW + 2*Math.sqrt(A)*a2v);
            return new Biquad(
                (float)(      A*((A+1) + (A-1)*cosW + 2*Math.sqrt(A)*a2v) * a0inv),
                (float)(   -2*A*((A-1) + (A+1)*cosW)                      * a0inv),
                (float)(      A*((A+1) + (A-1)*cosW - 2*Math.sqrt(A)*a2v) * a0inv),
                (float)(     -2*((A-1) - (A+1)*cosW)                      * a0inv),
                (float)(        ((A+1) - (A-1)*cosW - 2*Math.sqrt(A)*a2v) * a0inv));
        }

        float process(float x) {
            float y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            return y;
        }
    }

    private ParadoksVoice() {}
}
