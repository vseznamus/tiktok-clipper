package ru.tiktokclipper.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.tiktokclipper.core.JobQueue;

/** Звук через системные кодеки: декодирование отрезка, сведение каналов, ресемплинг, AAC, громкость. */
final class AudioPipe {
    private AudioPipe() {
    }

    static final int OUT_RATE = 44100;
    private static final long TIMEOUT_US = 10000;

    /** Получатель PCM: кадры float [-1..1], уже сведённые к channels каналам. */
    interface Sink {
        void pcm(float[] data, int frames, int channels, int rate, long ptsUs) throws Exception;
    }

    static final class Sample {
        final byte[] data;
        final long ptsUs;
        final int flags;

        Sample(byte[] d, long p, int f) {
            data = d;
            ptsUs = p;
            flags = f;
        }
    }

    static final class AacTrack {
        MediaFormat format;
        final List<Sample> samples = new ArrayList<>();
        String warning;
    }

    /**
     * Декодирует звук из [startUs, endUs) (endUs < 0 — до конца), сводит к outChannels каналам.
     * Возвращает false, если звуковой дорожки нет или телефон не умеет её декодировать.
     */
    static boolean decode(Context ctx, Uri uri, long startUs, long endUs, int outChannels, Sink sink,
                          AtomicBoolean cancel, JobQueue.Progress prog) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) return false;
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            try {
                dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                dec.configure(fmt, null, null, 0);
                dec.start();
            } catch (Exception e) {
                return false;
            }
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            boolean isFloat = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            long total = endUs > 0 ? endUs - startUs : Math.max(1, durUs - startUs);
            while (!outDone) {
                if (cancel != null && cancel.get()) throw new JobQueue.Cancelled();
                if (!inDone) {
                    int i = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (i >= 0) {
                        ByteBuffer b = dec.getInputBuffer(i);
                        int n = ex.readSampleData(b, 0);
                        long t = ex.getSampleTime();
                        if (n < 0 || (endUs > 0 && t > endUs + 200000)) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(i, 0, n, t, 0);
                            ex.advance();
                        }
                    }
                }
                int o = dec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                } else if (o >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(o);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.order(ByteOrder.nativeOrder());
                        int samples = isFloat ? info.size / 4 : info.size / 2;
                        int frames = samples / channels;
                        float[] raw = new float[frames * channels];
                        if (isFloat) {
                            ob.asFloatBuffer().get(raw);
                        } else {
                            short[] s = new short[frames * channels];
                            ob.asShortBuffer().get(s);
                            for (int k = 0; k < s.length; k++) raw[k] = s[k] / 32768f;
                        }
                        long pts = info.presentationTimeUs;
                        long bufEnd = pts + frames * 1000000L / rate;
                        if (bufEnd > startUs && (endUs < 0 || pts < endUs)) {
                            int skip = pts < startUs ? (int) ((startUs - pts) * rate / 1000000L) : 0;
                            int last = endUs < 0 ? frames : (int) Math.min(frames, (endUs - pts) * rate / 1000000L);
                            if (last > skip) {
                                float[] mixed = downmix(raw, skip, last, channels, outChannels);
                                sink.pcm(mixed, last - skip, outChannels, rate, pts + skip * 1000000L / rate);
                            }
                        }
                        if (prog != null) prog.set(Math.min(1.0, Math.max(0, (bufEnd - startUs) / (double) total)));
                        if (endUs > 0 && bufEnd >= endUs) outDone = true;
                    }
                    dec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            return true;
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (RuntimeException ignored) {
                }
                dec.release();
            }
            ex.release();
        }
    }

    /** Сведение к 1 или 2 каналам. Порядок каналов Android: FL, FR, FC, LFE, BL, BR, … */
    static float[] downmix(float[] in, int from, int to, int ch, int outCh) {
        int n = to - from;
        float[] out = new float[n * outCh];
        for (int f = 0; f < n; f++) {
            int b = (from + f) * ch;
            float l, r;
            if (ch == 1) {
                l = r = in[b];
            } else if (ch == 2) {
                l = in[b];
                r = in[b + 1];
            } else {
                float c = ch > 2 ? in[b + 2] : 0, bl = ch > 4 ? in[b + 4] : 0, br = ch > 5 ? in[b + 5] : 0;
                l = 0.5f * in[b] + 0.35f * c + 0.3f * bl;
                r = 0.5f * in[b + 1] + 0.35f * c + 0.3f * br;
            }
            if (outCh == 1) {
                out[f] = clamp((l + r) * 0.5f);
            } else {
                out[f * 2] = clamp(l);
                out[f * 2 + 1] = clamp(r);
            }
        }
        return out;
    }

    static float clamp(float v) {
        return v > 1f ? 1f : (v < -1f ? -1f : v);
    }

    /** Потоковый линейный ресемплер. */
    static final class Resampler {
        final int ch;
        final double step;
        double t = 0;
        float[] prev;

        Resampler(int inRate, int outRate, int ch) {
            this.ch = ch;
            this.step = inRate / (double) outRate;
        }

        float[] process(float[] in, int frames) {
            if (frames == 0) return new float[0];
            int est = (int) ((frames - t) / step) + 2;
            float[] out = new float[est * ch];
            int n = 0;
            while (true) {
                int i0 = (int) Math.floor(t);
                int i1 = i0 + 1;
                if (i1 > frames - 1) break;
                double frac = t - i0;
                for (int c = 0; c < ch; c++) {
                    float a = i0 < 0 ? (prev != null ? prev[c] : in[c]) : in[i0 * ch + c];
                    float b = in[i1 * ch + c];
                    if (n * ch + c >= out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                    out[n * ch + c] = (float) (a + (b - a) * frac);
                }
                n++;
                t += step;
            }
            t -= frames;
            if (t < -1) t = -1;
            prev = new float[ch];
            System.arraycopy(in, (frames - 1) * ch, prev, 0, ch);
            return java.util.Arrays.copyOf(out, n * ch);
        }
    }

    // ------------------------------------------------------------ AAC-дорожка клипа

    /** Звук отрезка в AAC 44,1 кГц стерео. Если звука нет или он не декодируется — тишина с предупреждением. */
    static AacTrack encodeAac(Context ctx, Uri uri, long startUs, long endUs, boolean hasAudio,
                              AtomicBoolean cancel, JobQueue.Progress prog) throws Exception {
        final AacEncoder enc = new AacEncoder();
        final AacTrack track = enc.track;
        try {
            boolean ok = false;
            if (hasAudio) {
                final Resampler[] rs = {null};
                final long[] expectedUs = {0};
                ok = decode(ctx, uri, startUs, endUs, 2, (data, frames, ch, rate, ptsUs) -> {
                    long rel = ptsUs - startUs;
                    if (rel - expectedUs[0] > 20000) enc.silence(rel - expectedUs[0]); // дыра в звуке — тишиной
                    if (rs[0] == null) rs[0] = new Resampler(rate, OUT_RATE, 2);
                    float[] out = rs[0].process(data, frames);
                    enc.feed(out, out.length / 2);
                    expectedUs[0] = rel + frames * 1000000L / rate;
                }, cancel, prog);
                if (!ok) track.warning = "Звук этого файла телефон не декодирует — добавлена тишина.";
            } else {
                track.warning = "В исходнике нет звука — добавлена тишина.";
            }
            long durUs = endUs - startUs;
            long have = enc.frames * 1000000L / OUT_RATE;
            if (have < durUs) enc.silence(durUs - have);
            enc.finish();
            return track;
        } finally {
            enc.release();
        }
    }

    static final class AacEncoder {
        final AacTrack track = new AacTrack();
        final MediaCodec codec;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long frames = 0;

        AacEncoder() throws Exception {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, OUT_RATE, 2);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 160000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        }

        void silence(long us) throws Exception {
            int n = (int) (us * OUT_RATE / 1000000L);
            float[] z = new float[4096 * 2];
            while (n > 0) {
                int k = Math.min(n, 4096);
                feed(z, k);
                n -= k;
            }
        }

        void feed(float[] stereo, int nFrames) throws Exception {
            int off = 0;
            while (off < nFrames) {
                int i = codec.dequeueInputBuffer(TIMEOUT_US);
                if (i < 0) {
                    drain(false);
                    continue;
                }
                ByteBuffer b = codec.getInputBuffer(i);
                b.clear();
                b.order(ByteOrder.nativeOrder());
                int cap = b.remaining() / 4;
                int k = Math.min(cap, nFrames - off);
                for (int j = 0; j < k; j++) {
                    b.putShort((short) (stereo[(off + j) * 2] * 32767));
                    b.putShort((short) (stereo[(off + j) * 2 + 1] * 32767));
                }
                codec.queueInputBuffer(i, 0, k * 4, frames * 1000000L / OUT_RATE, 0);
                frames += k;
                off += k;
                drain(false);
            }
        }

        void finish() throws Exception {
            int i;
            while ((i = codec.dequeueInputBuffer(TIMEOUT_US)) < 0) drain(false);
            codec.queueInputBuffer(i, 0, 0, frames * 1000000L / OUT_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
        }

        void drain(boolean untilEos) {
            while (true) {
                int o = codec.dequeueOutputBuffer(info, untilEos ? TIMEOUT_US : 0);
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEos) return;
                    continue;
                }
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track.format = codec.getOutputFormat();
                    continue;
                }
                if (o < 0) continue;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    ByteBuffer ob = codec.getOutputBuffer(o);
                    byte[] d = new byte[info.size];
                    ob.position(info.offset);
                    ob.get(d);
                    track.samples.add(new Sample(d, info.presentationTimeUs, info.flags));
                }
                codec.releaseOutputBuffer(o, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        void release() {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            codec.release();
        }
    }

    // ------------------------------------------------------------ громкость и звук для распознавания

    /** RMS-громкость (дБ) по секундам всего файла — для «Предложить моменты». */
    static double[] loudness(Context ctx, Uri uri, double durationSec, AtomicBoolean cancel, JobQueue.Progress prog) throws Exception {
        final int n = Math.max(1, (int) Math.ceil(durationSec));
        final double[] sum = new double[n];
        final long[] cnt = new long[n];
        boolean ok = decode(ctx, uri, 0, -1, 1, (data, frames, ch, rate, ptsUs) -> {
            for (int i = 0; i < frames; i++) {
                int sec = (int) ((ptsUs + i * 1000000L / rate) / 1000000L);
                if (sec < 0 || sec >= n) continue;
                sum[sec] += data[i] * data[i];
                cnt[sec]++;
            }
        }, cancel, prog);
        if (!ok) throw new ru.tiktokclipper.core.ApiError(400, "Звук этого файла телефон не декодирует — искать громкие моменты не по чему");
        double[] db = new double[n];
        for (int i = 0; i < n; i++) {
            double rms = cnt[i] > 0 ? Math.sqrt(sum[i] / cnt[i]) : 0;
            db[i] = rms > 1e-5 ? Math.max(-90, 20 * Math.log10(rms)) : -90;
        }
        return db;
    }

    /** Звук отрезка в 16 кГц моно — для распознавания речи; отдаётся кусками в consumer. */
    interface ShortSink {
        void accept(short[] data, int n) throws Exception;
    }

    static boolean pcm16kMono(Context ctx, Uri uri, long startUs, long endUs, final ShortSink out,
                              AtomicBoolean cancel, JobQueue.Progress prog) throws Exception {
        final Resampler[] rs = {null};
        return decode(ctx, uri, startUs, endUs, 1, (data, frames, ch, rate, ptsUs) -> {
            if (rs[0] == null) rs[0] = new Resampler(rate, 16000, 1);
            float[] r = rs[0].process(data, frames);
            short[] s = new short[r.length];
            for (int i = 0; i < r.length; i++) s[i] = (short) (r[i] * 32767);
            out.accept(s, s.length);
        }, cancel, prog);
    }
}
