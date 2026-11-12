package uz.paradoks.gram;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import org.telegram.messenger.video.InputSurface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Renders the visualizer together with the (effect processed) sound into an .mp4 file, much faster than
 * real time. This is the native counterpart of the web "Ekran yozish" button.
 * <p>
 * Like a video editor it works as a small pipeline: the sound is processed and encoded on one thread,
 * while a second thread draws the frames and hands them to the hardware H.264 encoder through an OpenGL
 * surface (so the colour conversion is done by the GPU). If the GPU path cannot be set up the old,
 * slower software path is used.
 */
public final class ParadoksMusicVideo {

    public static final int WIDTH = 1024, HEIGHT = 576, FPS = 30;

    private ParadoksMusicVideo() {
    }

    private static final class GpuUnavailable extends Exception {
        GpuUnavailable(Throwable cause) {
            super(cause);
        }
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

        synchronized void addTrack(MediaFormat format, boolean audio) {
            int track = muxer.addTrack(format);
            if (audio) audioTrack = track; else videoTrack = track;
            if (!started && videoTrack >= 0 && audioTrack >= 0) {
                muxer.start();
                started = true;
                for (Pending p : pending) {
                    muxer.writeSampleData(p.track, p.data, p.info);
                }
                pending.clear();
            }
        }

