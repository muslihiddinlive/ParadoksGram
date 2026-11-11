package uz.paradoks.gram;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Pure Java MPEG-1 Layer III (MP3) encoder. Android has no MP3 encoder, so this one is built in.
 * <p>
 * Constant bitrate, stereo, long blocks. Every frame gets ONE quantiser step for all four granule
 * channels (the smallest step that fits the frame budget), i.e. the quantisation noise is spread
 * evenly and the bits go where the signal needs them. The polyphase filter bank and the Huffman tables
 * come from LAME / JUMP3R (LGPL), see {@link ParadoksMp3Tables}.
 */
public final class ParadoksMp3Encoder {

    public static final int[] BITRATES = {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] RATES = {44100, 48000, 32000};

    /** Position of subband b inside a polyphase output vector. */
    private static final int[] ORDER = {
            0, 1, 16, 17, 8, 9, 24, 25, 4, 5, 20, 21, 12, 13, 28, 29,
            2, 3, 18, 19, 10, 11, 26, 27, 6, 7, 22, 23, 14, 15, 30, 31};

    /** region0_count / region1_count by number of scale factor bands used by the big values. */
    private static final int[][] SUBDV = {
            {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 1}, {1, 1}, {1, 1}, {1, 2}, {2, 2}, {2, 3}, {2, 3},
            {3, 4}, {3, 4}, {3, 4}, {4, 5}, {4, 5}, {4, 6}, {5, 6}, {5, 6}, {5, 7}, {6, 7}, {6, 7}};

    private static final int INF = Integer.MAX_VALUE / 4;
    private static final int MAX_IX = 8191 + 15;

    private static final float[] IPOW20 = new float[256];
    private static final float[] CS = new float[8], CA = new float[8];
    private static final float[][] MDCT = new float[18][36];

    /** Scale between the polyphase/MDCT output and the quantiser (found by measurement). */
    private static final double MDCT_SCALE = 8.1073e-12;

    static {
        for (int g = 0; g < 256; g++) {
            IPOW20[g] = (float) Math.pow(2.0, -(g - 210) * 0.1875);
        }
        final double[] c = {-0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037};
        for (int i = 0; i < 8; i++) {
            double sq = Math.sqrt(1.0 + c[i] * c[i]);
            CS[i] = (float) (1.0 / sq);
            CA[i] = (float) (c[i] / sq);
        }
        for (int k = 0; k < 18; k++) {
            for (int n = 0; n < 36; n++) {
                MDCT[k][n] = (float) (MDCT_SCALE * Math.sin(Math.PI / 36.0 * (n + 0.5))
                        * Math.cos(Math.PI / 72.0 * (2 * n + 19) * (2 * k + 1)));
            }
        }
    }

    /** One granule of one channel. */
    private static final class Unit {
        final float[] xr = new float[576];
        final float[] a34 = new float[576];
        final int[] ix = new int[576];
        int bigValues, count1End, count1Table, r0, r1, part23;
        final int[] table = new int[3];
    }

    private static final class BitWriter {
        final byte[] buf;
        int bitPos;

        BitWriter(byte[] buf) {
            this.buf = buf;
        }

