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
            float y = softClip(effect.process(x));
            buffer.putShort(i * 2, (short)(y * 32767f));
        }
        buffer.order(oldOrder);
    }

    private static float clamp(float y) {
        return y > 1f ? 1f : (y < -1f ? -1f : y);
    }

    /**
     * Gentle cubic soft clipper: transparent for small signals, smoothly saturates to +-1.
     * f(y) = y - (4/27) y^3 for |y| <= 1.5 (slope 0 and value 1.0 at the knee).
     */
    private static float softClip(float y) {
        if (y >= 1.5f) return 1f;
        if (y <= -1.5f) return -1f;
        return y - 0.148148148f * y * y * y;
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
     * Robot voice - ring modulation.
     *
     * Voice x sawtooth (120 Hz) = metallic sidebands while preserving speech.
     * Ring modulation is silent by itself when the input is silent (x = 0 -> output 0),
     * so no gate is needed. The old version also multiplied by an envelope follower, which
     * made the output proportional to x^2 (about -30 dB too quiet).
     * Two bandpass filters (600 Hz, 1500 Hz) are mixed in parallel with the raw ring-mod
     * signal to add metallic formants without throwing the level away.
     */
    private static final class Robot implements Effect {
        private static final float BUZZ_HZ = 120f;
        private static final float MAKEUP  = 2.0f;
        private final float phaseInc;
        private float phase = 0f;

        private final Biquad bp1, bp2;

        // Output 1-pole low-pass ~3.5 kHz
        private float lp = 0f;
        private final float lpCoef;

        Robot(int rate) {
            phaseInc = BUZZ_HZ / rate;
            bp1 = Biquad.bandpass(rate, 600f,  3.0f);
            bp2 = Biquad.bandpass(rate, 1500f, 2.5f);
            lpCoef = (float)(1.0 - Math.exp(-2.0 * Math.PI * 3500.0 / rate));
        }

        @Override
        public float process(float x) {
            phase += phaseInc;
            if (phase >= 1f) phase -= 1f;
            float buzz = 2f * phase - 1f; // [-1, 1], DC-free

            float ring = x * buzz;
            float shaped = 0.55f * ring + 0.5f * (bp1.process(ring) + bp2.process(ring));

            lp += lpCoef * (shaped - lp);
            return lp * MAKEUP;
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
        private final float echoLp;

        Cave(int rate) {
            // Prime delays in samples at 44.1 kHz, scaled proportionally for other rates
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
            // Echo damping low-pass at 3 kHz. The old constant 0.00042 was a time constant
            // mistaken for a coefficient: it is a 3 Hz low-pass that removed the whole voice.
            echoLp = (float)(1.0 - Math.exp(-2.0 * Math.PI * 3000.0 / rate));
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
            echoFeed += echoLp * (e - echoFeed);
            echo[echoPos] = x + 0.35f * echoFeed;
            if (++echoPos >= echo.length) echoPos = 0;

            return 0.60f * x + 0.50f * wet + 0.30f * echoFeed;
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
     * Pitch: +3 semitones with a two-tap crossfading delay-line pitch shifter.
     * Each tap's delay shrinks by (RATIO - 1) samples per sample, so the tap reads the
     * input RATIO times faster than real time (pitch UP). When a tap's delay reaches
     * zero it jumps back to the maximum while its Hann gain is 0; the second tap, half a
     * cycle away, takes over. The gains always sum to 1. Unlike a plain resampler there is
     * no drift between read and write pointers, so it never glitches, however long you talk.
     *
     * Timbre: nasal peak at 800 Hz Q=2 +5 dB + high-shelf cut -6 dB above 5 kHz.
     */
    private static final class Villager implements Effect {
        // 2^(3/12) = 1.1892 - 3 semitones up
        private static final double RATIO = Math.pow(2.0, 3.0 / 12.0);

        private final float[] buf;
        private final int     mask;
        private int   writeIdx = 0;

        private final float window;    // delay sweep range in samples (~20 ms)
        private final float phaseInc;  // fraction of the sweep per sample
        private float phase = 0f;

        private final Biquad nasal;
        private final Biquad shelf;

        Villager(int rate) {
            window   = rate * 0.020f;
            phaseInc = (float)(RATIO - 1.0) / window;
            int size = 1;
            while (size < window + 16) size <<= 1;
            buf  = new float[size];
            mask = size - 1;

            nasal = Biquad.peaking(rate, 800f, 2.0f, 5f);
            shelf = Biquad.highShelf(rate, 5000f, -6f);
        }

        @Override
        public float process(float x) {
            buf[writeIdx & mask] = x;

            float pA = phase;
            float pB = phase + 0.5f;
            if (pB >= 1f) pB -= 1f;

            float sA = (float) Math.sin(Math.PI * pA);
            float sB = (float) Math.sin(Math.PI * pB);
            float s  = tap(pA) * (sA * sA) + tap(pB) * (sB * sB);

            phase += phaseInc;
            if (phase >= 1f) phase -= 1f;
            writeIdx++;

            return shelf.process(nasal.process(s));
        }

        /** Cubic (Catmull-Rom) interpolated read at a delay of 2 .. 2 + window samples. */
        private float tap(float p) {
            float pos = writeIdx - (2f + window * (1f - p));
            int   i   = (int) Math.floor(pos);
            float f   = pos - i;
            float y0 = buf[(i - 1) & mask];
            float y1 = buf[i & mask];
            float y2 = buf[(i + 1) & mask];
            float y3 = buf[(i + 2) & mask];
            float c1 = 0.5f * (y2 - y0);
            float c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3;
            float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
            return ((c3 * f + c2) * f + c1) * f + y1;
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
            // RBJ audio-EQ-cookbook high shelf, shelf slope S = 1
            double A    = Math.pow(10.0, gainDb / 40.0);
            double w0   = 2.0 * Math.PI * freq / rate;
            double cosW = Math.cos(w0);
            double sinW = Math.sin(w0);
            double alpha = sinW / Math.sqrt(2.0);   // = sin(w0)/2 * sqrt((A + 1/A)(1/S - 1) + 2) for S = 1
            double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;
            double a0inv = 1.0 / ((A + 1) - (A - 1) * cosW + twoSqrtAAlpha);
            return new Biquad(
                (float)(      A * ((A + 1) + (A - 1) * cosW + twoSqrtAAlpha) * a0inv),
                (float)( -2 * A * ((A - 1) + (A + 1) * cosW)                 * a0inv),
                (float)(      A * ((A + 1) + (A - 1) * cosW - twoSqrtAAlpha) * a0inv),
                // a1 is +2*((A-1) - (A+1)cos w0): the old code had the opposite sign, which
                // turned the -6 dB shelf into -22 dB everywhere and +13 dB at 20 kHz.
                (float)(  2 * ((A - 1) - (A + 1) * cosW)                     * a0inv),
                (float)(        ((A + 1) - (A - 1) * cosW - twoSqrtAAlpha)   * a0inv));
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
