package ru.tiktokclipper.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import org.json.JSONObject;

import java.util.List;

import ru.tiktokclipper.core.Subs;

/** Рисует строку субтитров в картинку шириной 1080: крупный шрифт, обводка, тень, подсветка текущего слова. */
final class SubtitlePainter {
    /** Размер стиля задан «как в ASS» (высота строки); кеглю Android соответствует ~0,82 от него. */
    static final float SCALE = 0.82f;
    final Typeface tf;
    final JSONObject style;

    SubtitlePainter(Typeface tf, JSONObject style) {
        this.tf = tf;
        this.style = style;
    }

    private Paint paint(float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size * SCALE);
        return p;
    }

    /** Измеритель для раскладки строк — тот же шрифт и кегль, что и при рисовании. */
    Subs.Measurer measurer() {
        return (text, size) -> paint(size).measureText(text);
    }

    static int color(String hex) {
        return Color.parseColor("#" + hex);
    }

    Bitmap draw(Subs.Event ev, int[] topOut) {
        float outline = (float) style.optDouble("outline", 7), shadow = (float) style.optDouble("shadow", 2);
        int base = color(style.optString("color", "FFFFFF"));
        int hi = style.isNull("highlight") ? base : color(style.optString("highlight"));
        Paint fill = paint(ev.size), stroke = paint(ev.size), shade = paint(ev.size);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(outline * 2);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.BLACK);
        shade.setStyle(Paint.Style.FILL_AND_STROKE);
        shade.setStrokeWidth(outline * 2);
        shade.setStrokeJoin(Paint.Join.ROUND);
        shade.setColor(Color.argb(105, 0, 0, 0));
        Paint.FontMetrics fm = fill.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        int pad = (int) Math.ceil(outline * 2 + shadow + 4);
        int h = (int) Math.ceil(ev.rows.size() * lineH + 2 * pad);
        Bitmap b = Bitmap.createBitmap(Subs.W, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float space = fill.measureText(" ");
        float y = pad - fm.ascent;
        int idx = 0;
        for (List<String> row : ev.rows) {
            float x = ev.cx - fill.measureText(String.join(" ", row)) / 2;
            for (String w : row) {
                if (shadow > 0) c.drawText(w, x + shadow, y + shadow, shade);
                c.drawText(w, x, y, stroke);
                fill.setColor(idx == ev.highlight ? hi : base);
                c.drawText(w, x, y, fill);
                x += fill.measureText(w) + space;
                idx++;
            }
            y += lineH;
        }
        topOut[0] = Math.round(ev.cy - h / 2f);
        return b;
    }
}
