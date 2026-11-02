package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Автоподбор раскладки «экран сверху + вебкамера снизу» по нескольким кадрам записи эфира.
 *
 * Вебкамера стримера — прямоугольник поверх игры: его края неподвижны от кадра к кадру, а внутри что-то
 * шевелится. Поэтому берём «неподвижные края» — медиану яркостных перепадов по кадрам (движущаяся игра
 * усредняется, рамки остаются) и ищем вокруг лица (если Android его нашёл) или в углах кадра прямоугольник
 * из сплошных линий. Не нашли рамку, но есть лицо — рамка вокруг лица. Экран — область без чёрных полей,
 * подрезанная под верх клипа так, чтобы вебкамера в неё по возможности не попадала.
 */
public final class Layout {
    /** Пропорция «экрана» в клипе 1080×1920 при раскладке по умолчанию (экран 45 % высоты). */
    public static final float TOP_ASPECT = 1080f / (1920f * 0.45f);
    static final float EDGE = 9f;        // перепад яркости, который считаем краем (0…255, центральная разность / 2)
    static final float LINE_OK = 0.72f;  // доля сплошности, с которой отрезок считаем линией рамки

    private Layout() {
    }

    public static final class Result {
        public float[] cam;      // доли кадра x, y, w, h или null — вебкамеры нет
        public float[] screen;   // доли кадра
        public String how;       // frame — по рамке, corner — по рамке в углу, face — по лицу, fullcam — камера на весь кадр, none
        public int faces;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("ok", cam != null);
            o.put("layout", cam != null ? "split" : "single");
            o.put("cam_rect", cam != null ? arr(cam) : JSONObject.NULL);
            o.put("screen_rect", arr(screen));
            o.put("how", how);
            o.put("faces", faces);
            return o;
        }
    }

    static JSONArray arr(float[] r) throws JSONException {
        JSONArray a = new JSONArray();
        for (float v : r) a.put(Math.round(v * 10000) / 10000.0);
        return a;
    }

    /**
     * @param frames цвет 0xRRGGBB построчно, w×h, кадры из разных мест записи (лучше 5–10)
     * @param faces  найденные лица в долях кадра {x, y, w, h} (может быть пусто)
     * @param srcW   ширина исходника в пикселях (для пропорций экрана), srcH — высота
     */
    public static Result detect(int[][] frames, int w, int h, List<float[]> faces, int srcW, int srcH) {
        int n = frames.length;
        float[] gx = new float[w * h], gy = new float[w * h], motion = new float[w * h], mean = new float[w * h];
        float[] tx = new float[n], ty = new float[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float s = 0;
                for (int k = 0; k < n; k++) {
                    int[] f = frames[k];
                    // перепад по цвету: рамка может совпадать с соседями по яркости, но не по оттенку
                    tx[k] = x > 0 && x < w - 1 ? diff(f[i + 1], f[i - 1]) / 2 : 0;
                    ty[k] = y > 0 && y < h - 1 ? diff(f[i + w], f[i - w]) / 2 : 0;
                    s += luma(f[i]);
                }
                gx[i] = steady(tx);
                gy[i] = steady(ty);
                float m = s / n, d = 0;
                for (int k = 0; k < n; k++) d += Math.abs(luma(frames[k][i]) - m);
                mean[i] = m;
                motion[i] = n > 1 ? d / n : 0;
            }
        }
        // чёрные поля: тёмные и неподвижные строки/столбцы по краям
        int cx0 = 0, cx1 = w, cy0 = 0, cy1 = h;
        while (cy0 < h / 3 && dark(mean, motion, w, 0, w, cy0, cy0 + 1)) cy0++;
        while (cy1 > h * 2 / 3 && dark(mean, motion, w, 0, w, cy1 - 1, cy1)) cy1--;
        while (cx0 < w / 3 && dark(mean, motion, w, cx0, cx0 + 1, cy0, cy1)) cx0++;
        while (cx1 > w * 2 / 3 && dark(mean, motion, w, cx1 - 1, cx1, cy0, cy1)) cx1--;

        // линии: пиксель «на вертикальной линии», если рядом (±1 по x) есть неподвижный перепад
        boolean[] vl = new boolean[w * h], hl = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                // линия — перепад, который есть почти на всех кадрах и заметно сильнее соседа в 3 пикселях хотя бы с одной
                // стороны (по одну сторону рамки — подвижная камера; в пёстрой текстуре перепады с обеих сторон)
                float nx = Math.min(x >= 3 ? gx[i - 3] : 0, x + 3 < w ? gx[i + 3] : 0);
                float ny = Math.min(y >= 3 ? gy[i - 3 * w] : 0, y + 3 < h ? gy[i + 3 * w] : 0);
                vl[i] = gx[i] >= EDGE && gx[i] >= 1.6f * nx;
                hl[i] = gy[i] >= EDGE && gy[i] >= 1.6f * ny;
            }
        }
        Lines L = new Lines(vl, hl, w, h);
        Result r = new Result();
        r.faces = faces == null ? 0 : faces.size();
        int[] content = {cx0, cy0, cx1, cy1};

        int[] cam = null;
        float[] face = biggestFace(faces);
        if (face != null) {
            int fx0 = clamp(Math.round(face[0] * w), 0, w - 1), fy0 = clamp(Math.round(face[1] * h), 0, h - 1);
            int fx1 = clamp(Math.round((face[0] + face[2]) * w), fx0 + 1, w), fy1 = clamp(Math.round((face[1] + face[3]) * h), fy0 + 1, h);
            int[] box = aroundFace(L, content, fx0, fy0, fx1, fy1);
            int bw = box[2] - box[0], bh = box[3] - box[1];
            int realSides = box[4];
            float area = bw * (float) bh / ((cx1 - cx0) * (float) (cy1 - cy0));
            if (realSides >= 2 && area <= 0.55f && bw >= 1.4f * (fx1 - fx0) && bh >= 1.4f * (fy1 - fy0)) {
                cam = new int[]{box[0], box[1], box[2], box[3]};
                r.how = "frame";
            } else if ((fx1 - fx0) > 0.15f * (cx1 - cx0) || (realSides >= 2 && area > 0.55f)) {
                r.how = "fullcam"; // камера на весь кадр — делить на «экран» и «камеру» нечего
            } else {
                // рамки нет — прямоугольник вокруг лица (лицо в верхней половине)
                float fw = fx1 - fx0, fh = fy1 - fy0, ccx = (fx0 + fx1) / 2f;
                float rw = Math.min(cx1 - cx0, fw * 3f), rh = Math.min(cy1 - cy0, fh * 3.2f);
                float rx = Math.min(Math.max(cx0, ccx - rw / 2), cx1 - rw), ry = Math.min(Math.max(cy0, (fy0 + fy1) / 2f - rh * 0.42f), cy1 - rh);
                cam = new int[]{Math.round(rx), Math.round(ry), Math.round(rx + rw), Math.round(ry + rh)};
                r.how = "face";
            }
        }
        if (cam == null && !"fullcam".equals(r.how)) {
            cam = inCorner(L, content, motion, w);
            if (cam == null) cam = onEdge(L, content, motion, w, h, false); // посреди верхнего/нижнего края
            if (cam == null) { // посреди левого/правого края — то же самое на «повёрнутой» картинке
                boolean[] tv = new boolean[w * h], th = new boolean[w * h];
                float[] tm = new float[w * h];
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        tv[x * h + y] = hl[y * w + x];
                        th[x * h + y] = vl[y * w + x];
                        tm[x * h + y] = motion[y * w + x];
                    }
                int[] t = onEdge(new Lines(tv, th, h, w), new int[]{cy0, cx0, cy1, cx1}, tm, h, w, true);
                if (t != null) cam = new int[]{t[1], t[0], t[3], t[2]};
            }
            if (cam != null) r.how = "corner";
        }
        if (r.how == null) r.how = "none";
        if (cam != null) r.cam = new float[]{cam[0] / (float) w, cam[1] / (float) h, (cam[2] - cam[0]) / (float) w, (cam[3] - cam[1]) / (float) h};
        r.screen = screen(content, cam, w, h, srcW, srcH);
        return r;
    }

    // ------------------------------------------------------------ линии и сплошность

    static final class Lines {
        final int w, h;
        final int[] col;  // col[x*(h+1)+y] — сколько «вертикальных» пикселей в столбце x выше строки y
        final int[] row;  // row[y*(w+1)+x] — сколько «горизонтальных» пикселей в строке y левее x

        Lines(boolean[] vl, boolean[] hl, int w, int h) {
            this.w = w;
            this.h = h;
            col = new int[w * (h + 1)];
            row = new int[h * (w + 1)];
            for (int x = 0; x < w; x++)
                for (int y = 0; y < h; y++) col[x * (h + 1) + y + 1] = col[x * (h + 1) + y] + (vl[y * w + x] ? 1 : 0);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) row[y * (w + 1) + x + 1] = row[y * (w + 1) + x] + (hl[y * w + x] ? 1 : 0);
        }

        /** Доля сплошности вертикального отрезка x, y0…y1. */
        float v(int x, int y0, int y1) {
            y0 = clamp(y0, 0, h);
            y1 = clamp(y1, 0, h);
            if (x < 0 || x >= w || y1 - y0 < 3) return 0;
            return (col[x * (h + 1) + y1] - col[x * (h + 1) + y0]) / (float) (y1 - y0);
        }

        /** Пиксель (x, y) — на горизонтальной линии. */
        boolean hpx(int x, int y) {
            return x >= 0 && x < w && y >= 0 && y < h && row[y * (w + 1) + x + 1] > row[y * (w + 1) + x];
        }

        /** Доля сплошности горизонтального отрезка y, x0…x1. */
        float hz(int y, int x0, int x1) {
            x0 = clamp(x0, 0, w);
            x1 = clamp(x1, 0, w);
            if (y < 0 || y >= h || x1 - x0 < 3) return 0;
            return (row[y * (w + 1) + x1] - row[y * (w + 1) + x0]) / (float) (x1 - x0);
        }
    }

    /**
     * Рамка вокруг лица: на каждой стороне — самая сплошная линия между лицом и краем картинки.
     * Возвращает {x0, y0, x1, y1, сколько сторон нашлось линиями (а не краем кадра)}.
     */
    static int[] aroundFace(Lines L, int[] c, int fx0, int fy0, int fx1, int fy1) {
        int fw = fx1 - fx0, fh = fy1 - fy0;
        int x0 = c[0], x1 = c[2] - 1, y0 = c[1], y1 = c[3] - 1;
        int ly0 = fy0 - fh / 2, ly1 = fy1 + fh / 2, lx0 = fx0 - fw / 2, lx1 = fx1 + fw / 2;
        boolean[] real = new boolean[4];
        for (int pass = 0; pass < 3; pass++) {
            int[] bl = best(L, true, ly0, ly1, c[0], fx0 - 2, true), br = best(L, true, ly0, ly1, fx1 + 2, c[2] - 1, false);
            x0 = bl[0] >= 0 ? bl[0] : c[0];
            x1 = br[0] >= 0 ? br[0] : c[2] - 1;
            real[0] = bl[0] >= 0;
            real[1] = br[0] >= 0;
            int[] bt = best(L, false, x0, x1, c[1], fy0 - 2, true), bb = best(L, false, x0, x1, fy1 + 2, c[3] - 1, false);
            y0 = bt[0] >= 0 ? bt[0] : c[1];
            y1 = bb[0] >= 0 ? bb[0] : c[3] - 1;
            real[2] = bt[0] >= 0;
            real[3] = bb[0] >= 0;
            ly0 = y0;
            ly1 = y1;
            lx0 = x0;
            lx1 = x1;
        }
        int cnt = 0;
        for (boolean b : real) if (b) cnt++;
        // внутренность рамки: от внутреннего края полосы линии
        int ix0 = real[0] ? snap(L, true, x0, y0, y1, +1, fx0) : x0;
        int ix1 = real[1] ? snap(L, true, x1, y0, y1, -1, fx1) + 1 : x1 + 1;
        int iy0 = real[2] ? snap(L, false, y0, x0, x1, +1, fy0) : y0;
        int iy1 = real[3] ? snap(L, false, y1, x0, x1, -1, fy1) + 1 : y1 + 1;
        return new int[]{ix0, iy0, ix1, iy1, cnt};
    }

    /**
     * Самая сплошная линия на отрезке [a0, a1] поперёк поиска в диапазоне позиций [p0, p1].
     * vertical — ищем вертикальную (позиция = x, отрезок по y). nearFirst — при равенстве ближе к p1 (к лицу).
     * Возвращает {позиция или −1, сплошность ×1000}.
     */
    static int[] best(Lines L, boolean vertical, int a0, int a1, int p0, int p1, boolean towardHigh) {
        int bestP = -1;
        float bestS = LINE_OK;
        if (p0 > p1) return new int[]{-1, 0};
        for (int k = 0; k <= p1 - p0; k++) {
            int p = towardHigh ? p1 - k : p0 + k; // от лица к краю: при равной сплошности берём ближнюю линию
            float s = vertical ? L.v(p, a0, a1) : L.hz(p, a0, a1);
            if (s > bestS + 0.02f) {
                bestS = s;
                bestP = p;
            }
        }
        return new int[]{bestP, Math.round(bestS * 1000)};
    }

    /** Прямоугольник с подвижной картинкой в углу кадра, отделённый двумя сплошными линиями. */
    static int[] inCorner(Lines L, int[] c, float[] motion, int w) {
        int cw = c[2] - c[0], ch = c[3] - c[1];
        int[] bestBox = null;
        float bestScore = 0;
        for (int corner = 0; corner < 4; corner++) {
            boolean right = corner % 2 == 1, bottom = corner >= 2;
            for (int dx = Math.round(cw * 0.12f); dx <= Math.round(cw * 0.5f); dx++) {
                int x = right ? c[2] - 1 - dx : c[0] + dx;
                int ex0 = right ? x : c[0], ex1 = right ? c[2] : x + 1;
                for (int dy = Math.round(ch * 0.12f); dy <= Math.round(ch * 0.6f); dy++) {
                    int y = bottom ? c[3] - 1 - dy : c[1] + dy;
                    int ey0 = bottom ? y : c[1], ey1 = bottom ? c[3] : y + 1;
                    float sv = L.v(x, ey0, ey1), sh = L.hz(y, ex0, ex1);
                    float s = Math.min(sv, sh);
                    if (s < LINE_OK + 0.08f) continue;
                    float aspect = (ex1 - ex0) / (float) (ey1 - ey0);
                    float area = (ex1 - ex0) * (float) (ey1 - ey0) / (cw * (float) ch);
                    if (aspect < 1.05f || aspect > 2.4f || area > 0.3f) continue; // вебкамера 4:3…16:9, не полкадра
                    float score = s + area * 0.1f;
                    if (score > bestScore) {
                        int[] box = refineCorner(L, c, x, y, right, bottom);
                        if (box == null || coreMotion(motion, w, box) < 2f) continue; // неподвижная плашка интерфейса, а не камера
                        bestScore = score;
                        bestBox = box;
                    }
                }
            }
        }
        return bestBox;
    }

    /** Прямоугольник камеры по двум внутренним линиям угла: края полос рамки и внешние стороны у края кадра. */
    static int[] refineCorner(Lines L, int[] c, int x, int y, boolean right, boolean bottom) {
        int ey0 = bottom ? y : c[1], ey1 = bottom ? c[3] : y + 1, ex0 = right ? x : c[0], ex1 = right ? c[2] : x + 1;
        // внутренние стороны — к внутреннему краю полосы рамки
        int ix = snap(L, true, x, ey0, ey1, right ? +1 : -1, right ? c[2] : c[0] - 1);
        int iy = snap(L, false, y, ex0, ex1, bottom ? +1 : -1, bottom ? c[3] : c[1] - 1);
        // внешние стороны: камера может стоять с отступом от края — ищем рамку у края кадра
        int bw = Math.abs(ex1 - ex0), bh = Math.abs(ey1 - ey0);
        int ox = outer(L, true, ey0, ey1, right ? c[2] - 1 : c[0], right ? -1 : +1, Math.max(3, bw / 8));
        int oy = outer(L, false, ex0, ex1, bottom ? c[3] - 1 : c[1], bottom ? -1 : +1, Math.max(3, bh / 8));
        int x0, x1, y0, y1;
        if (right) {
            x0 = ix + 1;
            x1 = ox >= 0 ? snap(L, true, ox, ey0, ey1, -1, ix) + 1 : c[2];
        } else {
            x0 = ox >= 0 ? snap(L, true, ox, ey0, ey1, +1, ix) : c[0];
            x1 = ix;
        }
        if (bottom) {
            y0 = iy + 1;
            y1 = oy >= 0 ? snap(L, false, oy, ex0, ex1, -1, iy) + 1 : c[3];
        } else {
            y0 = oy >= 0 ? snap(L, false, oy, ex0, ex1, +1, iy) : c[1];
            y1 = iy;
        }
        return x1 - x0 > 4 && y1 - y0 > 4 ? new int[]{x0, y0, x1, y1} : null;
    }

    /**
     * Камера посреди верхнего или нижнего края: сверху (снизу) и по бокам — сплошные линии, четвёртая сторона — край кадра
     * (или линия рамки у самого края). sideways — вызов для левого/правого края на повёрнутой картинке.
     */
    static int[] onEdge(Lines L, int[] c, float[] motion, int w, int h, boolean sideways) {
        int cw = c[2] - c[0], ch = c[3] - c[1];
        int[] best = null;
        float bestScore = 0;
        for (int side = 0; side < 2; side++) {
            boolean bottom = side == 1;
            for (int d = Math.round(ch * 0.12f); d <= Math.round(ch * 0.6f); d++) {
                int y = bottom ? c[3] - 1 - d : c[1] + d;
                int ey0 = bottom ? y : c[1], ey1 = bottom ? c[3] : y + 1;
                int x = c[0];
                while (x < c[2]) {
                    if (!L.hpx(x, y)) {
                        x++;
                        continue;
                    }
                    int start = x, gap = 0;
                    while (x < c[2] && gap <= 2) {
                        gap = L.hpx(x, y) ? 0 : gap + 1;
                        x++;
                    }
                    int end = x - gap; // линия [start, end)
                    if (end - start < cw * 0.12f || end - start > cw * 0.6f) continue;
                    int xa = -1, xb = -1;
                    float sa = 0, sb = 0;
                    for (int k = -2; k <= 2; k++) {
                        float va = L.v(start + k, ey0, ey1), vb = L.v(end - 1 + k, ey0, ey1);
                        if (va > sa) { sa = va; xa = start + k; }
                        if (vb > sb) { sb = vb; xb = end - 1 + k; }
                    }
                    float sh = L.hz(y, start, end), sc = Math.min(sh, Math.min(sa, sb));
                    if (sc < LINE_OK + 0.08f || xb - xa < 6) continue;
                    // внутренность: от внутренних краёв полос линий; сторона у края кадра — край или линия рядом с ним
                    int x0 = snap(L, true, xa, ey0, ey1, +1, xb) + 1, x1 = snap(L, true, xb, ey0, ey1, -1, xa);
                    int iy = snap(L, false, y, x0, x1, bottom ? +1 : -1, bottom ? c[3] : c[1] - 1);
                    int bh = ey1 - ey0;
                    int oy = outer(L, false, x0, x1, bottom ? c[3] - 1 : c[1], bottom ? -1 : +1, Math.max(3, bh / 8));
                    int y0, y1;
                    if (bottom) {
                        y0 = iy + 1;
                        y1 = oy >= 0 ? snap(L, false, oy, x0, x1, -1, iy) + 1 : c[3];
                    } else {
                        y0 = oy >= 0 ? snap(L, false, oy, x0, x1, +1, iy) : c[1];
                        y1 = iy;
                    }
                    if (x1 - x0 < 5 || y1 - y0 < 5) continue;
                    float bw = x1 - x0, bhh = y1 - y0;
                    float aspect = sideways ? bhh / bw : bw / bhh; // пропорция — в настоящей, не повёрнутой картинке
                    float area = bw * bhh / (cw * (float) ch);
                    if (aspect < 1.05f || aspect > 2.4f || area > 0.3f) continue;
                    int[] box = {x0, y0, x1, y1};
                    float score = sc + area * 0.1f;
                    if (score > bestScore && coreMotion(motion, w, box) >= 2f) {
                        bestScore = score;
                        best = box;
                    }
                }
            }
        }
        return best;
    }

    /** Подвижность в середине прямоугольника (без краёв): у камеры там человек, у плашки интерфейса — ничего. */
    static float coreMotion(float[] motion, int w, int[] b) {
        int mx = (b[2] - b[0]) / 4, my = (b[3] - b[1]) / 4;
        return meanIn(motion, w, new int[]{b[0] + mx, b[1] + my, b[2] - mx, b[3] - my});
    }

    /** Линия рамки у края кадра: от края p0 внутрь не дальше max; −1 — рамка вплотную к краю. */
    static int outer(Lines L, boolean vertical, int a0, int a1, int p0, int dir, int max) {
        for (int k = 1; k <= max; k++) {
            int p = p0 + dir * k;
            if ((vertical ? L.v(p, a0, a1) : L.hz(p, a0, a1)) >= 0.85f) return p; // отступ рамки от края — небольшой и линия чёткая
        }
        return -1;
    }

    // ------------------------------------------------------------ экран

    /** Экран — без чёрных полей, подрезан под верх клипа; вебкамеру по возможности не захватывает. */
    static float[] screen(int[] c, int[] cam, int w, int h, int srcW, int srcH) {
        float px = srcW / (float) w, py = srcH / (float) h; // размер пикселя анализа в пикселях исходника
        float cwPx = (c[2] - c[0]) * px, chPx = (c[3] - c[1]) * py;
        if (cam == null) return norm(new float[]{c[0], c[1], c[2] - c[0], c[3] - c[1]}, w, h);
        float bw, bh; // размер подрезки в пикселях анализа
        if (cwPx / chPx > TOP_ASPECT) {
            bh = c[3] - c[1];
            bw = chPx * TOP_ASPECT / px;
        } else {
            bw = c[2] - c[0];
            bh = cwPx / TOP_ASPECT / py;
        }
        float bestCost = Float.MAX_VALUE, bx = c[0], by = c[1];
        float camArea = Math.max(1, (cam[2] - cam[0]) * (float) (cam[3] - cam[1]));
        float ccx = (c[0] + c[2]) / 2f, ccy = (c[1] + c[3]) / 2f;
        for (float x = c[0]; x <= c[2] - bw + 0.01f; x += 1) {
            for (float y = c[1]; y <= c[3] - bh + 0.01f; y += 1) {
                float ox = Math.max(0, Math.min(x + bw, cam[2]) - Math.max(x, cam[0]));
                float oy = Math.max(0, Math.min(y + bh, cam[3]) - Math.max(y, cam[1]));
                float cost = 2 * ox * oy / camArea + Math.abs(x + bw / 2 - ccx) / w + Math.abs(y + bh / 2 - ccy) / h;
                if (cost < bestCost - 1e-6f) {
                    bestCost = cost;
                    bx = x;
                    by = y;
                }
            }
        }
        return norm(new float[]{bx, by, bw, bh}, w, h);
    }

    static float[] norm(float[] r, int w, int h) {
        float x = r[0] / w, y = r[1] / h, rw = Math.min(r[2] / w, 1 - x), rh = Math.min(r[3] / h, 1 - y);
        return new float[]{x, y, rw, rh};
    }

    // ------------------------------------------------------------ мелочи

    static float luma(int c) {
        return 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
    }

    /** Наибольшая разница по каналам R, G, B. */
    static float diff(int a, int b) {
        int dr = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)), dg = Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), db = Math.abs((a & 255) - (b & 255));
        return Math.max(dr, Math.max(dg, db));
    }

    static boolean dark(float[] mean, float[] motion, int w, int x0, int x1, int y0, int y1) {
        float s = 0, m = 0;
        int n = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                s += mean[y * w + x];
                m += motion[y * w + x];
                n++;
            }
        return n > 0 && s / n < 20 && m / n < 3;
    }

    static float meanIn(float[] a, int w, int[] b) {
        double s = 0;
        int n = 0;
        for (int y = b[1]; y < b[3]; y++)
            for (int x = b[0]; x < b[2]; x++) {
                s += a[y * w + x];
                n++;
            }
        return n == 0 ? 0 : (float) (s / n);
    }

    static float[] biggestFace(List<float[]> faces) {
        if (faces == null || faces.isEmpty()) return null;
        float[] big = faces.get(0);
        for (float[] f : faces) if (f[2] * f[3] > big[2] * big[3]) big = f;
        // усредняем лица, которые рядом с самым крупным (одно и то же лицо на разных кадрах)
        List<float[]> near = new ArrayList<>();
        for (float[] f : faces) if (Math.abs(f[0] - big[0]) < 0.08 && Math.abs(f[1] - big[1]) < 0.08) near.add(f);
        float[] s = new float[4];
        for (float[] f : near) for (int i = 0; i < 4; i++) s[i] += f[i] / near.size();
        return s;
    }

    /**
     * Сила «неподвижного» перепада: какой он хотя бы на 3/4 кадров (нижняя четверть модулей).
     * Знак не важен: край рамки вебкамеры то светлее игры за ним, то темнее.
     */
    static float steady(float[] v) {
        float[] c = new float[v.length];
        for (int i = 0; i < v.length; i++) c[i] = Math.abs(v[i]);
        Arrays.sort(c);
        return c[(c.length - 1) / 4];
    }

    /** Сдвиг найденной линии к внутренней стороне её «полосы» (рамка в несколько пикселей). */
    static int snap(Lines L, boolean vertical, int p, int a0, int a1, int dir, int limit) {
        while (p + dir >= 0 && p + dir != limit && (vertical ? L.v(p + dir, a0, a1) : L.hz(p + dir, a0, a1)) >= LINE_OK) p += dir;
        return p;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
