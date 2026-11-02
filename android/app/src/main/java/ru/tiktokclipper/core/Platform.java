package ru.tiktokclipper.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** То, что зависит от Android: файлы через SAF, видео, кадры, галерея, ленты. */
public interface Platform {
    /** Видео в папке источника: [{name, path, size, mtime}], path = "<id источника>/<имя файла>". */
    JSONArray listFiles(JSONObject source) throws Exception;

    MiniHttp.RangeSource openMedia(String fileId) throws Exception;

    String mimeOf(String fileId);

    /** {duration, width, height, fps, has_audio, has_video, vcodec, acodec, has_srt}. */
    JSONObject probe(String fileId) throws Exception;

    byte[] frameJpeg(String fileId, double t, int width) throws Exception;

    /** Текст файла .srt рядом с видео или null. */
    String sidecarSrt(String fileId);

    JSONObject status();

    JSONObject faceDetect(String fileId, double t) throws Exception;

    /** Автоподбор рамок вебкамеры и экрана по кадрам видео: {ok, layout, cam_rect, screen_rect, how}. См. {@link Layout}. */
    JSONObject detectLayout(String fileId) throws Exception;

    MiniHttp.RangeSource openClip(JSONObject meta) throws Exception;

    boolean clipExists(JSONObject meta);

    void deleteClip(JSONObject meta);

    /** Файл интерфейса из assets/web или null. */
    byte[] asset(String path);
}
