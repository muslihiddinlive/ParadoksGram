package uz.paradoks.gram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Real-time voice effects for voice-message recording (16-bit mono PCM).
 * All effects are streaming and keep their state between buffers, so there are no
 * discontinuities at buffer borders.
 */
public final class ParadoksVoice {

    public static final int OFF = 0;
    public static final int ROBOT = 1;
    public static final int CAVE = 2;
    public static final int VILLAGER = 3;

    private interface Effect {
        float process(float x);
    }

    private static Effect effect;
    private static int activeMode = -1;
    private static int activeRate = 0;

    public static synchronized void reset() {
        effect = null;
        activeMode = -1;
        activeRate = 0;
    }

    /** Processes {@code len} bytes of PCM16 audio in place. */
    public static synchronized void process(ByteBuffer buffer, int len, int sampleRate) {
        int mode = ParadoksConfig.getVoiceEffect();
        if (mode == OFF || buffer == null || len < 2) {
            return;
        }
        if (effect == null || mode != activeMode || sampleRate != activeRate) {
            effect = create(mode, sampleRate);
            activeMode = mode;
            activeRate = sampleRate;
        }
        if (effect == null) {
            return;
        }

        ByteOrder oldOrder = buffer.order();
        buffer.order(ByteOrder.nativeOrder());
        int count = len / 2;
        for (int i = 0; i < count; i++) {
            float x = buffer.getShort(i * 2) / 32768f;
            float y = effect.process(x);
            if (y > 1f) {
                y = 1f;
            } else if (y < -1f) {
                y = -1f;
            }
            buffer.putShort(i * 2, (short) (y * 32767f));
        }
        buffer.order(oldOrder);
    }

