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
     * Robot voice: ring-modulation vocoder.
     *
     * How it works:
     *   1. Smooth envelope follower tracks input loudness (fast attack, slow release)
     *   2. Buzz oscillator (sawtooth, 100 Hz) generates the robot "carrier"
     *   3. Buzz is gated by envelope: silent input → silent output (no idle buzzing)
     *   4. Two narrow bandpass filters at 800 Hz and 1800 Hz add "metallic" formants
     *   5. Light low-pass removes harshness above 3.5 kHz
     *
     * This produces the classic "Dalek / radio robot" sound without the
     * trrr-noise that the old comb approach caused.
     */
    private static final class Robot implements Effect {
        private static final float BUZZ_HZ   = 100f;
        private static final float ATK_MS    = 5f;   // envelope attack  ms
        private static final float REL_MS    = 80f;  // envelope release ms

        private final float phaseInc;
        private float phase = 0f;

        // Envelope follower
        private final float atkCoef, relCoef;
        private float env = 0f;

        // Two bandpass filters for metallic character
        private final Biquad bp1, bp2;

        // Output low-pass
        private float lp = 0f;
        private final float lpCoef;

        Robot(int rate) {
            phaseInc = BUZZ_HZ / rate;
            atkCoef  = (float)(1.0 - Math.exp(-1.0 / (ATK_MS * 0.001 * rate)));
            relCoef  = (float)(1.0 - Math.exp(-1.0 / (REL_MS * 0.001 * rate)));

            // Narrow bandpass at 800 Hz and 1800 Hz — metallic robot formants
            bp1 = Biquad.bandpass(rate, 800f,  3.0f);
            bp2 = Biquad.bandpass(rate, 1800f, 2.5f);

            // Low-pass ~3.5 kHz
            float fc = 3500f / rate;
            lpCoef = (float)(1.0 - Math.exp(-2.0 * Math.PI * fc));
        }

        @Override
        public float process(float x) {
            // 1. Envelope follower (asymmetric attack/release)
            float absX = x < 0 ? -x : x;
            if (absX > env) {
                env += atkCoef * (absX - env);
            } else {
                env += relCoef * (absX - env);
            }

            // 2. Sawtooth buzz oscillator
            phase += phaseInc;
            if (phase >= 1f) phase -= 1f;
            float buzz = 2f * phase - 1f; // [-1, 1]

            // 3. Gate buzz by envelope (no buzz when silent)
            float sig = buzz * env * 2.5f;
            if (sig >  1f) sig =  1f;
            if (sig < -1f) sig = -1f;

            // 4. Bandpass shaping for metallic formants
            float shaped = bp1.process(sig) * 0.6f + bp2.process(sig) * 0.4f;

            // 5. Low-pass to remove harshness
            lp += lpCoef * (shaped - lp);

            return clamp(lp * 1.8f);
        }
    }

    // ════════════════════════════════════════════════════════════════ CAVE
    /**
     * Cave reverb: Freeverb-style Schroeder reverb (4 parallel LP-combs +
     * 2 series allpass) + long echo tail.
     *
     * Click fix: allpass buffers are pre-warmed with silence so there is
     * no transient when the first real sample arrives.
     */
    private static final class Cave implements Effect {
        private final Comb[]    combs;
        private final AllPass[] aps;
        private final float[]   echo;
        private int   echoPos  = 0;
        private float echoFeed = 0f;

        Cave(int rate) {
            // Prime delays (ms) — Freeverb-style, slightly scaled up for "cave"
            int[] delayMs = {37, 43, 53, 59};
            combs = new Comb[delayMs.length];
            for (int i = 0; i < delayMs.length; i++) {
                combs[i] = new Comb(Math.max(2, rate * delayMs[i] / 1000), 0.78f, 0.20f);
            }
            // Allpass delays — must be non-zero and mutually prime
            aps = new AllPass[]{
                new AllPass(Math.max(2, rate * 11 / 1000), 0.5f),
                new AllPass(Math.max(2, rate *  7 / 1000), 0.5f)
            };
            // 300 ms echo for cave depth
            echo = new float[Math.max(2, rate * 300 / 1000)];
        }

        @Override
        public float process(float x) {
            // Parallel combs
            float wet = 0f;
            for (Comb c : combs) wet += c.process(x);
            wet *= 0.25f;

            // Series allpass diffusion
            for (AllPass a : aps) wet = a.process(wet);

            // Long echo with LP-damped feedback (cave depth)
            float e = echo[echoPos];
            echoFeed += 0.25f * (e - echoFeed);
            echo[echoPos] = x + 0.38f * echoFeed;
            if (++echoPos >= echo.length) echoPos = 0;

            // dry 45% + reverb 55% + echo 30%
            return clamp(0.45f * x + 0.55f * wet + 0.30f * echoFeed);
        }

        // LP-damped comb filter (Freeverb style)
        private static final class Comb {
            private final float[] buf;
            private int   pos   = 0;
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

        // Schroeder allpass (pre-filled with zeros — no click on first sample)
        private static final class AllPass {
            private final float[] buf;
            private int   pos  = 0;
            private final float gain;

            AllPass(int size, float gain) {
                buf = new float[size]; // Java zero-fills — no warm-up needed
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
     * Approach — replicates VoiceWave-style chipmunk:
     *   1. Pitch down first via circular-buffer resampling (ratio < 1 = slower read)
     *      so the voice sounds shorter/thicker, not just "nightcore fast".
     *      Actually we pitch UP by 3 semitones (ratio 1.189) — just enough for
     *      "small creature" without sounding like a sped-up recording.
     *   2. Nasal bandpass boost at 900 Hz (Q=2, +8 dB) — the "hmm hmm" honk
     *      that defines Villager speech.
     *   3. High-shelf cut above 4 kHz — Villager voices are muffled/boxy.
     *   4. No vibrato LFO — Villager pitch is steady, not wobbly.
     *
     * The combo of mild pitch-up + heavy nasal coloring is what separates
     * "Minecraft Villager" from plain chipmunk/nightcore.
     */
    private static final class Villager implements Effect {
        // 3 semitones up = 2^(3/12) ≈ 1.189  (keeps voice recognisable)
        private static final float RATIO = 1.189f;

        // Ring buffer (power of 2)
        private final float[] buf;
        private final int     mask;
        private int   writePtr = 0;
        private float readPtr  = 0f;

        // Nasal peak — the Villager "honk"
        private final Biquad nasal;

        // High-shelf cut — muffled boxy character
        private final Biquad shelf;

        Villager(int rate) {
            // ~150 ms buffer — plenty for the small ratio
            int size = 1;
            while (size < rate / 7) size <<= 1;
            buf  = new float[size];
            mask = size - 1;

            // Start read pointer slightly behind write
            readPtr = size - Math.max(4, size / 20);

            // Nasal peak at 900 Hz, Q=2, +8 dB
            nasal = Biquad.peaking(rate, 900f, 2.0f, 8f);

            // High-shelf cut: -8 dB above 4 kHz (muffled/boxy)
            shelf = Biquad.highShelf(rate, 4000f, -8f);
        }

        @Override
        public float process(float x) {
            // Write new sample
            buf[writePtr & mask] = x;
            writePtr++;

            // Linear-interpolated read at RATIO speed
            int   i0   = (int) readPtr;
            float frac = readPtr - i0;
            float s    = buf[i0 & mask] * (1f - frac) + buf[(i0 + 1) & mask] * frac;
            readPtr += RATIO;

            // Prevent pointer drift
            float lag = writePtr - readPtr;
            if (lag < 0)              lag += buf.length;
            if (lag < 8)              readPtr -= 32;
            if (lag > buf.length - 32) readPtr += 32;

            // Shape: nasal honk + high-shelf muffling
            float out = shelf.process(nasal.process(s));

            return clamp(out * 0.9f);
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

        /** Peaking EQ */
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

        /** Bandpass (constant skirt gain) */
        static Biquad bandpass(int rate, float freq, float q) {
            double w0    = 2.0 * Math.PI * freq / rate;
            double alpha = Math.sin(w0) / (2.0 * q);
            double cosW  = Math.cos(w0);
            double a0inv = 1.0 / (1.0 + alpha);
            return new Biquad(
                (float)(alpha          * a0inv),
                0f,
                (float)(-alpha         * a0inv),
                (float)((-2.0 * cosW)  * a0inv),
                (float)((1.0 - alpha)  * a0inv));
        }

        /** High-shelf filter */
        static Biquad highShelf(int rate, float freq, float gainDb) {
            double A    = Math.pow(10.0, gainDb / 40.0);
            double w0   = 2.0 * Math.PI * freq / rate;
            double cosW = Math.cos(w0);
            double sinW = Math.sin(w0);
            double S    = 1.0; // shelf slope
            double alpha = sinW / 2.0 * Math.sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0);
            double a0inv = 1.0 / (      (A+1) - (A-1)*cosW + 2*Math.sqrt(A)*alpha);
            return new Biquad(
                (float)(     A*((A+1) + (A-1)*cosW + 2*Math.sqrt(A)*alpha) * a0inv),
                (float)(  -2*A*((A-1) + (A+1)*cosW)                        * a0inv),
                (float)(     A*((A+1) + (A-1)*cosW - 2*Math.sqrt(A)*alpha) * a0inv),
                (float)(    -2*((A-1) - (A+1)*cosW)                        * a0inv),
                (float)(       ((A+1) - (A-1)*cosW - 2*Math.sqrt(A)*alpha) * a0inv));
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
