package uz.paradoks.gram;

import java.io.IOException;

/** Small stereo 16-bit sample rate converter (linear interpolation, box filtered when going down). */
final class ParadoksResampler {

    interface Out {
        void write(short[] interleaved, int frames) throws IOException;
    }

    private final Out out;
    private final boolean passThrough;
    private final double step;
    private final int box;
    private double pos;
    private long srcIndex;
    private float prevL, prevR;
    private final short[] tmp = new short[8192];
    private int tmpN;

    ParadoksResampler(int srcRate, int dstRate, Out out) {
        this.out = out;
        this.passThrough = srcRate == dstRate;
        this.step = srcRate / (double) dstRate;
        this.box = step > 1.0 ? (int) Math.round(step) : 1;
    }

    void write(short[] d, int frames) throws IOException {
        if (passThrough) {
            out.write(d, frames);
            return;
        }
        for (int i = 0; i < frames; i++) {
            float l = d[i * 2], r = d[i * 2 + 1];
            if (box > 1) { // running mean over `box` frames: a cheap low pass
                l = (l + prevL * (box - 1)) / box;
                r = (r + prevR * (box - 1)) / box;
            }
            while (pos < srcIndex + 1) {
                double f = pos - srcIndex;
                if (f < 0) f = 0;
                emit((float) (prevL + (l - prevL) * f), (float) (prevR + (r - prevR) * f));
                pos += step;
            }
            prevL = l;
            prevR = r;
            srcIndex++;
        }
        flush();
    }

    void flush() throws IOException {
        if (tmpN > 0) {
            out.write(tmp, tmpN / 2);
            tmpN = 0;
        }
    }

    private void emit(float l, float r) throws IOException {
        if (tmpN + 2 > tmp.length) flush();
        tmp[tmpN++] = clip(l);
        tmp[tmpN++] = clip(r);
    }

    private static short clip(float v) {
        int s = Math.round(v);
        return (short) (s > 32767 ? 32767 : s < -32768 ? -32768 : s);
    }
}
