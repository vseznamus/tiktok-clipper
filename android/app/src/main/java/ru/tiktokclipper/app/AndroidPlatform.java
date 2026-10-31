package ru.tiktokclipper.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ru.tiktokclipper.core.ApiError;
import ru.tiktokclipper.core.MiniHttp;
import ru.tiktokclipper.core.Platform;
import ru.tiktokclipper.core.Store;

/**
 * Android-часть: видео берутся из папок, выбранных пользователем (Storage Access Framework —
 * программа видит только эти папки), клипы сохраняются в галерею (Movies/TikTokClips).
 * Идентификатор файла: "<id источника>/<имя файла>" или "_picked/<имя>" для отдельно выбранных видео.
 */
public class AndroidPlatform implements Platform {
    public static final String PICKED = "_picked";
    public static final String IMAGES = "_img"; // картинки для баннера
    static final String[] VIDEO_EXT = {".mp4", ".mkv", ".mov", ".webm", ".m4v", ".3gp", ".ts"};

    final Context ctx;
    final Store store;
    private final Map<String, Uri> uriCache = new ConcurrentHashMap<>();
    private final Map<String, Uri> srtCache = new ConcurrentHashMap<>();

    AndroidPlatform(Context ctx, Store store) {
        this.ctx = ctx;
        this.store = store;
    }

    // ------------------------------------------------------------ файлы