        void put(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) {
                    buf[bitPos >> 3] |= (byte) (0x80 >> (bitPos & 7));
                }
                bitPos++;
            }
        }
    }

    private final OutputStream out;
    private final int rate, srIdx, brIdx, kbps;
    private final int[] sfb;
    private final float[][] t = new float[2][1056];
    private int fill = 512; // leading silence so that the first window is complete
    private float[][][] sbCur = new float[2][18][32];
    private float[][][] sbPrev = new float[2][18][32];
    private final Unit[][] units = new Unit[2][2];
    private int grDone;
    private int padAcc;
    private final byte[] frame = new byte[2048];
    private final float[] x36 = new float[36];
    private long framesWritten;

    public ParadoksMp3Encoder(OutputStream out, int sampleRate, int kbps) {
        int si = -1;
        for (int i = 0; i < RATES.length; i++) {
            if (RATES[i] == sampleRate) si = i;
        }
        if (si < 0) {
            throw new IllegalArgumentException("sample rate " + sampleRate);
        }
        int bi = -1;
        for (int i = 0; i < BITRATES.length; i++) {
            if (BITRATES[i] == kbps) bi = i;
        }
        if (bi < 0) {
            throw new IllegalArgumentException("bitrate " + kbps);
        }
        this.out = out;
        this.rate = sampleRate;
        this.srIdx = si;
        this.brIdx = bi + 1;
        this.kbps = kbps;
        this.sfb = ParadoksMp3Tables.SFB[si];
        for (int g = 0; g < 2; g++) {
            for (int c = 0; c < 2; c++) units[g][c] = new Unit();
        }
    }

    public static boolean supportsRate(int rate) {
        for (int r : RATES) {
            if (r == rate) return true;
        }
        return false;
    }

    /** Interleaved 16-bit stereo. */
    public void write(short[] d, int frames) throws IOException {
        for (int i = 0; i < frames; i++) {
            t[0][fill] = d[i * 2];
            t[1][fill] = d[i * 2 + 1];
            if (++fill >= 1055) {
                granule();
            }
        }
    }

    public void finish() throws IOException {
        short[] zeros = new short[2];
        int n = 0;
        while (n < 3000 || grDone != 0) {
            write(zeros, 1);
            n++;
        }
        out.flush();
    }

    public long framesWritten() {
        return framesWritten;
    }

    // ------------------------------------------------------------------------------------------
    // analysis
    // ------------------------------------------------------------------------------------------

    private void granule() throws IOException {
        for (int ch = 0; ch < 2; ch++) {
            float[][] cur = sbCur[ch];
            float[][] old = sbPrev[ch];
            for (int s = 0; s < 18; s++) {
                ParadoksMp3Tables.windowSubband(t[ch], 286 + 32 * s, cur[s]);
            }
            // compensate the inversion of the analysis filter
            for (int s = 1; s < 18; s += 2) {
                for (int b = 1; b < 32; b += 2) cur[s][b] = -cur[s][b];
            }
            Unit u = units[grDone][ch];
            float[] xr = u.xr;
            for (int band = 0; band < 32; band++) {
                int p = ORDER[band];
                for (int k = 0; k < 18; k++) {
                    x36[k] = old[k][p];
                    x36[18 + k] = cur[k][p];
                }
                int base = band * 18;
                for (int k = 0; k < 18; k++) {
                    float[] m = MDCT[k];
                    float sum = 0f;
                    for (int n = 0; n < 36; n++) sum += m[n] * x36[n];
                    xr[base + k] = sum;
                }
                if (band != 0) {
                    for (int k = 7; k >= 0; k--) {
                        float lo = xr[base - 1 - k], hi = xr[base + k];
                        xr[base - 1 - k] = lo * CS[k] + hi * CA[k];
                        xr[base + k] = hi * CS[k] - lo * CA[k];
                    }
                }
            }
            for (int i = 0; i < 576; i++) {
                float a = Math.abs(xr[i]);
                u.a34[i] = (float) Math.sqrt(a * Math.sqrt(a));
            }
            float[][] tmp = sbPrev[ch];
            sbPrev[ch] = sbCur[ch];
            sbCur[ch] = tmp;
            System.arraycopy(t[ch], 576, t[ch], 0, fill - 576);
        }
        fill -= 576;
        if (++grDone == 2) {
            encodeFrame();
            grDone = 0;
        }
    }

    // ------------------------------------------------------------------------------------------
    // quantisation + bit counting
    // ------------------------------------------------------------------------------------------

    /** Quantises with global gain g and counts the bits. Returns INF when it cannot be coded. */
    private int quantise(Unit u, int g) {
        final float ip = IPOW20[g];
        final int[] ix = u.ix;
        final float[] a = u.a34;
        for (int i = 0; i < 576; i++) {
            int v = (int) (a[i] * ip + 0.4054f);
            if (v > MAX_IX) return INF;
            ix[i] = v;
        }
        int i = 576;
        while (i > 1 && ix[i - 1] == 0 && ix[i - 2] == 0) i -= 2;
        final int end = i;
        while (i > 3 && ix[i - 1] <= 1 && ix[i - 2] <= 1 && ix[i - 3] <= 1 && ix[i - 4] <= 1) i -= 4;
        u.bigValues = i;
        u.count1End = end;

        int bitsA = 0, bitsB = 0;
        final int[] la = ParadoksMp3Tables.HL[32], lb = ParadoksMp3Tables.HL[33];
        for (int q = i; q < end; q += 4) {
            int p = (ix[q] != 0 ? 8 : 0) | (ix[q + 1] != 0 ? 4 : 0) | (ix[q + 2] != 0 ? 2 : 0) | (ix[q + 3] != 0 ? 1 : 0);
            bitsA += la[p];
            bitsB += lb[p];
        }
        u.count1Table = bitsB < bitsA ? 1 : 0;
        int bits = Math.min(bitsA, bitsB);

        // regions
        final int bv = u.bigValues;
        int s = 1;
        while (s < 22 && sfb[s] < bv) s++;
        int r0 = SUBDV[s][0], r1 = SUBDV[s][1];
        int a1 = Math.min(sfb[r0 + 1], bv);
        int a2 = Math.min(sfb[r0 + r1 + 2], bv);
        u.r0 = r0;
        u.r1 = r1;
        bits += region(u, 0, 0, a1);
        bits += region(u, 1, a1, a2);
        bits += region(u, 2, a2, bv);
        u.part23 = bits;
        return bits > 4095 ? INF : bits;
    }

    private int region(Unit u, int slot, int from, int to) {
        if (to <= from) {
            u.table[slot] = 0;
            return 0;
        }
        final int[] ix = u.ix;
        int max = 0;
        for (int i = from; i < to; i++) {
            if (ix[i] > max) max = ix[i];
        }
        if (max == 0) {
            u.table[slot] = 0;
            return 0;
        }
        int best = INF, bestT = 1;
        if (max <= 15) {
            final int[] cand;
            if (max <= 1) cand = C1;
            else if (max <= 2) cand = C2;
            else if (max <= 3) cand = C3;
            else if (max <= 5) cand = C5;
            else if (max <= 7) cand = C7;
            else cand = C15;
            for (int t : cand) {
                int b = pairBits(ix, from, to, t);
                if (b < best) {
                    best = b;
                    bestT = t;
                }
            }
        } else {
            final int need = max - 15;
            int ta = 0, tb = 0;
            for (int t = 16; t <= 23; t++) {
                if (ParadoksMp3Tables.LINMAX[t] >= need) {
                    ta = t;
                    break;
                }
            }
            for (int t = 24; t <= 31; t++) {
                if (ParadoksMp3Tables.LINMAX[t] >= need) {
                    tb = t;
                    break;
                }
            }
            if (ta != 0) {
                best = pairBits(ix, from, to, ta);
                bestT = ta;
            }
            if (tb != 0) {
                int b = pairBits(ix, from, to, tb);
                if (b < best) {
                    best = b;
                    bestT = tb;
                }
            }
        }
        u.table[slot] = bestT;
        return best;
    }

    private static final int[] C1 = {1}, C2 = {2, 3}, C3 = {5, 6}, C5 = {7, 8, 9}, C7 = {10, 11, 12}, C15 = {13, 15};

    private static int pairBits(int[] ix, int from, int to, int t) {
        final int[] hl = ParadoksMp3Tables.HL[t];
        final int xlen = t > 15 ? 16 : ParadoksMp3Tables.XLEN[t];
        final int lin = t > 15 ? ParadoksMp3Tables.XLEN[t] : 0;
        int bits = 0;
        for (int i = from; i < to; i += 2) {
            int x = ix[i], y = ix[i + 1];
            if (lin != 0) {
                if (x > 14) {
                    bits += lin;
                    x = 15;
                }
                if (y > 14) {
                    bits += lin;
                    y = 15;
                }
            }
            bits += hl[x * xlen + y];
        }
        return bits;
    }

    // ------------------------------------------------------------------------------------------
    // bitstream
    // ------------------------------------------------------------------------------------------

    private void encodeFrame() throws IOException {
        int total = 144 * kbps * 1000;
        int bytes = total / rate;
        padAcc += total % rate;
        int pad = 0;
        if (padAcc >= rate) {
            padAcc -= rate;
            pad = 1;
        }
        bytes += pad;
        final int budget = bytes * 8 - 32 - 256;

        int lo = 0, hi = 255;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (fits(mid, budget)) hi = mid;
            else lo = mid + 1;
        }
        final int g = lo;
        if (!fits(g, budget)) {
            // cannot happen with g = 255, but never write a broken frame
            for (int gr = 0; gr < 2; gr++) {
                for (int ch = 0; ch < 2; ch++) java.util.Arrays.fill(units[gr][ch].ix, 0);
            }
            fits(255, budget);
        }

        java.util.Arrays.fill(frame, 0, bytes, (byte) 0);
        BitWriter bw = new BitWriter(frame);
        bw.put(0x7FF, 11);
        bw.put(3, 2);       // MPEG-1
        bw.put(1, 2);       // layer III
        bw.put(1, 1);       // no CRC
        bw.put(brIdx, 4);
        bw.put(srIdx, 2);
        bw.put(pad, 1);
        bw.put(0, 1);
        bw.put(0, 2);       // stereo
        bw.put(0, 2);
        bw.put(0, 1);
        bw.put(1, 1);       // original
        bw.put(0, 2);
        // side info
        bw.put(0, 9);       // main_data_begin
        bw.put(0, 3);
        bw.put(0, 8);       // scfsi
        for (int gr = 0; gr < 2; gr++) {
            for (int ch = 0; ch < 2; ch++) {
                Unit u = units[gr][ch];
                bw.put(u.part23, 12);
                bw.put(u.bigValues >> 1, 9);
                bw.put(g, 8);
                bw.put(0, 4);   // scalefac_compress
                bw.put(0, 1);   // window switching
                bw.put(u.table[0], 5);
                bw.put(u.table[1], 5);
                bw.put(u.table[2], 5);
                bw.put(u.r0, 4);
                bw.put(u.r1, 3);
                bw.put(0, 1);   // preflag
                bw.put(0, 1);   // scalefac_scale
                bw.put(u.count1Table, 1);
            }
        }
        // main data
        for (int gr = 0; gr < 2; gr++) {
            for (int ch = 0; ch < 2; ch++) {
                writeUnit(bw, units[gr][ch]);
            }
        }
        out.write(frame, 0, bytes);
        framesWritten++;
    }

    private boolean fits(int g, int budget) {
        int sum = 0;
        for (int gr = 0; gr < 2; gr++) {
            for (int ch = 0; ch < 2; ch++) {
                int b = quantise(units[gr][ch], g);
                if (b >= INF) return false;
                sum += b;
            }
        }
        return sum <= budget;
    }

    private void writeUnit(BitWriter bw, Unit u) {
        final int bv = u.bigValues;
        int a1 = Math.min(sfb[u.r0 + 1], bv);
        int a2 = Math.min(sfb[u.r0 + u.r1 + 2], bv);
        writeRegion(bw, u, u.table[0], 0, a1);
        writeRegion(bw, u, u.table[1], a1, a2);
        writeRegion(bw, u, u.table[2], a2, bv);
        final int[] hb = ParadoksMp3Tables.HB[32 + u.count1Table];
        final int[] hl = ParadoksMp3Tables.HL[32 + u.count1Table];
        final float[] xr = u.xr;
        final int[] ix = u.ix;
        for (int q = bv; q < u.count1End; q += 4) {
            int p = 0, signs = 0;
            for (int k = 0; k < 4; k++) {
                if (ix[q + k] != 0) {
                    p |= 8 >> k;
                    signs = signs * 2 + (xr[q + k] < 0 ? 1 : 0);
                }
            }
            bw.put(hb[p] + signs, hl[p]);
        }
    }

    private static void writeRegion(BitWriter bw, Unit u, int t, int from, int to) {
        if (t == 0 || to <= from) return;
        final int[] hb = ParadoksMp3Tables.HB[t];
        final int[] hl = ParadoksMp3Tables.HL[t];
        final int xlen = t > 15 ? 16 : ParadoksMp3Tables.XLEN[t];
        final int lin = t > 15 ? ParadoksMp3Tables.XLEN[t] : 0;
        final int[] ix = u.ix;
        final float[] xr = u.xr;
        for (int i = from; i < to; i += 2) {
            int x = ix[i], y = ix[i + 1];
            int cx = x, cy = y, lx = 0, ly = 0;
            boolean ex = false, ey = false;
            if (lin != 0) {
                if (x > 14) {
                    lx = x - 15;
                    cx = 15;
                    ex = true;
                }
                if (y > 14) {
                    ly = y - 15;
                    cy = 15;
                    ey = true;
                }
            }
            int idx = cx * xlen + cy;
            int nz = (x != 0 ? 1 : 0) + (y != 0 ? 1 : 0);
            bw.put(hb[idx], hl[idx] - nz);
            if (ex) bw.put(lx, lin);
            if (x != 0) bw.put(xr[i] < 0 ? 1 : 0, 1);
            if (ey) bw.put(ly, lin);
            if (y != 0) bw.put(xr[i + 1] < 0 ? 1 : 0, 1);
        }
    }

    // ------------------------------------------------------------------------------------------
    // file sink with sample rate conversion
    // ------------------------------------------------------------------------------------------

    /** The sample rate an MP3 file will have for a given source rate. */
    public static int targetRate(int src) {
        if (supportsRate(src)) return src;
        if (src % 11025 == 0) return 44100;
        if (src % 48000 == 0 || src % 24000 == 0 || src % 12000 == 0) return 48000;
        if (src % 32000 == 0 || src % 16000 == 0 || src % 8000 == 0) return 32000;
        return 44100;
    }

    public static final class Sink implements ParadoksMusicDsp.Sink {
        private final OutputStream os;
        private final ParadoksMp3Encoder enc;
        private final ParadoksResampler resampler;

        public Sink(File file, int srcRate, int kbps, String title) throws IOException {
            int dstRate = targetRate(srcRate);
            os = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024);
            if (title != null && !title.isEmpty()) {
                writeId3(os, title);
            }
            enc = new ParadoksMp3Encoder(os, dstRate, kbps);
            resampler = new ParadoksResampler(srcRate, dstRate, enc::write);
        }

        @Override
        public void write(short[] d, int frames) throws IOException {
            resampler.write(d, frames);
        }

        @Override
        public void finish() throws IOException {
            resampler.flush();
            enc.finish();
            os.close();
        }

        @Override
        public void abort() {
            try {
                os.close();
            } catch (IOException ignore) {
            }
        }
    }

    private static void writeId3(OutputStream os, String title) throws IOException {
        byte[] text = title.getBytes("UTF-16LE");
        int frameSize = 1 + 2 + text.length;
        int tagSize = 10 + frameSize;
        byte[] h = new byte[10];
        h[0] = 'I';
        h[1] = 'D';
        h[2] = '3';
        h[3] = 3;
        h[4] = 0;
        h[5] = 0;
        h[6] = (byte) ((tagSize >> 21) & 0x7F);
        h[7] = (byte) ((tagSize >> 14) & 0x7F);
        h[8] = (byte) ((tagSize >> 7) & 0x7F);
        h[9] = (byte) (tagSize & 0x7F);
        os.write(h);
        byte[] f = new byte[10];
        f[0] = 'T';
        f[1] = 'I';
        f[2] = 'T';
        f[3] = '2';
        f[4] = (byte) ((frameSize >> 24) & 0xFF);
        f[5] = (byte) ((frameSize >> 16) & 0xFF);
        f[6] = (byte) ((frameSize >> 8) & 0xFF);
        f[7] = (byte) (frameSize & 0xFF);
        os.write(f);
        os.write(1);
        os.write(0xFF);
        os.write(0xFE);
        os.write(text);
    }
}
