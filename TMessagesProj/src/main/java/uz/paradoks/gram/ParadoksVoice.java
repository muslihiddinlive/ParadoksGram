package uz.paradoks.gram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Real-time voice effects for voice-message recording (16-bit mono PCM).
 *
 * Fixes vs previous version:
 *  - Robot: sawtooth carrier (no dry bleed), no comb filter (no clicking)
 *  - Cave:  full Freeverb (8 combs + 4 allpass, correct delay sizes, zero-init,
 *           denormal flushing) — no crackling
 *  - Villager: circular-buffer resampler + AM tremolo — no warbling
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
    private static int activeMode = -1;
    private static int activeRate = 0;

    public static synchronized void reset() {
        effect    = null;
        activeMode = -1;
        activeRate = 0;
    }

    /** Processes {@code len} bytes of PCM16 audio in-place. */
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
            buffer.putShort(i * 2, (short) (y * 32767f));
        }
        buffer.order(oldOrder);
    }

    private static float clamp(float v) {
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }

    private static Effect create(int mode, int rate) {
        switch (mode) {
            case ROBOT:    return new Robot(rate);
            case CAVE:     return new Cave(rate);
            case VILLAGER: return new Villager(rate);
            default:       return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════ ROBOT ══
    /**
     * Sawtooth ring modulation — zero dry signal, zero feedback comb.
     * Sawtooth gives a richer robot buzz than a sine; because it swings
     * symmetrically around 0 it produces no DC offset and no audible dry bleed.
     */
    private static final class Robot implements Effect {
        private static final float CARRIER_HZ = 100f;

        private final float phaseStep; // radians per sample
        private float phase = 0f;

        Robot(int rate) {
            phaseStep = (float) (2.0 * Math.PI * CARRIER_HZ / rate);
        }

        @Override
        public float process(float x) {
            // Sawtooth: phase / π  maps [0, 2π] → [0, 2], then − 1 → [-1, 1]
            float carrier = phase / (float) Math.PI - 1f;
            phase += phaseStep;
            if (phase >= (float) (2.0 * Math.PI)) phase -= (float) (2.0 * Math.PI);
            // Pure ring mod — no dry at all
            return x * carrier;
        }
    }

    // ════════════════════════════════════════════════════════════════ CAVE ══
    /**
     * Freeverb (Schroeder–Moorer) mono reverb.
     * Delay sizes from the original Freeverb tuning.h (44100 Hz), scaled for
     * other sample rates.  Every buffer is zero-initialised; denormals are
     * flushed to prevent CPU spikes that manifest as clicks.
     */
    private static final class Cave implements Effect {

        // Freeverb tuning at 44100 Hz
        private static final int[] COMB_SIZES_44  = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        private static final int[] AP_SIZES_44    = {556, 441, 341, 225};
        private static final float FIXED_GAIN     = 0.015f;
        private static final float ROOM_FEEDBACK  = 0.84f;  // roomSize=0.5 → 0.5*0.28+0.70
        private static final float DAMP           = 0.2f;   // damp=0.5  → 0.5*0.40

        private final LPComb[]   combs;
        private final APFilter[] allpasses;

        Cave(int rate) {
            combs     = new LPComb  [COMB_SIZES_44.length];
            allpasses = new APFilter[AP_SIZES_44.length];
            for (int i = 0; i < combs.length; i++) {
                int sz = Math.max(2, (int) ((long) COMB_SIZES_44[i] * rate / 44100));
                combs[i] = new LPComb(sz, ROOM_FEEDBACK, DAMP);
            }
            for (int i = 0; i < allpasses.length; i++) {
                int sz = Math.max(2, (int) ((long) AP_SIZES_44[i] * rate / 44100));
                allpasses[i] = new APFilter(sz);
            }
        }

        @Override
        public float process(float x) {
            float in  = x * FIXED_GAIN;
            float wet = 0f;
            for (LPComb c : combs)     wet += c.process(in);
            for (APFilter a : allpasses) wet  = a.process(wet);
            // blend: keep a little dry so voice stays intelligible
            return 0.2f * x + 2.8f * wet;
        }

        /** Lowpass-feedback comb filter (the "Schroeder comb" used in Freeverb). */
        private static final class LPComb {
            private final float[] buf;
            private int idx = 0;
            private float store = 0f;
            private final float fb, d1, d2;

            LPComb(int size, float feedback, float damp) {
                buf = new float[size];
                Arrays.fill(buf, 0f);
                fb = feedback;
                d1 = damp;
                d2 = 1f - damp;
            }

            float process(float x) {
                float out = buf[idx];
                // flush denormals
                if (out > -1e-6f && out < 1e-6f) out = 0f;
                store = out * d2 + store * d1;
                if (store > -1e-6f && store < 1e-6f) store = 0f;
                buf[idx] = x + store * fb;
                if (++idx >= buf.length) idx = 0;
                return out;
            }
        }

        /** Schroeder all-pass filter. */
        private static final class APFilter {
            private static final float GAIN = 0.5f;
            private final float[] buf;
            private int idx = 0;

            APFilter(int size) {
                buf = new float[size];
                Arrays.fill(buf, 0f);
            }

            float process(float x) {
                float delayed = buf[idx];
                if (delayed > -1e-6f && delayed < 1e-6f) delayed = 0f;
                buf[idx] = x + delayed * GAIN;
                if (++idx >= buf.length) idx = 0;
                return delayed - x;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════ VILLAGER ══
    /**
     * Minecraft-villager sound: circular-buffer fractional resampler (pitch ×1.5)
     * + fast AM tremolo (10 Hz).  No FFT, no phase-vocoder — no warbling.
     */
    private static final class Villager implements Effect {
        private static final float PITCH_RATIO   = 1.5f;  // up a fifth
        private static final float TREMOLO_HZ    = 10f;
        private static final float TREMOLO_DEPTH = 0.4f;  // 0.6 + 0.4·sin → [0.2, 1.0]

        private static final int BUF_BITS = 12;           // 4096 samples
        private static final int BUF_SIZE = 1 << BUF_BITS;
        private static final int BUF_MASK = BUF_SIZE - 1;

        private final float[] ring = new float[BUF_SIZE];
        private int   writePos     = 0;
        private float readPos;

        private final float tremoloStep;
        private float tremoloPhase = 0f;

        Villager(int rate) {
            // Start read 256 samples behind write so we always have valid data
            readPos     = BUF_SIZE - 256f;
            tremoloStep = (float) (2.0 * Math.PI * TREMOLO_HZ / rate);
        }

        @Override
        public float process(float x) {
            // 1. Write input
            ring[writePos & BUF_MASK] = x;
            writePos++;

            // 2. Read at fractional position (linear interpolation)
            int   i0      = (int) readPos;
            float frac    = readPos - i0;
            float s0      = ring[ i0       & BUF_MASK];
            float s1      = ring[(i0 + 1)  & BUF_MASK];
            float pitched = s0 + frac * (s1 - s0);

            // 3. Advance read position by pitch ratio; keep it in circular range
            readPos += PITCH_RATIO;
            if (readPos >= BUF_SIZE) readPos -= BUF_SIZE;

            // 4. AM tremolo
            float tremolo = (1f - TREMOLO_DEPTH) + TREMOLO_DEPTH * (float) Math.sin(tremoloPhase);
            tremoloPhase += tremoloStep;
            if (tremoloPhase >= (float) (2.0 * Math.PI)) tremoloPhase -= (float) (2.0 * Math.PI);

            return pitched * tremolo;
        }
    }

    private ParadoksVoice() {}
}