    private static Effect create(int mode, int rate) {
        switch (mode) {
            case ROBOT:
                return new Robot(rate);
            case CAVE:
                return new Cave(rate);
            case VILLAGER:
                return new Villager(rate);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ robot
    /** Ring modulation + short feedback comb = metallic "robot" voice. */
    private static final class Robot implements Effect {
        private static final double CARRIER_HZ = 70.0;
        private static final float COMB_FEEDBACK = 0.55f;

        private final double phaseStep;
        private double phase;
        private final float[] comb;
        private int combPos;

        Robot(int rate) {
            phaseStep = 2.0 * Math.PI * CARRIER_HZ / rate;
            comb = new float[Math.max(2, rate / 250)]; // ~4 ms
        }

        @Override
        public float process(float x) {
            float ring = (float) (x * Math.sin(phase));
            phase += phaseStep;
            if (phase > 2.0 * Math.PI) {
                phase -= 2.0 * Math.PI;
            }
            float delayed = comb[combPos];
            float y = ring + COMB_FEEDBACK * delayed;
            comb[combPos] = y;
            if (++combPos >= comb.length) {
                combPos = 0;
            }
            return 0.25f * x + 0.7f * y;
        }
    }

    // ------------------------------------------------------------------- cave
    /** Schroeder reverb + a slap echo: big empty cave. */
    private static final class Cave implements Effect {
        private final Comb[] combs;
        private final AllPass[] allPasses;
        private final float[] echo;
        private int echoPos;
        private float echoLow;

        Cave(int rate) {
            int[] combMs10 = {297, 371, 411, 437}; // tenths of ms
            combs = new Comb[combMs10.length];
            for (int i = 0; i < combs.length; i++) {
                combs[i] = new Comb(Math.max(2, (int) (rate * combMs10[i] / 10000L)), 0.84f, 0.3f);
            }
            allPasses = new AllPass[]{
                    new AllPass(Math.max(2, rate * 5 / 1000), 0.5f),
                    new AllPass(Math.max(2, rate * 17 / 10000), 0.5f)
            };
            echo = new float[Math.max(2, rate * 220 / 1000)];
        }

        @Override
        public float process(float x) {
            float wet = 0f;
            for (Comb c : combs) {
                wet += c.process(x);
            }
            wet *= 0.25f;
            for (AllPass a : allPasses) {
                wet = a.process(wet);
            }

            float e = echo[echoPos];
            echoLow += 0.35f * (e - echoLow);
            echo[echoPos] = x + 0.38f * echoLow;
            if (++echoPos >= echo.length) {
                echoPos = 0;
            }
            return 0.65f * x + 0.55f * wet + 0.3f * echoLow;
        }

        private static final class Comb {
            private final float[] buf;
            private int pos;
            private final float feedback;
            private final float damp;
            private float store;

            Comb(int size, float feedback, float damp) {
                this.buf = new float[size];
                this.feedback = feedback;
                this.damp = damp;
            }

            float process(float x) {
                float out = buf[pos];
                store = out * (1f - damp) + store * damp;
                buf[pos] = x + store * feedback;
                if (++pos >= buf.length) {
                    pos = 0;
                }
                return out;
            }
        }

        private static final class AllPass {
            private final float[] buf;
            private int pos;
            private final float gain;

            AllPass(int size, float gain) {
                this.buf = new float[size];
                this.gain = gain;
            }

            float process(float x) {
                float delayed = buf[pos];
                float out = delayed - gain * x;
                buf[pos] = x + gain * delayed;
                if (++pos >= buf.length) {
                    pos = 0;
                }
                return out;
            }
        }
    }

    // --------------------------------------------------------------- villager
    /** Pitch up with slow vibrato + nasal resonance. */
    private static final class Villager implements Effect {
        private static final float RATIO = 1.3f;
        private static final float VIBRATO_DEPTH = 0.03f;
        private static final double VIBRATO_HZ = 5.5;

        private final PitchShifter shifter;
        private final Biquad nasal;
        private final double lfoStep;
        private double lfo;

        Villager(int rate) {
            shifter = new PitchShifter(rate);
            nasal = Biquad.peaking(rate, 1200f, 1.5f, 7f);
            lfoStep = 2.0 * Math.PI * VIBRATO_HZ / rate;
        }

        @Override
        public float process(float x) {
            float ratio = RATIO + VIBRATO_DEPTH * (float) Math.sin(lfo);
            lfo += lfoStep;
            if (lfo > 2.0 * Math.PI) {
                lfo -= 2.0 * Math.PI;
            }
            float y = shifter.process(x, ratio);
            return nasal.process(y) * 0.9f;
        }
    }

    /**
     * Two-tap delay-line pitch shifter with Hann cross-fade (no per-buffer resampling,
     * so no chirping). Latency ~ grain size / 2.
     */
    private static final class PitchShifter {
        private final float[] buf;
        private final int mask;
        private final int grain;
        private int write;
        private float phase;

        PitchShifter(int rate) {
            grain = Math.max(64, (int) (rate * 0.05)); // 50 ms
            int size = 1;
            while (size < grain * 2 + 8) {
                size <<= 1;
            }
            buf = new float[size];
            mask = size - 1;
        }

        float process(float x, float ratio) {
            buf[write & mask] = x;

            phase += (1f - ratio) / grain;
            phase -= (float) Math.floor(phase);
            float phase2 = phase + 0.5f;
            phase2 -= (float) Math.floor(phase2);

            float g1 = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * phase);
            float g2 = 1f - g1;
            float y = g1 * read(phase * grain) + g2 * read(phase2 * grain);

            write++;
            return y;
        }

        private float read(float delay) {
            float pos = write - delay;
            int i0 = (int) Math.floor(pos);
            float frac = pos - i0;
            return buf[i0 & mask] * (1f - frac) + buf[(i0 + 1) & mask] * frac;
        }
    }

    private static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        private Biquad(float b0, float b1, float b2, float a1, float a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        static Biquad peaking(int rate, float freq, float q, float gainDb) {
            double a = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * freq / rate;
            double alpha = Math.sin(w0) / (2.0 * q);
            double cos = Math.cos(w0);
            double a0 = 1.0 + alpha / a;
            return new Biquad(
                    (float) ((1.0 + alpha * a) / a0),
                    (float) ((-2.0 * cos) / a0),
                    (float) ((1.0 - alpha * a) / a0),
                    (float) ((-2.0 * cos) / a0),
                    (float) ((1.0 - alpha / a) / a0));
        }

        float process(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    private ParadoksVoice() {
    }
}
