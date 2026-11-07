package uz.paradoks.gram;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Renders the visualizer together with the (effect processed) sound into an .mp4 file, faster than
 * real time. This is the native counterpart of the web "Ekran yozish" button.
 */
public final class ParadoksMusicVideo {

    public static final int WIDTH = 1024, HEIGHT = 576, FPS = 30;

    private ParadoksMusicVideo() {
    }

    private static final class Pending {
        int track;
        ByteBuffer data;
        MediaCodec.BufferInfo info;
    }

    private static final class Writer {
        final MediaMuxer muxer;
        final ArrayList<Pending> pending = new ArrayList<>();
        int videoTrack = -1, audioTrack = -1;
        boolean started;

        Writer(File out) throws IOException {
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        void tryStart() {
            if (!started && videoTrack >= 0 && audioTrack >= 0) {
                muxer.start();
                started = true;
                for (Pending p : pending) {
                    muxer.writeSampleData(p.track, p.data, p.info);
                }
                pending.clear();
            }
        }

        void write(int track, ByteBuffer data, MediaCodec.BufferInfo info) {
            if (started) {
                muxer.writeSampleData(track, data, info);
            } else {
                Pending p = new Pending();
                p.track = track;
                p.data = ByteBuffer.allocate(info.size);
                data.position(info.offset);
                data.limit(info.offset + info.size);
                p.data.put(data);
                p.data.flip();
                p.info = new MediaCodec.BufferInfo();
                p.info.set(0, info.size, info.presentationTimeUs, info.flags);
                pending.add(p);
            }
        }
    }

    /**
     * Blocking. Returns false when cancelled (the output file is then incomplete).
     */
    public static boolean render(ParadoksMusicDsp.Pcm pcm, ParadoksMusicDsp.Params params, ParadoksVizRenderer.Settings settings,
                                 File out, ParadoksMusicDsp.Progress progress, ParadoksMusicDsp.Cancel cancel) throws IOException {
        final int rate = pcm.sampleRate;
        ParadoksMusicDsp.Params p = params.copy();
        ParadoksMusicDsp.Engine engine = new ParadoksMusicDsp.Engine(pcm, p);
        ParadoksVizRenderer renderer = new ParadoksVizRenderer(settings.copy());
        ParadoksMusicDsp.Spectrum spectrum = new ParadoksMusicDsp.Spectrum();

        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);

