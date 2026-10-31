package ru.tiktokclipper.core;

/**
 * «Убрать зелёный фон» (хромакей) для баннера: зелёные пиксели становятся прозрачными, края — полупрозрачными,
 * зелёный отсвет на краях убирается. Пиксели — ARGB без предумножения (как Bitmap.getPixels).
 */
public final class Chroma {
    private Chroma() {
    }

    public static void key(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            int d = g - Math.max(r, b); // насколько пиксель «зеленее» остального
            if (g < 100 || d < 40) continue; // тёмные и слегка зеленоватые цвета — не фон
            double keep = d >= 100 ? 0 : 1 - (d - 40) / 60.0; // мягкий край
            int na = (int) Math.round(a * keep);
            int ng = Math.min(g, Math.max(r, b)); // убираем зелёный отсвет
            px[i] = (na << 24) | (r << 16) | (ng << 8) | b;
        }
    }
}
