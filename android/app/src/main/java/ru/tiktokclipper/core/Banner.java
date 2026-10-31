package ru.tiktokclipper.core;

/**
 * Баннер (картинка из файлов) поверх клипа 1080×1920: положение «сверху / по центру / снизу» и ширина в долях кадра.
 * Те же расчёты повторяет предпросмотр в интерфейсе (app.js, bannerBox).
 */
public final class Banner {
    private Banner() {
    }

    public static final double TOP = 0.07, BOTTOM = 0.84, MAX_H = 0.4;

    /** {x, y, w, h} в пикселях кадра W×H (y — от верхнего края). bw×bh — размер картинки. */
    public static int[] rect(String pos, double size, int bw, int bh, int W, int H) {
        double s = Math.max(0.2, Math.min(1.0, size));
        double w = W * s, h = w * bh / Math.max(1.0, bw);
        if (h > H * MAX_H) { // слишком высокая картинка — ограничиваем высоту, ширину уменьшаем пропорционально
            h = H * MAX_H;
            w = h * bw / Math.max(1.0, bh);
        }
        double y;
        if ("center".equals(pos)) y = (H - h) / 2;
        else if ("bottom".equals(pos)) y = H * BOTTOM - h;
        else y = H * TOP;
        int x = (int) Math.round((W - w) / 2);
        return new int[]{x, (int) Math.round(y), (int) Math.round(w), (int) Math.round(h)};
    }
}
