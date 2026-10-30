package ru.tiktokclipper.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Переносит индекс (moov) в начало MP4 — аналог ffmpeg -movflags +faststart.
 * Android MediaMuxer пишет moov в конец файла; для соцсетей и просмотра лучше в начало.
 * Смещения в таблицах stco/co64 сдвигаются на размер moov.
 */
public final class Mp4FastStart {
    private Mp4FastStart() {
    }

    /** Возвращает true, если файл переписан; false — если moov уже в начале. */
    public static boolean process(File in, File out) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(in, "r")) {
            long len = raf.length();
            long pos = 0, moovPos = -1, moovSize = 0, firstMdat = -1;
            while (pos + 8 <= len) {
                raf.seek(pos);
                long size = raf.readInt() & 0xFFFFFFFFL;
                int type = raf.readInt();
                if (size == 1) size = raf.readLong();
                else if (size == 0) size = len - pos;
                if (size < 8) throw new IOException("Повреждённый MP4");
                if (type == fourcc("moov")) {
                    moovPos = pos;
                    moovSize = size;
                } else if (type == fourcc("mdat") && firstMdat < 0) {
                    firstMdat = pos;
                }
                pos += size;
            }
            if (moovPos < 0) throw new IOException("В MP4 нет moov");
            if (firstMdat < 0 || moovPos < firstMdat) return false;
            if (moovSize > Integer.MAX_VALUE) throw new IOException("Слишком большой moov");
            byte[] moov = new byte[(int) moovSize];
            raf.seek(moovPos);
            raf.readFully(moov);
            ByteBuffer bb = ByteBuffer.wrap(moov);
            patch(bb, 0, moov.length, moovSize);
            try (RandomAccessFile w = new RandomAccessFile(out, "rw")) {
                w.setLength(0);
                copy(raf, w, 0, firstMdat);            // ftyp и всё до первого mdat
                w.write(moov);                        // moov
                copy(raf, w, firstMdat, moovPos - firstMdat);
                copy(raf, w, moovPos + moovSize, len - moovPos - moovSize);
            }
            return true;
        }
    }

    private static void copy(RandomAccessFile r, RandomAccessFile w, long from, long n) throws IOException {
        byte[] buf = new byte[1 << 16];
        r.seek(from);
        while (n > 0) {
            int k = r.read(buf, 0, (int) Math.min(buf.length, n));
            if (k <= 0) throw new IOException("Неожиданный конец файла");
            w.write(buf, 0, k);
            n -= k;
        }
    }

    private static final String[] CONTAINERS = {"moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta"};

    private static boolean isContainer(int type) {
        for (String c : CONTAINERS) if (fourcc(c) == type) return true;
        return false;
    }

    private static void patch(ByteBuffer bb, int start, int end, long delta) throws IOException {
        int pos = start == 0 ? 8 : start; // пропускаем заголовок самого moov
        while (pos + 8 <= end) {
            long size = bb.getInt(pos) & 0xFFFFFFFFL;
            int type = bb.getInt(pos + 4);
            int hdr = 8;
            if (size == 1) {
                size = bb.getLong(pos + 8);
                hdr = 16;
            }
            if (size < 8 || pos + size > end) throw new IOException("Повреждённый moov");
            if (type == fourcc("stco")) {
                int n = bb.getInt(pos + hdr + 4);
                for (int i = 0; i < n; i++) {
                    int p = pos + hdr + 8 + i * 4;
                    long v = (bb.getInt(p) & 0xFFFFFFFFL) + delta;
                    if (v > 0xFFFFFFFFL) throw new IOException("Файл слишком большой для stco");
                    bb.putInt(p, (int) v);
                }
            } else if (type == fourcc("co64")) {
                int n = bb.getInt(pos + hdr + 4);
                for (int i = 0; i < n; i++) {
                    int p = pos + hdr + 8 + i * 8;
                    bb.putLong(p, bb.getLong(p) + delta);
                }
            } else if (isContainer(type)) {
                patch(bb, pos + hdr, (int) (pos + size), delta);
            }
            pos += size;
        }
    }

    static int fourcc(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    /** Для проверки: true, если moov стоит раньше mdat. */
    public static boolean isFastStart(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length(), pos = 0;
            while (pos + 8 <= len) {
                raf.seek(pos);
                long size = raf.readInt() & 0xFFFFFFFFL;
                int type = raf.readInt();
                if (size == 1) size = raf.readLong();
                else if (size == 0) size = len - pos;
                if (type == fourcc("moov")) return true;
                if (type == fourcc("mdat")) return false;
                if (size < 8) return false;
                pos += size;
            }
        }
        return false;
    }
}
