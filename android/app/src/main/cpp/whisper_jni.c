// Мост JNI между приложением и whisper.cpp: загрузка модели, распознавание, слова с таймкодами.
#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "narezka-whisper"

static void log_cb(enum ggml_log_level level, const char *text, void *ud) {
    (void) ud;
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_write(ANDROID_LOG_ERROR, TAG, text);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, TAG, text);
}

// Загружает подходящий процессору вариант вычислительного ядра из папки библиотек приложения.
JNIEXPORT jstring JNICALL
Java_ru_tiktokclipper_app_WhisperAsr_nativeInitBackends(JNIEnv *env, jclass cls, jstring libDir) {
    (void) cls;
    ggml_log_set(log_cb, NULL);
    whisper_log_set(log_cb, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, libDir, NULL);
    ggml_backend_load_all_from_path(dir);
    (*env)->ReleaseStringUTFChars(env, libDir, dir);
    char info[512];
    snprintf(info, sizeof info, "devices=%d; %s", (int) ggml_backend_dev_count(), whisper_print_system_info());
    return (*env)->NewStringUTF(env, info);
}

JNIEXPORT jlong JNICALL
Java_ru_tiktokclipper_app_WhisperAsr_nativeLoad(JNIEnv *env, jclass cls, jstring path) {
    (void) cls;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_ru_tiktokclipper_app_WhisperAsr_nativeFree(JNIEnv *env, jclass cls, jlong h) {
    (void) env;
    (void) cls;
    if (h) whisper_free((struct whisper_context *) (intptr_t) h);
}

/* Отмена из любого потока Java (кнопка «Отменить»): проверяется и посреди расчёта окна, а не только между окнами. */
static volatile int g_abort = 0;

JNIEXPORT void JNICALL
Java_ru_tiktokclipper_app_WhisperAsr_nativeAbort(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    g_abort = 1;
}

typedef struct {
    JNIEnv *env;
    jobject listener;
    jmethodID progress; // boolean progress(int percent): false — отменить
    volatile int cancel;
} cb_data;

static void progress_cb(struct whisper_context *ctx, struct whisper_state *st, int pct, void *ud) {
    (void) ctx;
    (void) st;
    cb_data *d = ud;
    if (g_abort) d->cancel = 1;
    if (d->cancel) return;
    if (!(*d->env)->CallBooleanMethod(d->env, d->listener, d->progress, pct)) d->cancel = 1;
    if ((*d->env)->ExceptionCheck(d->env)) {
        (*d->env)->ExceptionClear(d->env);
        d->cancel = 1;
    }
}

static bool encoder_begin_cb(struct whisper_context *ctx, struct whisper_state *st, void *ud) {
    progress_cb(ctx, st, -1, ud);
    return !((cb_data *) ud)->cancel;
}

static bool abort_cb(void *ud) {
    return ((cb_data *) ud)->cancel != 0 || g_abort != 0;
}

typedef struct {
    char *p;
    size_t n, cap;
} buf_t;

static void put(buf_t *b, const char *s, size_t n) {
    if (b->n + n + 1 > b->cap) {
        size_t cap = b->cap ? b->cap : 4096;
        while (b->n + n + 1 > cap) cap *= 2;
        char *q = realloc(b->p, cap);
        if (!q) return;
        b->p = q;
        b->cap = cap;
    }
    memcpy(b->p + b->n, s, n);
    b->n += n;
    b->p[b->n] = 0;
}

static void puts_(buf_t *b, const char *s) { put(b, s, strlen(s)); }

static void flush_word(buf_t *out, buf_t *w, int seg, int64_t t0, int64_t t1) {
    if (!w->n) return;
    char head[64];
    snprintf(head, sizeof head, "W\t%d\t%lld\t%lld\t", seg, (long long) t0, (long long) t1);
    puts_(out, head);
    // табуляции и переводы строк внутри слова не нужны
    for (size_t i = 0; i < w->n; i++) if (w->p[i] == '\t' || w->p[i] == '\n' || w->p[i] == '\r') w->p[i] = ' ';
    put(out, w->p, w->n);
    puts_(out, "\n");
    w->n = 0;
}

/*
 * Распознаёт 16 кГц моно. Возвращает UTF-8 строки:
 *   L\t<язык>
 *   S\t<номер>\t<t0>\t<t1>\t<вероятность тишины>     — сегмент (время в сотых секунды)
 *   W\t<номер сегмента>\t<t0>\t<t1>\t<слово>          — слово
 * null — модель не смогла распознать (ошибка) или отмена.
 */
JNIEXPORT jbyteArray JNICALL
Java_ru_tiktokclipper_app_WhisperAsr_nativeTranscribe(JNIEnv *env, jclass cls, jlong h, jfloatArray pcm,
                                                      jstring lang, jint threads, jobject listener) {
    (void) cls;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) h;
    if (!ctx) return NULL;
    cb_data d;
    d.env = env;
    d.listener = listener;
    d.progress = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, listener), "progress", "(I)Z");
    d.cancel = 0;
    g_abort = 0;
    if (!d.progress) return NULL;

    const char *lg = (*env)->GetStringUTFChars(env, lang, NULL);
    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.language = lg;
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;        // не тянуть текст из прошлого окна — меньше «зацикливаний»
    p.token_timestamps = true;  // время каждого слова
    p.suppress_nst = true;      // без [музыка], (смех) и т. п.
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.progress_callback = progress_cb;
    p.progress_callback_user_data = &d;
    p.encoder_begin_callback = encoder_begin_cb;
    p.encoder_begin_callback_user_data = &d;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = &d;

    jsize n = (*env)->GetArrayLength(env, pcm);
    jfloat *samples = (*env)->GetFloatArrayElements(env, pcm, NULL);
    int rc = whisper_full(ctx, p, samples, n);
    (*env)->ReleaseFloatArrayElements(env, pcm, samples, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, lang, lg);
    if (rc != 0 || d.cancel || g_abort) return NULL;

    buf_t out = {0}, w = {0};
    char line[160];
    int lid = whisper_full_lang_id(ctx);
    snprintf(line, sizeof line, "L\t%s\n", lid >= 0 ? whisper_lang_str(lid) : "");
    puts_(&out, line);
    const whisper_token eot = whisper_token_eot(ctx);
    const int ns = whisper_full_n_segments(ctx);
    for (int s = 0; s < ns; s++) {
        snprintf(line, sizeof line, "S\t%d\t%lld\t%lld\t%.3f\n", s,
                 (long long) whisper_full_get_segment_t0(ctx, s), (long long) whisper_full_get_segment_t1(ctx, s),
                 whisper_full_get_segment_no_speech_prob(ctx, s));
        puts_(&out, line);
        int64_t t0 = 0, t1 = 0;
        const int nt = whisper_full_n_tokens(ctx, s);
        for (int i = 0; i < nt; i++) {
            whisper_token_data td = whisper_full_get_token_data(ctx, s, i);
            if (td.id >= eot) continue; // служебные токены и метки времени
            const char *txt = whisper_full_get_token_text(ctx, s, i);
            if (!txt || !*txt) continue;
            // токен с пробелом в начале — новое слово (буквы кириллицы могут быть разрезаны между токенами,
            // поэтому байты склеиваются до границы слова и только потом превращаются в текст)
            if (txt[0] == ' ' && w.n) flush_word(&out, &w, s, t0, t1);
            if (!w.n) t0 = td.t0;
            t1 = td.t1;
            put(&w, txt[0] == ' ' ? txt + 1 : txt, strlen(txt[0] == ' ' ? txt + 1 : txt));
        }
        flush_word(&out, &w, s, t0, t1);
    }
    free(w.p);
    jbyteArray res = (*env)->NewByteArray(env, (jsize) out.n);
    if (res && out.n) (*env)->SetByteArrayRegion(env, res, 0, (jsize) out.n, (const jbyte *) out.p);
    free(out.p);
    return res;
}
