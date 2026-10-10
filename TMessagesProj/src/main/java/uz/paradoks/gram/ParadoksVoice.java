package uz.paradoks.gram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Real-time voice effects for voice-message recording (16-bit mono PCM).
 * All effects are fully streaming — state survives across buffers, no clicks.
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
     * Robot voice: pulse-train vocoder approach.
     * - LPC-style spectral envelope via one-pole pre-emphasis
     * - Buzz excitation (sawtooth wave) replaces original pitch
     * - Original voice fully suppressed (only spectral shape kept)
     * No clicks: phase and filter state are continuous across buffers.
     */
    private static final class Robot implements Effect {
        // Buzz oscillator
        private static final float BUZZ_HZ = 120f; // fundamental pitch of robot
        private final float phaseInc;
        private float phase = 0f;

        // Pre-emphasis filter state (high-shelf to brighten buzz)
        private float preX1 = 0f;

        // Formant shaping: two-stage comb for metallic texture
        private final float[] comb1;
        private final float[] comb2;
        private int c1pos = 0, c2pos = 0;
        private static final float COMB_FB = 0.6f;

        // Final low-pass to smooth harshness
        private float lpState = 0f;
        private final float lpCoef;

        Robot(int rate) {
            phaseInc = BUZZ_HZ / rate;

            // two comb lengths — prime ratio avoids resonance aliasing
            comb1 = new float[Math.max(2, rate / 200)];  // ~5 ms
            comb2 = new float[Math.max(2, rate / 143)];  // ~7 ms

            // low-pass cutoff ~4 kHz
            float fc = 4000f / rate;
            lpCoef = (float)(1.0 - Math.exp(-2.0 * Math.PI * fc));
        }

        @Override
        public float process(float x) {
            // 1. Pre-emphasis on input to extract spectral envelope hint
            float pre = x - 0.95f * preX1;
            preX1 = x;
            float envAbs = Math.abs(pre);

            // 2. Generate buzz (sawtooth)
            phase += phaseInc;
            if (phase >= 1f) phase -= 1f;
            float buzz = 2f * phase - 1f; // sawtooth [-1,1]

            // 3. AM-modulate buzz by input envelope so loudness follows voice
            float sig = buzz * (0.3f + 0.7f * envAbs * 8f);
            if (sig >  1f) sig =  1f;
            if (sig < -1f) sig = -1f;

            // 4. Comb filtering for metallic formants
            float d1   = comb1[c1pos];
            float out1 = sig + COMB_FB * d1;
            comb1[c1pos] = out1;
            if (++c1pos >= comb1.length) c1pos = 0;

            float d2   = comb2[c2pos];
            float out2 = out1 + COMB_FB * d2;
            comb2[c2pos] = out2;
            if (++c2pos >= comb2.length) c2pos = 0;

            // 5. Low-pass to cut harshness
            lpState += lpCoef * (out2 * 0.4f - lpState);

            return clamp(lpState);
        }
    }

    // ════════════════════════════════════════════════════════════════ CAVE
    /**
     * Cave reverb: Schroeder reverb (4 parallel combs + 2 series allpass)
     * followed by a long echo. No clicks — all state is persistent.
     */
    private static final class Cave implements Effect {
        private final Comb[]   combs;
        private final AllPass[] aps;
        private final float[]  echo;
        private int echoPos = 0;
        private float echoFeed = 0f; // low-pass on echo feedback

        Cave(int rate) {
            // Moorer/Schroeder delays (prime, mutually incoherent)
            int[] delayMs = {29, 37, 41, 43};
            combs = new Comb[delayMs.length];
            for (int i = 0; i < delayMs.length; i++) {
                combs[i] = new Comb(Math.max(2, rate * delayMs[i] / 1000), 0.82f, 0.25f);
            }
            aps = new AllPass[]{
                new AllPass(Math.max(2, rate * 5  / 1000), 0.5f),
                new AllPass(Math.max(2, rate * 17 / 10000), 0.5f)
            };
            // 250 ms echo for cave depth
            echo = new float[Math.max(2, rate * 250 / 1000)];
        }

        @Override
        public float process(float x) {
            // Parallel combs
            float wet = 0f;
            for (Comb c : combs) wet += c.process(x);
            wet *= 0.25f;

            // Series allpass diffusion
            for (AllPass a : aps) wet = a.process(wet);

            // Long echo with LP-damped feedback
            float e = echo[echoPos];
            echoFeed += 0.3f * (e - echoFeed); // damp high freq in echo
            echo[echoPos] = x + 0.42f * echoFeed;
            if (++echoPos >= echo.length) echoPos = 0;

            return clamp(0.5f * x + 0.6f * wet + 0.35f * echoFeed);
        }

        // Schroeder comb with damping
        private static final class Comb {
            private final float[] buf;
            private int pos = 0;
            private final float fb, damp;
            private float store = 0f;

            Comb(int size, float fb, float damp) {
                buf = new float[size];
                this.fb   = fb;
                this.damp = damp;
            }

            float process(float x) {
                float out = buf[pos];
                store = out * (1f - damp) + store * damp;
                buf[pos] = x + store * fb;
                if (++pos >= buf.length) pos = 0;
                return out;
            }
        }

        // Schroeder allpass
        private static final class AllPass {
            private final float[] buf;
            private int pos = 0;
            private final float gain;

            AllPass(int size, float gain) {
                buf = new float[size];
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
     * Villager (chipmunk) voice: simple real-time resampling.
     *
     * Approach: write input at normal rate, read back faster (higher pitch).
     * Uses a circular buffer with linear interpolation — zero warble,
     * no grain boundaries, no FFT needed.
     *
     * Ratio 1.4 = pitch up ~5 semitones (chipmunk-ish without sounding tiny).
     * A gentle vibrato (±0.4 semitone, 5 Hz) adds the "alive" Villager feel.
     * Nasal bandpass boost at 1200 Hz reinforces the character.
     */
    private static final class Villager implements Effect {
        private static final float BASE_RATIO  = 1.40f;  // read speed / write speed
        private static final float VIB_DEPTH   = 0.008f; // ±0.8%
        private static final float VIB_HZ      = 5.0f;

        // Ring buffer — must be power-of-2 for fast masking
        private final float[] buf;
        private final int     mask;
        private int   writePtr = 0;
        private float readPtr  = 0f;

        // Vibrato LFO
        private final float lfoInc;
        private float lfo = 0f;

        // Nasal peak filter
        private final Biquad nasal;

        // Output LP to soften aliasing
        private float lpOut = 0f;
        private final float lpCoef;

        Villager(int rate) {
            // Buffer: 3× the max read window gives plenty of headroom
            int size = 1;
            while (size < rate / 5) size <<= 1; // ~200 ms
            buf  = new float[size];
            mask = size - 1;

            // Seed read pointer behind write so we always have data
            readPtr = size - (int)(size * 0.1f); // 10% behind

            lfoInc = (float)(2.0 * Math.PI * VIB_HZ / rate);

            // Boost around 1200 Hz, Q=1.8, +6 dB — nasal quality
            nasal  = Biquad.peaking(rate, 1200f, 1.8f, 6f);

            // LP ~8 kHz to suppress resampling aliases
            float fc = 8000f / rate;
            lpCoef   = (float)(1.0 - Math.exp(-2.0 * Math.PI * fc));
        }

        @Override
        public float process(float x) {
            // Write
            buf[writePtr & mask] = x;
            writePtr++;

            // Vibrato
            lfo += lfoInc;
            if (lfo > (float)(2.0 * Math.PI)) lfo -= (float)(2.0 * Math.PI);
            float ratio = BASE_RATIO + VIB_DEPTH * (float)Math.sin(lfo);

            // Read with linear interpolation
            int   i0   = (int)Math.floor(readPtr);
            float frac = readPtr - i0;
            float s    = buf[i0 & mask] * (1f - frac) + buf[(i0 + 1) & mask] * frac;
            readPtr += ratio;

            // Keep read pointer from drifting too far from write
            // (wrap within buffer, always stay behind writePtr)
            float lag = (writePtr - readPtr + buf.length) % buf.length;
            if (lag < 16)              readPtr -= 64;   // too close — step back
            if (lag > buf.length - 64) readPtr += 64;   // too far — catch up

            // Nasal coloring + LP anti-alias
            float colored = nasal.process(s);
            lpOut += lpCoef * (colored - lpOut);

            return clamp(lpOut * 0.85f);
        }
    }

    // ═══════════════════════════════════════════════════════════ BIQUAD
    private static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        private Biquad(float b0, float b1, float b2, float a1, float a2) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2;
            this.a1 = a1; this.a2 = a2;
        }

        static Biquad peaking(int rate, float freq, float q, float gainDb) {
            double A  = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * freq / rate;
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

        float process(float x) {
            float y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            return y;
        }
    }

    private ParadoksVoice() {}
}
