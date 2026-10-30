package ru.tiktokclipper.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.tiktokclipper.core.ApiError;
import ru.tiktokclipper.core.JobQueue;
import ru.tiktokclipper.core.Subs;

/**
 * Сборка вертикального клипа 1080x1920 аппаратными кодеками телефона:
 * декодер -> текстура OpenGL -> раскладка (single | split) с зеркалом -> субтитры поверх (не зеркалятся)
 * -> кодировщик H.264 -> MP4 вместе с готовой AAC-дорожкой.
 */
final class VideoRenderer {
    static final int W = 1080, H = 1920, BG_W = 216, BG_H = 384;
    private static final long TIMEOUT_US = 10000;

    // ------------------------------------------------------------ раскладка

    /** Прямоугольник на экране (px, начало сверху слева) и область исходника (доли, начало сверху слева). */
    static final class Draw {
        float x, y, w, h, u0, v0, u1, v1;

        Draw(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
        }
    }

    static final class Plan {
        String layout;
        Draw bg;          // область исходника для размытого фона
        Draw[] parts;     // что рисуем поверх фона
    }

    static float[] rect(JSONArray a) {
        if (a == null || a.length() != 4) return null;
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) r[i] = (float) a.optDouble(i, -1);
        float x = Math.min(Math.max(r[0], 0), 1), y = Math.min(Math.max(r[1], 0), 1);
        float w = Math.min(r[2], 1 - x), h = Math.min(r[3], 1 - y);
        if (w < 0.03f || h < 0.03f) return null;
        return new float[]{x, y, w, h};
    }

    static boolean validCam(JSONObject prof, int sw, int sh) {
        float[] r = rect(prof.optJSONArray("cam_rect"));
        return r != null && r[2] * sw >= 16 && r[3] * sh >= 16;
    }

    /** Обрезка области (доли) до пропорции tw:th по центру, с учётом размеров исходника. */
    static float[] cropToAspect(float[] r, int sw, int sh, float tw, float th) {
        float px = r[0] * sw, py = r[1] * sh, pw = r[2] * sw, ph = r[3] * sh, a = tw / th;
        if (pw / ph > a) {
            float nw = ph * a;
            px += (pw - nw) / 2;
            pw = nw;
        } else {
            float nh = pw / a;
            py += (ph - nh) / 2;
            ph = nh;
        }
        return new float[]{px / sw, py / sh, pw / sw, ph / sh};
    }

    static Plan plan(int sw, int sh, JSONObject prof, String layout) {
        Plan p = new Plan();
        float[] full = {0, 0, 1, 1};
        float[] bg = cropToAspect(full, sw, sh, BG_W, BG_H);
        p.bg = new Draw(0, 0, W, H, bg[0], bg[1], bg[0] + bg[2], bg[1] + bg[3]);
        if ("split".equals(layout) && validCam(prof, sw, sh)) {
            p.layout = "split";
            float hs = (float) prof.optDouble("split_screen_h", 0.45), hg = (float) prof.optDouble("split_gap_h", 0.10),
                    hc = (float) prof.optDouble("split_cam_h", 0.45);
            float tot = hs + hg + hc;
            if (tot > 1) {
                hs /= tot;
                hg /= tot;
                hc /= tot;
            }
            float HS = Math.round(H * hs), HG = Math.round(H * hg), HC = Math.min(Math.round(H * hc), H - HS - HG);
            float[] scr = rect(prof.optJSONArray("screen_rect"));
            if (scr == null) scr = full;
            Draw screen;
            if ("fit".equals(prof.optString("screen_fit"))) {
                float k = Math.min(W / (scr[2] * sw), HS / (scr[3] * sh));
                float tw = scr[2] * sw * k, th = scr[3] * sh * k;
                screen = new Draw((W - tw) / 2, (HS - th) / 2, tw, th, scr[0], scr[1], scr[0] + scr[2], scr[1] + scr[3]);
            } else {
                float[] c = cropToAspect(scr, sw, sh, W, HS);
                screen = new Draw(0, 0, W, HS, c[0], c[1], c[0] + c[2], c[1] + c[3]);
            }
            float[] cam = cropToAspect(rect(prof.optJSONArray("cam_rect")), sw, sh, W, HC);
            Draw camD = new Draw(0, HS + HG, W, HC, cam[0], cam[1], cam[0] + cam[2], cam[1] + cam[3]);
            p.parts = new Draw[]{screen, camD};
        } else {
            p.layout = "single";
            float z = (float) Math.min(Math.max(prof.optDouble("single_zoom", 1.0), 1.0), 3.0);
            float fit = Math.min(W / (float) sw, H / (float) sh) * z;
            float fw = sw * fit, fh = sh * fit, cw = Math.min(fw, W), ch = Math.min(fh, H);
            float u0 = (fw - cw) / 2 / fw, v0 = (fh - ch) / 2 / fh;
            p.parts = new Draw[]{new Draw((W - cw) / 2, (H - ch) / 2, cw, ch, u0, v0, 1 - u0, 1 - v0)};
        }
        return p;
    }

    // ------------------------------------------------------------ шейдеры

    private static final String VS = "attribute vec2 aPos; attribute vec2 aTex; uniform mat4 uTexM; uniform float uFlip;\n"
            + "varying vec2 vTex;\n"
            + "void main(){ gl_Position = vec4(aPos.x * uFlip, aPos.y, 0.0, 1.0); vTex = (uTexM * vec4(aTex, 0.0, 1.0)).xy; }\n";
    private static final String FS_OES = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex; uniform float uGain;\n"
            + "void main(){ vec4 c = texture2D(sTex, vTex); gl_FragColor = vec4(c.rgb * uGain, 1.0); }\n";
    private static final String FS_2D = "precision mediump float; varying vec2 vTex; uniform sampler2D sTex;\n"
            + "uniform vec2 uStep; uniform float uGain; uniform float uBlur;\n"
            + "void main(){\n"
            + "  if (uBlur > 0.5) {\n"
            + "    vec4 s = texture2D(sTex, vTex) * 0.227027;\n"
            + "    s += texture2D(sTex, vTex + uStep * 1.3846) * 0.316216; s += texture2D(sTex, vTex - uStep * 1.3846) * 0.316216;\n"
            + "    s += texture2D(sTex, vTex + uStep * 3.2308) * 0.070270; s += texture2D(sTex, vTex - uStep * 3.2308) * 0.070270;\n"
            + "    gl_FragColor = vec4(s.rgb * uGain, 1.0);\n"
            + "  } else { vec4 c = texture2D(sTex, vTex); gl_FragColor = vec4(c.rgb * uGain, c.a); }\n"
            + "}\n";

    static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Шейдер: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    static int program(String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Программа GL: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    // ------------------------------------------------------------ состояние одного рендера

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int progOes, prog2d, oesTex;
    private final int[] fbo = new int[2], fboTex = new int[2];
    private SurfaceTexture st;
    private HandlerThread frameThread;
    private final Object frameLock = new Object();
    private boolean frameAvailable;
    private final float[] texM = new float[16], ident = new float[16];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private int subTex = -1;
    private Subs.Event subEvent;
    private int subTop, subH;

    static final class Result {
        String layout;
        int frames;
    }

    /** Собирает видео в out (MP4 без faststart). */
    Result render(Context ctx, Uri uri, int sw, int sh, double fps, JSONObject prof, String layout, boolean mirror,
                  List<Subs.Event> events, SubtitlePainter painter, long startUs, long endUs, int bitrate,
                  AudioPipe.AacTrack audio, File out, AtomicBoolean cancel, JobQueue.Progress prog) throws Exception {
        Plan plan = plan(sw, sh, prof, layout);
        Result res = new Result();
        res.layout = plan.layout;
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        Surface encSurface = null, decSurface = null;
        boolean muxStarted = false;
        try {
            ex.setDataSource(ctx, uri, null);
            int vt = -1;
            MediaFormat vf = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    vt = i;
                    vf = f;
                    break;
                }
            }
            if (vt < 0) throw new ApiError(400, "В файле нет видеодорожки");
            ex.selectTrack(vt);

            // кодировщик H.264 1080x1920
            int outFps = (int) Math.max(15, Math.min(60, Math.round(fps > 0 ? fps : 30)));
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat ef = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, outFps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            try {
                MediaFormat hi = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
                hi.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                hi.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                hi.setInteger(MediaFormat.KEY_FRAME_RATE, outFps);
                hi.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                hi.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                hi.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
                enc.configure(hi, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception e) {
                // кодировщик не поддерживает High — берём профиль по умолчанию
                enc.release();
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            encSurface = enc.createInputSurface();
            enc.start();
            setupEgl(encSurface);
            setupGl();

            // декодер в текстуру
            st = new SurfaceTexture(oesTex);
            frameThread = new HandlerThread("frames");
            frameThread.start();
            st.setOnFrameAvailableListener(t -> {
                synchronized (frameLock) {
                    frameAvailable = true;
                    frameLock.notifyAll();
                }
            }, new Handler(frameThread.getLooper()));
            decSurface = new Surface(st);
            String vmime = vf.getString(MediaFormat.KEY_MIME);
            try {
                dec = MediaCodec.createDecoderByType(vmime);
                dec.configure(vf, decSurface, null, 0);
                dec.start();
            } catch (Exception e) {
                throw new ApiError(400, "Телефон не умеет декодировать это видео (" + vmime + "). Попробуйте файл MP4 с H.264.");
            }

            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxV = -1, muxA = -1, ai = 0;
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            MediaCodec.BufferInfo di = new MediaCodec.BufferInfo(), ei = new MediaCodec.BufferInfo();
            boolean inDone = false, decDone = false, encDone = false;
            long lastRendered = Long.MIN_VALUE, minGap = 1000000L / 61, lastEncPts = -1;
            long total = Math.max(1, endUs - startUs);
            while (!encDone) {
                if (cancel.get()) throw new JobQueue.Cancelled();
                if (!inDone) {
                    int i = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (i >= 0) {
                        int n = ex.readSampleData(dec.getInputBuffer(i), 0);
                        long t = ex.getSampleTime();
                        if (n < 0 || t > endUs + 1000000L) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(i, 0, n, t, 0);
                            ex.advance();
                        }
                    }
                }
                if (!decDone) {
                    int o = dec.dequeueOutputBuffer(di, TIMEOUT_US);
                    if (o >= 0) {
                        long pts = di.presentationTimeUs;
                        boolean eos = (di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean render = di.size > 0 && pts >= startUs && pts < endUs && pts - lastRendered >= minGap;
                        dec.releaseOutputBuffer(o, render);
                        if (render) {
                            awaitFrame();
                            st.updateTexImage();
                            st.getTransformMatrix(texM);
                            long rel = pts - startUs;
                            drawFrame(plan, mirror, events, painter, rel / 1e6);
                            EGLExt.eglPresentationTimeANDROID(display, surface, rel * 1000);
                            EGL14.eglSwapBuffers(display, surface);
                            lastRendered = pts;
                            res.frames++;
                            prog.set(Math.min(1.0, rel / (double) total));
                        }
                        if (eos || pts >= endUs) {
                            enc.signalEndOfInputStream();
                            decDone = true;
                        }
                    }
                }
                // забираем закодированное
                while (true) {
                    int o = enc.dequeueOutputBuffer(ei, decDone ? TIMEOUT_US : 0);
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxV = mux.addTrack(enc.getOutputFormat());
                        if (audio != null && audio.format != null) muxA = mux.addTrack(audio.format);
                        mux.start();
                        muxStarted = true;
                        continue;
                    }
                    if (o < 0) continue;
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
                    if (ei.size > 0 && muxStarted && ei.presentationTimeUs > lastEncPts) {
                        ByteBuffer b = enc.getOutputBuffer(o);
                        b.position(ei.offset);
                        b.limit(ei.offset + ei.size);
                        mux.writeSampleData(muxV, b, ei);
                        lastEncPts = ei.presentationTimeUs;
                        // звук пишем вперемешку с видео, по времени
                        while (muxA >= 0 && ai < audio.samples.size() && audio.samples.get(ai).ptsUs <= lastEncPts) {
                            writeAudio(mux, muxA, audio.samples.get(ai++));
                        }
                    }
                    enc.releaseOutputBuffer(o, false);
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encDone = true;
                        break;
                    }
                }
            }
            if (res.frames == 0 || !muxStarted) throw new ApiError(500, "Не получилось ни одного кадра — проверьте начало и конец отрезка");
            while (muxA >= 0 && ai < audio.samples.size()) writeAudio(mux, muxA, audio.samples.get(ai++));
            mux.stop();
            muxStarted = false;
            return res;
        } finally {
            if (dec != null) {
                try { dec.stop(); } catch (RuntimeException ignored) { }
                dec.release();
            }
            if (enc != null) {
                try { enc.stop(); } catch (RuntimeException ignored) { }
                enc.release();
            }
            if (mux != null) {
                try {
                    if (muxStarted) mux.stop();
                } catch (RuntimeException ignored) {
                }
                mux.release();
            }
            ex.release();
            releaseGl();
            if (decSurface != null) decSurface.release();
            if (st != null) st.release();
            if (frameThread != null) frameThread.quitSafely();
            if (encSurface != null) encSurface.release();
        }
    }

    private static void writeAudio(MediaMuxer mux, int track, AudioPipe.Sample s) {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        bi.set(0, s.data.length, s.ptsUs, s.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        mux.writeSampleData(track, ByteBuffer.wrap(s.data), bi);
    }

    private void awaitFrame() throws Exception {
        synchronized (frameLock) {
            long end = System.currentTimeMillis() + 3000;
            while (!frameAvailable) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new ApiError(500, "Декодер не отдал кадр (зависание видеокодека)");
                frameLock.wait(left);
            }
            frameAvailable = false;
        }
    }

    // ------------------------------------------------------------ EGL / GL

    private void setupEgl(Surface target) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw new RuntimeException("EGL не запускается");
        int[] attr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(display, attr, 0, cfg, 0, 1, n, 0) || n[0] == 0) throw new RuntimeException("Нет подходящей конфигурации EGL");
        context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        surface = EGL14.eglCreateWindowSurface(display, cfg[0], target, new int[]{EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new RuntimeException("eglMakeCurrent не удался");
    }

    private void setupGl() {
        progOes = program(FS_OES);
        prog2d = program(FS_2D);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        oesTex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenTextures(2, fboTex, 0);
        GLES20.glGenFramebuffers(2, fbo, 0);
        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BG_W, BG_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            setTexParams();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        Matrix.setIdentityM(ident, 0);
    }

    private static void setTexParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private void releaseGl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        surface = EGL14.EGL_NO_SURFACE;
    }

    /**
     * Квадрат в целевой области размером tw x th (px, сверху слева). Текстурные координаты задаются
     * по углам: (s0,t0) — левый верх, (s1,t1) — правый низ.
     */
    private void quad(int prog, float x, float y, float w, float h, float tw, float th,
                      float s0, float t0, float s1, float t1, float[] m, boolean flip) {
        float l = x / tw * 2 - 1, r = (x + w) / tw * 2 - 1, top = 1 - y / th * 2, bot = 1 - (y + h) / th * 2;
        quad.clear();
        quad.put(new float[]{l, top, s0, t0, r, top, s1, t0, l, bot, s0, t1, r, bot, s1, t1});
        int aPos = GLES20.glGetAttribLocation(prog, "aPos"), aTex = GLES20.glGetAttribLocation(prog, "aTex");
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexM"), 1, false, m, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlip"), flip ? -1f : 1f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Кадр исходника (OES) в область; координаты области исходника — сверху слева, GL — снизу слева. */
    private void drawVideo(Draw d, float tw, float th, boolean flip, float gain) {
        GLES20.glUseProgram(progOes);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progOes, "sTex"), 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progOes, "uGain"), gain);
        quad(progOes, d.x, d.y, d.w, d.h, tw, th, d.u0, 1 - d.v0, d.u1, 1 - d.v1, texM, flip);
    }

    private void draw2d(int tex, float x, float y, float w, float h, float tw, float th, float s0, float t0, float s1, float t1,
                        boolean flip, float gain, float blurDx, float blurDy) {
        GLES20.glUseProgram(prog2d);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog2d, "sTex"), 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog2d, "uGain"), gain);
        boolean blur = blurDx != 0 || blurDy != 0;
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog2d, "uBlur"), blur ? 1f : 0f);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog2d, "uStep"), blurDx, blurDy);
        quad(prog2d, x, y, w, h, tw, th, s0, t0, s1, t1, ident, flip);
    }

    private void drawFrame(Plan plan, boolean mirror, List<Subs.Event> events, SubtitlePainter painter, double t) {
        GLES20.glDisable(GLES20.GL_BLEND);
        // 1) размытый фон: кадр в маленький буфер, затем 2 прохода размытия по каждой оси
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glViewport(0, 0, BG_W, BG_H);
        drawVideo(new Draw(0, 0, BG_W, BG_H, plan.bg.u0, plan.bg.v0, plan.bg.u1, plan.bg.v1), BG_W, BG_H, false, 1f);
        for (int pass = 0; pass < 2; pass++) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[1]);
            draw2d(fboTex[0], 0, 0, BG_W, BG_H, BG_W, BG_H, 0, 1, 1, 0, false, 1f, 2.5f / BG_W, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
            draw2d(fboTex[1], 0, 0, BG_W, BG_H, BG_W, BG_H, 0, 1, 1, 0, false, 1f, 0, 2.5f / BG_H);
        }
        // 2) на выход: фон (затемнённый), затем картинка; зеркало — ко всей композиции
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, W, H);
        draw2d(fboTex[0], 0, 0, W, H, W, H, 0, 1, 1, 0, mirror, 0.88f, 0, 0);
        for (Draw d : plan.parts) drawVideo(d, W, H, mirror, 1f);
        // 3) субтитры поверх — без зеркала
        Subs.Event ev = null;
        for (Subs.Event e : events) {
            if (t >= e.t0 && t < e.t1) {
                ev = e;
                break;
            }
        }
        if (ev != null) {
            if (ev != subEvent) uploadSubtitle(ev, painter);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            draw2d(subTex, 0, subTop, W, subH, W, H, 0, 0, 1, 1, false, 1f, 0, 0);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private void uploadSubtitle(Subs.Event ev, SubtitlePainter painter) {
        int[] top = new int[1];
        Bitmap b = painter.draw(ev, top);
        if (subTex < 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            subTex = t[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, subTex);
        setTexParams();
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        subTop = top[0];
        subH = b.getHeight();
        b.recycle();
        subEvent = ev;
    }
}