    static boolean isVideoName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        for (String e : VIDEO_EXT) if (l.endsWith(e)) return true;
        return false;
    }

    private SharedPreferences picked() {
        return ctx.getSharedPreferences("picked", Context.MODE_PRIVATE);
    }

    private SharedPreferences pickedImages() {
        return ctx.getSharedPreferences("picked_img", Context.MODE_PRIVATE);
    }

    /** Картинка для баннера (кнопка «Выбрать картинку»). */
    public String registerImage(Uri uri) {
        String name = displayName(uri);
        if (name == null) name = "banner.png";
        String key = name;
        SharedPreferences sp = pickedImages();
        int n = 2;
        while (sp.contains(key) && !uri.toString().equals(sp.getString(key, ""))) {
            int dot = name.lastIndexOf('.');
            key = (dot > 0 ? name.substring(0, dot) : name) + "_" + (n++) + (dot > 0 ? name.substring(dot) : "");
        }
        sp.edit().putString(key, uri.toString()).apply();
        String id = IMAGES + "/" + key;
        uriCache.put(id, uri);
        return id;
    }

    /** Отдельно выбранное видео (кнопка «Выбрать видео на телефоне»). */
    public String registerPicked(Uri uri) {
        String name = displayName(uri);
        if (name == null) name = "video.mp4";
        String key = name;
        SharedPreferences sp = picked();
        int n = 2;
        while (sp.contains(key) && !uri.toString().equals(sp.getString(key, ""))) {
            int dot = name.lastIndexOf('.');
            key = (dot > 0 ? name.substring(0, dot) : name) + "_" + (n++) + (dot > 0 ? name.substring(dot) : "");
        }
        sp.edit().putString(key, uri.toString()).apply();
        String id = PICKED + "/" + key;
        uriCache.put(id, uri);
        return id;
    }

    String displayName(Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{"_display_name"}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    @Override
    public JSONArray listFiles(JSONObject source) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (source == null) {
            for (Map.Entry<String, ?> e : picked().getAll().entrySet()) {
                Uri u = Uri.parse(String.valueOf(e.getValue()));
                long[] sm = sizeAndMtime(u);
                if (sm == null) continue; // доступ пропал (файл удалён)
                String id = PICKED + "/" + e.getKey();
                uriCache.put(id, u);
                out.add(file(e.getKey(), id, sm[0], sm[1]));
            }
        } else {
            String tree = source.optString("folder_uri");
            if (tree.isEmpty()) return new JSONArray();
            Uri treeUri = Uri.parse(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
            String sid = source.optString("id");
            try (Cursor c = ctx.getContentResolver().query(children, cols, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String name = c.getString(1);
                    if (name == null || name.startsWith(".")) continue;
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                    String id = sid + "/" + name;
                    if (name.toLowerCase(Locale.ROOT).endsWith(".srt")) {
                        srtCache.put(id, doc);
                        continue;
                    }
                    if (!isVideoName(name)) continue;
                    uriCache.put(id, doc);
                    out.add(file(name, id, c.getLong(2), c.getLong(3) / 1000.0));
                }
            } catch (SecurityException e) {
                throw new ApiError(403, "Нет доступа к папке источника. Выберите папку заново в «Настройки» → источник.");
            }
        }
        Collections.sort(out, (a, b) -> naturalCompare(a.optString("name"), b.optString("name")));
        return new JSONArray(out);
    }

    private static JSONObject file(String name, String id, long size, double mtime) throws JSONException {
        JSONObject f = new JSONObject();
        f.put("name", name);
        f.put("path", id);
        f.put("size", size);
        f.put("mtime", mtime);
        return f;
    }

    private long[] sizeAndMtime(Uri u) {
        try (Cursor c = ctx.getContentResolver().query(u, new String[]{"_size", "last_modified"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                long mt = 0;
                int i = c.getColumnIndex("last_modified");
                if (i >= 0) mt = c.getLong(i) / 1000;
                return new long[]{c.getLong(0), mt};
            }
        } catch (RuntimeException ignored) {
        }
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(u, "r")) {
            return new long[]{pfd.getStatSize(), 0};
        } catch (Exception e) {
            return null;
        }
    }

    static int naturalCompare(String a, String b) {
        String[] pa = a.toLowerCase(Locale.ROOT).split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        String[] pb = b.toLowerCase(Locale.ROOT).split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            int c;
            if (pa[i].matches("\\d+") && pb[i].matches("\\d+")) {
                c = Long.compare(Long.parseLong(pa[i].length() > 18 ? pa[i].substring(0, 18) : pa[i]),
                        Long.parseLong(pb[i].length() > 18 ? pb[i].substring(0, 18) : pb[i]));
            } else {
                c = pa[i].compareTo(pb[i]);
            }
            if (c != 0) return c;
        }
        return Integer.compare(pa.length, pb.length);
    }

    /** Uri видео по идентификатору (при необходимости перечитывает папку источника). */
    public Uri resolve(String fileId) throws Exception {
        Uri u = uriCache.get(fileId);
        if (u != null) return u;
        int slash = fileId.indexOf('/');
        if (slash <= 0) throw new ApiError(404, "Файл не найден");
        String sid = fileId.substring(0, slash);
        if (sid.equals(IMAGES)) {
            String s = pickedImages().getString(fileId.substring(slash + 1), null);
            if (s == null) throw new ApiError(404, "Картинка не найдена");
            u = Uri.parse(s);
        } else if (sid.equals(PICKED)) {
            String s = picked().getString(fileId.substring(slash + 1), null);
            if (s == null) throw new ApiError(404, "Файл не найден");
            u = Uri.parse(s);
        } else {
            JSONObject src = store.getSource(sid);
            if (src == null) throw new ApiError(404, "Источник не найден");
            listFiles(src);
            u = uriCache.get(fileId);
        }
        if (u == null) throw new ApiError(404, "Файл не найден: " + fileId.substring(slash + 1));
        uriCache.put(fileId, u);
        return u;
    }

    @Override
    public MiniHttp.RangeSource openMedia(String fileId) throws Exception {
        return uriSource(resolve(fileId));
    }

    MiniHttp.RangeSource uriSource(final Uri u) throws IOException {
        final long size;
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(u, "r")) {
            if (pfd == null) throw new IOException("Файл недоступен");
            size = pfd.getStatSize();
        }
        return new MiniHttp.RangeSource() {
            public long size() {
                return size;
            }

            public InputStream open(long offset) throws IOException {
                ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(u, "r");
                ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                in.getChannel().position(offset);
                return in;
            }
        };
    }

    @Override
    public String mimeOf(String fileId) {
        String l = fileId.toLowerCase(Locale.ROOT);
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".webm")) return "video/webm";
        if (l.endsWith(".mkv")) return "video/x-matroska";
        if (l.endsWith(".3gp")) return "video/3gpp";
        if (l.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }

    @Override
    public JSONObject probe(String fileId) throws Exception {
        Uri u = resolve(fileId);
        JSONObject o = probeUri(u);
        o.put("has_srt", sidecarSrt(fileId) != null);
        return o;
    }


    JSONObject probeUri(Uri u) throws Exception {
        JSONObject o = new JSONObject();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, u, null);
        } catch (IOException e) {
            ex.release();
            throw new ApiError(400, "Android не умеет читать этот файл (формат не поддерживается). Подойдут MP4, MKV, WEBM, MOV.");
        }
        try {
            long dur = 0;
            boolean hasV = false, hasA = false, aOk = false, vOk = false;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (f.containsKey(MediaFormat.KEY_DURATION)) dur = Math.max(dur, f.getLong(MediaFormat.KEY_DURATION));
                if (mime.startsWith("video/") && !hasV) {
                    hasV = true;
                    int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    int rot = f.containsKey(MediaFormat.KEY_ROTATION) ? f.getInteger(MediaFormat.KEY_ROTATION) : 0;
                    if (rot == 90 || rot == 270) {
                        int t = w;
                        w = h;
                        h = t;
                    }
                    o.put("width", w);
                    o.put("height", h);
                    o.put("rotation", rot);
                    o.put("vcodec", mime);
                    o.put("fps", f.containsKey(MediaFormat.KEY_FRAME_RATE) ? f.getInteger(MediaFormat.KEY_FRAME_RATE) : 30);
                    vOk = canDecode(f);
                } else if (mime.startsWith("audio/") && !hasA) {
                    hasA = true;
                    o.put("acodec", mime);
                    aOk = canDecode(f);
                }
            }
            if (dur == 0) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(ctx, u);
                    String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) dur = Long.parseLong(d) * 1000;
                } finally {
                    release(r);
                }
            }
            o.put("duration", dur / 1e6);
            o.put("has_video", hasV);
            o.put("has_audio", hasA);
            o.put("video_decodable", vOk);
            o.put("audio_decodable", aOk);
            return o;
        } finally {
            ex.release();
        }
    }

    static boolean canDecode(MediaFormat f) {
        try {
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null;
        } catch (RuntimeException e) {
            return true; // не смогли проверить — пусть попробует сам декодер
        }
    }

    static void release(MediaMetadataRetriever r) {
        try {
            r.release();
        } catch (Exception ignored) {
        }
    }

    Bitmap frame(Uri u, double t, int width) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, u);
            Bitmap b = r.getFrameAtTime((long) (t * 1e6), MediaMetadataRetriever.OPTION_CLOSEST);
            if (b == null) b = r.getFrameAtTime((long) (t * 1e6), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) throw new ApiError(500, "Не удалось получить кадр");
            if (b.getWidth() != width) {
                int h = Math.max(2, Math.round(b.getHeight() * (width / (float) b.getWidth())) / 2 * 2);
                Bitmap s = Bitmap.createScaledBitmap(b, width, h, true);
                if (s != b) b.recycle();
                b = s;
            }
            return b;
        } finally {
            release(r);
        }
    }

    @Override
    public byte[] frameJpeg(String fileId, double t, int width) throws Exception {
        Bitmap b = frame(resolve(fileId), t, width);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 82, bo);
        b.recycle();
        return bo.toByteArray();
    }

    @Override
    public String sidecarSrt(String fileId) {
        try {
            int slash = fileId.indexOf('/');
            if (slash <= 0 || fileId.startsWith(PICKED)) return null;
            String name = fileId.substring(slash + 1), stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            String prefix = fileId.substring(0, slash + 1);
            if (srtCache.isEmpty()) resolve(fileId);
            Uri u = srtCache.get(prefix + stem + ".srt");
            if (u == null) {
                for (Map.Entry<String, Uri> e : srtCache.entrySet()) {
                    if (e.getKey().startsWith(prefix + stem + ".") && e.getKey().toLowerCase(Locale.ROOT).endsWith(".srt")) {
                        u = e.getValue();
                        break;
                    }
                }
            }
            if (u == null) return null;
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
                return new String(Store.readAll(in), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ сведения о системе

    static boolean hasEncoder(String mime) {
        for (android.media.MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    @Override
    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", ru.tiktokclipper.core.Api.VERSION);
            o.put("platform", "android-app");
            o.put("termux", false);
            o.put("auth", false);
            boolean h264 = hasEncoder("video/avc"), aac = hasEncoder("audio/mp4a-latm");
            JSONArray checks = new JSONArray();
            checks.put(new JSONObject().put("name", "Кодировщик видео H.264").put("ok", h264));
            checks.put(new JSONObject().put("name", "Кодировщик звука AAC").put("ok", aac));
            checks.put(new JSONObject().put("name", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")").put("ok", true));
            o.put("checks", checks);
            JSONObject ff = new JSONObject().put("ffmpeg", true).put("libass", true).put("libx264", h264).put("problems", new JSONArray());
            o.put("ffmpeg", ff);
            JSONArray models = new JSONArray(), ids = new JSONArray();
            JSONArray installed = new JSONArray();
            boolean whisperOk = WhisperAsr.available(ctx);
            for (AsrModels.ModelInfo m : AsrModels.ALL) {
                boolean inst = AsrModels.isInstalled(store, m.id);
                boolean ok = !AsrModels.WHISPER.equals(m.engine) || whisperOk;
                models.put(new JSONObject().put("id", m.id).put("name", m.name).put("note", m.note).put("engine", m.engine)
                        .put("size_mb", m.sizeMb).put("installed", inst).put("supported", ok)
                        .put("recommended", m.id.equals(AsrModels.DEFAULT)));
                ids.put(m.id);
                if (inst) installed.put(m.id);
            }
            o.put("asr_models", models);
            o.put("model_ids", ids);
            JSONObject asr = new JSONObject();
            asr.put("vosk", new JSONObject().put("ok", installed.length() > 0).put("models", installed)
                    .put("message", installed.length() > 0 ? "" : "модель не скачана — скачайте ниже"));
            asr.put("whisper", new JSONObject().put("ok", whisperOk).put("message", whisperOk ? "" : WhisperAsr.unavailableReason()));
            o.put("asr", asr);
            o.put("opencv", true); // автопоиск лица: android.media.FaceDetector
            o.put("media_root", "папки, выбранные для источников");
            o.put("output_dir", "Галерея: Movies/TikTokClips");
            o.put("default_model", whisperOk ? AsrModels.DEFAULT : "ru-small");
            o.put("default_preset", JSONObject.NULL);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    // ------------------------------------------------------------ автопоиск вебкамеры

    @Override
    public JSONObject faceDetect(String fileId, double t) throws Exception {
        Uri u = resolve(fileId);
        double dur = probeUri(u).optDouble("duration", 1);
        List<float[]> found = new ArrayList<>();
        for (int k = -20; k <= 20; k += 10) {
            double tt = Math.min(dur - 0.1, Math.max(0, t + k));
            Bitmap b;
            try {
                b = frame(u, tt, 640);
            } catch (Exception e) {
                continue;
            }
            Bitmap rgb = b.copy(Bitmap.Config.RGB_565, false);
            b.recycle();
            int w = rgb.getWidth() / 2 * 2, h = rgb.getHeight();
            if (w != rgb.getWidth()) {
                Bitmap c = Bitmap.createBitmap(rgb, 0, 0, w, h);
                rgb.recycle();
                rgb = c.copy(Bitmap.Config.RGB_565, false);
            }
            FaceDetector.Face[] faces = new FaceDetector.Face[4];
            int n = new FaceDetector(w, h, faces.length).findFaces(rgb, faces);
            for (int i = 0; i < n; i++) {
                if (faces[i].confidence() < 0.3f) continue;
                PointF mid = new PointF();
                faces[i].getMidPoint(mid);
                float ed = faces[i].eyesDistance();
                float fw = ed * 2.2f, fh = ed * 2.8f;
                found.add(new float[]{(mid.x - fw / 2) / w, (mid.y - fh * 0.4f) / h, fw / w, fh / h});
            }
            rgb.recycle();
        }
        JSONObject o = new JSONObject();
        if (found.isEmpty()) {
            o.put("ok", false);
            o.put("error", "Лицо не найдено — задайте рамку вручную");
            return o;
        }
        float[] big = found.get(0);
        for (float[] f : found) if (f[2] * f[3] > big[2] * big[3]) big = f;
        float sx = 0, sy = 0, sw = 0, sh = 0;
        int cnt = 0;
        for (float[] f : found) {
            if (Math.abs(f[0] - big[0]) < 0.08 && Math.abs(f[1] - big[1]) < 0.08) {
                sx += f[0];
                sy += f[1];
                sw += f[2];
                sh += f[3];
                cnt++;
            }
        }
        float x = sx / cnt, y = sy / cnt, fw = sw / cnt, fh = sh / cnt;
        float cx = x + fw / 2, cy = y + fh / 2;
        float rw = Math.min(1f, fw * 3f), rh = Math.min(1f, fh * 3.2f);
        float rx = Math.min(Math.max(0f, cx - rw / 2), 1 - rw), ry = Math.min(Math.max(0f, cy - rh * 0.42f), 1 - rh);
        o.put("ok", true);
        o.put("rect", new JSONArray().put(r4(rx)).put(r4(ry)).put(r4(rw)).put(r4(rh)));
        o.put("faces", found.size());
        return o;
    }

    private static double r4(float v) {
        return Math.round(v * 10000) / 10000.0;
    }

    // ------------------------------------------------------------ клипы: личная копия + галерея

    File clipFile(String id) {
        return new File(new File(store.dir, "clips"), id + ".mp4");
    }

    @Override
    public MiniHttp.RangeSource openClip(JSONObject meta) {
        final File f = clipFile(meta.optString("id"));
        return new MiniHttp.RangeSource() {
            public long size() {
                return f.length();
            }

            public InputStream open(long offset) throws IOException {
                FileInputStream in = new FileInputStream(f);
                in.getChannel().position(offset);
                return in;
            }
        };
    }

    @Override
    public boolean clipExists(JSONObject meta) {
        return clipFile(meta.optString("id")).isFile();
    }

    @Override
    public void deleteClip(JSONObject meta) {
        clipFile(meta.optString("id")).delete();
        for (String k : new String[]{"media_uri", "srt_uri", "ass_uri"}) {
            String u = meta.optString(k);
            if (u.isEmpty()) continue;
            try {
                ctx.getContentResolver().delete(Uri.parse(u), null, null);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Копирует файл в общую память: видео в Movies/TikTokClips, субтитры в Documents/TikTokClips. */
    Uri publishToGallery(File src, String displayName, String mime, boolean video, String existing) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = null;
        if (existing != null && !existing.isEmpty()) {
            uri = Uri.parse(existing);
            try (OutputStream out = cr.openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("нет доступа");
                copy(src, out);
                return uri;
            } catch (Exception e) {
                uri = null; // запись удалили из галереи — создадим заново
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? "Movies" : "Documents") + "/TikTokClips");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri coll = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            uri = cr.insert(coll, v);
            if (uri == null) throw new IOException("Не удалось сохранить в галерею");
            try (OutputStream out = cr.openOutputStream(uri)) {
                copy(src, out);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        }
        // Android 8–9: обычная запись в общую папку (нужно разрешение на запись, его просит MainActivity)
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                video ? android.os.Environment.DIRECTORY_MOVIES : android.os.Environment.DIRECTORY_DOCUMENTS), "TikTokClips");
        dir.mkdirs();
        File dst = new File(dir, displayName);
        try (OutputStream out = new java.io.FileOutputStream(dst)) {
            copy(src, out);
        }
        android.media.MediaScannerConnection.scanFile(ctx, new String[]{dst.getPath()}, new String[]{mime}, null);
        return Uri.fromFile(dst);
    }

    static void copy(File src, OutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    @Override
    public byte[] asset(String path) {
        try (InputStream in = ctx.getAssets().open("web/" + path)) {
            return Store.readAll(in);
        } catch (IOException e) {
            return null;
        }
    }
}