        MediaCodec venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaCodec aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        Writer writer = null;
        boolean ok = false;
        try {
            venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            venc.start();
            aenc.start();
            writer = new Writer(out);

            final Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            final int[] pixels = new int[WIDTH * HEIGHT];
            final float dpr = WIDTH / 400f;
            final MediaCodec.BufferInfo vinfo = new MediaCodec.BufferInfo(), ainfo = new MediaCodec.BufferInfo();

            final int win = ParadoksMusicDsp.Spectrum.SIZE;
            float[] winL = new float[win], winR = new float[win];
            float[] l = new float[rate / FPS + 8], r = new float[rate / FPS + 8];
            short[] pcmOut = new short[l.length * 2];

            final long tailFrames = (p.echo || p.reverb) ? rate * 4L : 0L;
            long tailDone = 0;
            boolean sourceDone = false;
            long audioFramesSent = 0;
            long frameIndex = 0;
            final double total = pcm.frames;

            while (true) {
                if (cancel != null && cancel.isCancelled()) {
                    return false;
                }
                int want = (int) ((frameIndex + 1) * (long) rate / FPS - frameIndex * (long) rate / FPS);
                int n = 0;
                if (!sourceDone) {
                    n = engine.fill(l, r, want);
                    if (n < want) {
                        sourceDone = true;
                    }
                }
                if (sourceDone && n < want) {
                    int fill = want - n;
                    if (tailDone < tailFrames) {
                        int t = (int) Math.min(fill, tailFrames - tailDone);
                        float[] tl = new float[t], tr = new float[t];
                        engine.fillSilence(tl, tr, t);
                        System.arraycopy(tl, 0, l, n, t);
                        System.arraycopy(tr, 0, r, n, t);
                        n += t;
                        tailDone += t;
                    }
                }
                if (n <= 0) {
                    break;
                }

                // --- audio
                ParadoksMusicDsp.toShorts(l, r, n, pcmOut);
                audioFramesSent = feedAudio(aenc, ainfo, writer, pcmOut, n, audioFramesSent, rate);

                // --- spectrum window
                System.arraycopy(winL, n, winL, 0, win - Math.min(n, win));
                System.arraycopy(winR, n, winR, 0, win - Math.min(n, win));
                int copy = Math.min(n, win);
                System.arraycopy(l, n - copy, winL, win - copy, copy);
                System.arraycopy(r, n - copy, winR, win - copy, copy);
                spectrum.analyze(winL, winR, win);

                // --- video frame
                renderer.draw(canvas, WIDTH, HEIGHT, dpr, spectrum, rate, true, frameIndex * 1000L / FPS);
                bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
                feedVideo(venc, vinfo, writer, pixels, frameIndex * 1_000_000L / FPS);

                drain(aenc, ainfo, writer, false, true);
                frameIndex++;
                if (progress != null && frameIndex % 15 == 0) {
                    progress.onProgress((float) Math.min(1.0, engine.pos / total));
                }
                if (n < want) {
                    break;
                }
            }

            // end of streams
            int idx;
            while ((idx = venc.dequeueInputBuffer(10_000)) < 0) {
                drain(venc, vinfo, writer, false, false);
                drain(aenc, ainfo, writer, false, true);
            }
            venc.queueInputBuffer(idx, 0, 0, frameIndex * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            while ((idx = aenc.dequeueInputBuffer(10_000)) < 0) {
                drain(aenc, ainfo, writer, false, true);
                drain(venc, vinfo, writer, false, false);
            }
            aenc.queueInputBuffer(idx, 0, 0, audioFramesSent * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(venc, vinfo, writer, true, false);
            drain(aenc, ainfo, writer, true, true);
            ok = true;
            return true;
        } finally {
            try {
                venc.stop();
            } catch (Throwable ignore) {
            }
            try {
                aenc.stop();
            } catch (Throwable ignore) {
            }
            venc.release();
            aenc.release();
            if (writer != null) {
                try {
                    if (writer.started) writer.muxer.stop();
                } catch (Throwable ignore) {
                }
                try {
                    writer.muxer.release();
                } catch (Throwable ignore) {
                }
            }
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
    }

    private static long feedAudio(MediaCodec aenc, MediaCodec.BufferInfo info, Writer writer, short[] d, int frames, long sent, int rate) {
        int offset = 0, total = frames * 2;
        while (offset < total) {
            int idx = aenc.dequeueInputBuffer(10_000);
            if (idx < 0) {
                drain(aenc, info, writer, false, true);
                continue;
            }
            ByteBuffer buf = aenc.getInputBuffer(idx);
            buf.clear();
            buf.order(ByteOrder.LITTLE_ENDIAN);
            int n = Math.min(total - offset, buf.capacity() / 2) & ~1;
            for (int i = 0; i < n; i++) buf.putShort(d[offset + i]);
            aenc.queueInputBuffer(idx, 0, n * 2, sent * 1_000_000L / rate, 0);
            sent += n / 2;
            offset += n;
        }
        return sent;
    }

    private static void feedVideo(MediaCodec venc, MediaCodec.BufferInfo info, Writer writer, int[] argb, long ptsUs) {
        int idx;
        while ((idx = venc.dequeueInputBuffer(10_000)) < 0) {
            drain(venc, info, writer, false, false);
        }
        Image image = venc.getInputImage(idx);
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yb = planes[0].getBuffer(), ub = planes[1].getBuffer(), vb = planes[2].getBuffer();
        final int ys = planes[0].getRowStride(), yp = planes[0].getPixelStride();
        final int us = planes[1].getRowStride(), up = planes[1].getPixelStride();
        final int vs = planes[2].getRowStride(), vp = planes[2].getPixelStride();
        for (int y = 0; y < HEIGHT; y++) {
            int row = y * WIDTH;
            boolean chroma = (y & 1) == 0;
            for (int x = 0; x < WIDTH; x++) {
                int c = argb[row + x];
                int R = (c >> 16) & 0xFF, G = (c >> 8) & 0xFF, B = c & 0xFF;
                yb.put(y * ys + x * yp, (byte) (((66 * R + 129 * G + 25 * B + 128) >> 8) + 16));
                if (chroma && (x & 1) == 0) {
                    int cx = x >> 1, cy = y >> 1;
                    ub.put(cy * us + cx * up, (byte) (((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128));
                    vb.put(cy * vs + cx * vp, (byte) (((112 * R - 94 * G - 18 * B + 128) >> 8) + 128));
                }
            }
        }
        venc.queueInputBuffer(idx, 0, WIDTH * HEIGHT * 3 / 2, ptsUs, 0);
        drain(venc, info, writer, false, false);
    }

    private static void drain(MediaCodec codec, MediaCodec.BufferInfo info, Writer writer, boolean untilEnd, boolean audio) {
        while (true) {
            int out = codec.dequeueOutputBuffer(info, untilEnd ? 10_000 : 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd) return;
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                int track = writer.muxer.addTrack(codec.getOutputFormat());
                if (audio) writer.audioTrack = track; else writer.videoTrack = track;
                writer.tryStart();
            } else if (out >= 0) {
                ByteBuffer data = codec.getOutputBuffer(out);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size > 0 && data != null) {
                    writer.write(audio ? writer.audioTrack : writer.videoTrack, data, info);
                }
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(out, false);
                if (end) return;
            }
        }
    }
}
