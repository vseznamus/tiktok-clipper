package ru.tiktokclipper.core;

/**
 * Баннер (картинка или короткое видео из файлов) поверх клипа 1080×1920 — так, чтобы его было хорошо видно:
 * только внутри безопасной зоны TikTok (не под верхними вкладками, кнопками справа и подписью снизу)
 * и не поверх субтитров.
 * «Сверху» — над видео, на размытом фоне; «По центру» — поверх середины видео; «Снизу» — внизу безопасной зоны,
 * субтитры тогда поднимаются над баннером.
 * Те же расчёты повторяет предпросмотр в интерфейсе (app.js, bannerBox).
 */
public final class Banner {
    private Banner() {
    }

    static final int W = Subs.W, H = Subs.H;
    static final int LEFT = Subs.SAFE_LEFT, RIGHT = Subs.SAFE_RIGHT, TOP = Subs.SAFE_TOP, BOTTOM = Subs.SAFE_BOTTOM;
    /** Верх видео при раскладке «один кадр» (16:9 по центру) — баннер «сверху» до него не доходит. */
    public static final int VIDEO_TOP = (H - W * 9 / 16) / 2;
    static final int GAP = 16, MAX_H = 420;

    /** {x, y, w, h} в пикселях кадра (y — от верхнего края). size — доля ширины безопасной зоны; bw×bh — размер картинки. */
    public static int[] rect(String pos, double size, int bw, int bh) {
        double s = Math.max(0.2, Math.min(1.0, size));
        double maxW = RIGHT - LEFT;
        double w = maxW * s, h = w * bh / Math.max(1.0, bw);
        double maxH = "top".equals(pos) || pos == null || pos.isEmpty() ? Math.min(MAX_H, VIDEO_TOP - TOP - GAP) : MAX_H;
        if (h > maxH) { // слишком высокая — уменьшаем пропорционально
            h = maxH;
            w = h * bw / Math.max(1.0, bh);
        }
        double y;
        if ("center".equals(pos)) y = H * 0.48 - h / 2;
        else if ("bottom".equals(pos)) y = BOTTOM - h;
        else y = TOP + (VIDEO_TOP - TOP - GAP - h) / 2; // по центру полосы над видео
        double x = (LEFT + RIGHT) / 2.0 - w / 2;
        return new int[]{(int) Math.round(x), (int) Math.round(y), (int) Math.round(w), (int) Math.round(h)};
    }

    /** Центр субтитров (по высоте): при баннере снизу — над баннером, иначе как обычно. */
    public static double subCenter(String pos, int[] rect, double normal) {
        if (rect == null || !"bottom".equals(pos)) return normal;
        return Math.min(normal, rect[1] - GAP - 120);
    }

    /** Подложка для читаемости: тёмный прямоугольник чуть больше баннера. */
    public static int[] plate(int[] r) {
        int p = 14;
        return new int[]{r[0] - p, r[1] - p, r[2] + 2 * p, r[3] + 2 * p};
    }
}