        synchronized void write(boolean audio, ByteBuffer data, MediaCodec.BufferInfo info) {
            int track = audio ? audioTrack : videoTrack;
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

        synchronized void close() {
            try {
                if (started) muxer.stop();
            } catch (Throwable ignore) {
            }
            try {
                muxer.release();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Blocking. Returns false when cancelled (the output file is then incomplete).
     */
    public static boolean render(ParadoksMusicDsp.Pcm pcm, ParadoksMusicDsp.Params params, ParadoksVizRenderer.Settings settings,
                                 File out, ParadoksMusicDsp.Progress progress, ParadoksMusicDsp.Cancel cancel) throws IOException {
        try {
            return renderGpu(pcm, params, settings, out, progress, cancel);
        } catch (GpuUnavailable e) {
            return renderLegacy(pcm, params, settings, out, progress, cancel);
        }
    }

    // ------------------------------------------------------------------------------------------
    // fast path: audio thread + GL video thread
    // ------------------------------------------------------------------------------------------

    private static final class Job {
        final long index;
        final float[] db, wave;

        Job(long index, float[] db, float[] wave) {
            this.index = index;
            this.db = db;
            this.wave = wave;
        }
    }

    private static final Job END = new Job(-1, null, null);

    private static final String VS = "attribute vec4 aPos;attribute vec2 aTex;varying vec2 vTex;"
            + "void main(){gl_Position=aPos;vTex=aTex;}";
    private static final String FS = "precision mediump float;varying vec2 vTex;uniform sampler2D uTex;"
            + "void main(){gl_FragColor=texture2D(uTex,vTex);}";

    private static final class VideoWorker extends Thread {
        final MediaCodec venc;
        final Surface surface;
        final Writer writer;
        final ParadoksVizRenderer.Settings settings;
        final int rate;
        final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(8);
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean initOk;
        volatile boolean stop;
        volatile Throwable error;

        VideoWorker(MediaCodec venc, Surface surface, Writer writer, ParadoksVizRenderer.Settings settings, int rate) {
            super("ParadoksMusicVideoGL");
            this.venc = venc;
            this.surface = surface;
            this.writer = writer;
            this.settings = settings;
            this.rate = rate;
        }

        @Override
        public void run() {
            InputSurface input = null;
            Bitmap bitmap = null;
            int program = 0;
            int[] tex = new int[1];
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                try {
                    input = new InputSurface(surface);
                    input.makeCurrent();
                    program = buildProgram();
                    GLES20.glGenTextures(1, tex, 0);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                    bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                    GLES20.glViewport(0, 0, WIDTH, HEIGHT);
                    initOk = true;
                } catch (Throwable t) {
                    error = t;
                    return;
                } finally {
                    ready.countDown();
                }

                final Canvas canvas = new Canvas(bitmap);
                final float dpr = WIDTH / 400f;
                final ParadoksVizRenderer renderer = new ParadoksVizRenderer(settings);
                final ParadoksMusicDsp.Spectrum sp = new ParadoksMusicDsp.Spectrum();
                final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                quad.put(new float[]{-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f}).position(0);
                final int aPos = GLES20.glGetAttribLocation(program, "aPos");
                final int aTex = GLES20.glGetAttribLocation(program, "aTex");
                final int uTex = GLES20.glGetUniformLocation(program, "uTex");

                while (!stop) {
                    Job job = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (job == null) {
                        drain(venc, info, writer, false, false);
                        continue;
                    }
                    if (job == END) break;
                    System.arraycopy(job.db, 0, sp.db, 0, sp.db.length);
                    System.arraycopy(job.wave, 0, sp.wave, 0, sp.wave.length);
                    renderer.draw(canvas, WIDTH, HEIGHT, dpr, sp, rate, true, job.index * 1000L / FPS);

                    GLES20.glUseProgram(program);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
                    GLES20.glUniform1i(uTex, 0);
                    quad.position(0);
                    GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
                    GLES20.glEnableVertexAttribArray(aPos);
                    quad.position(2);
                    GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
                    GLES20.glEnableVertexAttribArray(aTex);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    input.setPresentationTime(job.index * 1_000_000_000L / FPS);
                    input.swapBuffers();
                    drain(venc, info, writer, false, false);
                }
                if (!stop) {
                    venc.signalEndOfInputStream();
                    drain(venc, info, writer, true, false);
                }
            } catch (Throwable t) {
                error = t;
            } finally {
                try {
                    if (program != 0) GLES20.glDeleteProgram(program);
                    if (tex[0] != 0) GLES20.glDeleteTextures(1, tex, 0);
                } catch (Throwable ignore) {
                }
                if (input != null) {
                    try {
                        input.release();
                    } catch (Throwable ignore) {
                    }
                }
                if (bitmap != null) bitmap.recycle();
            }
        }

        private static int buildProgram() {
            int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("GL link failed");
            return p;
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("GL shader failed");
            return s;
        }
    }

    private static boolean renderGpu(ParadoksMusicDsp.Pcm pcm, ParadoksMusicDsp.Params params, ParadoksVizRenderer.Settings settings,
                                     File out, ParadoksMusicDsp.Progress progress, ParadoksMusicDsp.Cancel cancel) throws IOException, GpuUnavailable {
        final int rate = pcm.sampleRate;
        final ParadoksMusicDsp.Params p = params.copy();
        final ParadoksMusicDsp.Engine engine = new ParadoksMusicDsp.Engine(pcm, p);
        final ParadoksMusicDsp.Spectrum spectrum = new ParadoksMusicDsp.Spectrum();

        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);

        MediaCodec venc = null, aenc = null;
        Writer writer = null;
        VideoWorker worker = null;
        boolean ok = false;
        boolean setupDone = false;
        try {
            try {
                venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface surface = venc.createInputSurface();
                aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                venc.start();
                aenc.start();
                writer = new Writer(out);
                worker = new VideoWorker(venc, surface, writer, settings.copy(), rate);
                worker.start();
                if (!worker.ready.await(10, TimeUnit.SECONDS) || !worker.initOk) {
                    throw new RuntimeException("GL init", worker.error);
                }
                setupDone = true;
            } catch (Throwable t) {
                if (!setupDone) throw new GpuUnavailable(t);
                throw t;
            }

            final MediaCodec.BufferInfo ainfo = new MediaCodec.BufferInfo();
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
            boolean cancelled = false;

            while (true) {
                if ((cancel != null && cancel.isCancelled()) || worker.error != null) {
                    cancelled = true;
                    break;
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

                // --- spectrum window -> the drawing thread
                System.arraycopy(winL, n, winL, 0, win - Math.min(n, win));
                System.arraycopy(winR, n, winR, 0, win - Math.min(n, win));
                int copy = Math.min(n, win);
                System.arraycopy(l, n - copy, winL, win - copy, copy);
                System.arraycopy(r, n - copy, winR, win - copy, copy);
                spectrum.analyze(winL, winR, win);
                Job job = new Job(frameIndex, spectrum.db.clone(), spectrum.wave.clone());
                while (!worker.queue.offer(job, 50, TimeUnit.MILLISECONDS)) {
                    if ((cancel != null && cancel.isCancelled()) || worker.error != null || !worker.isAlive()) {
                        cancelled = true;
                        break;
                    }
                    drain(aenc, ainfo, writer, false, true);
                }
                if (cancelled) break;

                drain(aenc, ainfo, writer, false, true);
                frameIndex++;
                if (progress != null && frameIndex % 15 == 0) {
                    progress.onProgress((float) Math.min(1.0, engine.pos / total));
                }
                if (n < want) {
                    break;
                }
            }

            if (cancelled) {
                worker.stop = true;
                worker.join(3000);
                if (worker.error != null && !(cancel != null && cancel.isCancelled())) {
                    throw new IOException(String.valueOf(worker.error.getMessage()), worker.error);
                }
                return false;
            }

            // end of streams
            while (!worker.queue.offer(END, 50, TimeUnit.MILLISECONDS)) {
                if (worker.error != null || !worker.isAlive()) break;
                drain(aenc, ainfo, writer, false, true);
            }
            int idx;
            while ((idx = aenc.dequeueInputBuffer(10_000)) < 0) {
                drain(aenc, ainfo, writer, false, true);
            }
            aenc.queueInputBuffer(idx, 0, 0, audioFramesSent * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(aenc, ainfo, writer, true, true);
            worker.join();
            if (worker.error != null) {
                throw new IOException(String.valueOf(worker.error.getMessage()), worker.error);
            }
            ok = true;
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | GpuUnavailable | RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(String.valueOf(t.getMessage()), t);
        } finally {
            if (worker != null) {
                worker.stop = true;
                try {
                    worker.join(3000);
                } catch (InterruptedException ignore) {
                }
            }
            if (venc != null) {
                try {
                    venc.stop();
                } catch (Throwable ignore) {
                }
                try {
                    venc.release();
                } catch (Throwable ignore) {
                }
            }
            if (aenc != null) {
                try {
                    aenc.stop();
                } catch (Throwable ignore) {
                }
                try {
                    aenc.release();
                } catch (Throwable ignore) {
                }
            }
            if (writer != null) writer.close();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // slow path (software colour conversion), only used when OpenGL cannot be set up
    // ------------------------------------------------------------------------------------------

    private static boolean renderLegacy(ParadoksMusicDsp.Pcm pcm, ParadoksMusicDsp.Params params, ParadoksVizRenderer.Settings settings,
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

                ParadoksMusicDsp.toShorts(l, r, n, pcmOut);
                audioFramesSent = feedAudio(aenc, ainfo, writer, pcmOut, n, audioFramesSent, rate);

                System.arraycopy(winL, n, winL, 0, win - Math.min(n, win));
                System.arraycopy(winR, n, winR, 0, win - Math.min(n, win));
                int copy = Math.min(n, win);
                System.arraycopy(l, n - copy, winL, win - copy, copy);
                System.arraycopy(r, n - copy, winR, win - copy, copy);
                spectrum.analyze(winL, winR, win);

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
            if (writer != null) writer.close();
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
                writer.addTrack(codec.getOutputFormat(), audio);
            } else if (out >= 0) {
                ByteBuffer data = codec.getOutputBuffer(out);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size > 0 && data != null) {
                    writer.write(audio, data, info);
                }
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(out, false);
                if (end) return;
            }
        }
    }
}
