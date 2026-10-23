/* Shared native soft-decode player (FFmpeg libav*) rendered to an ANativeWindow.
 * Used by both DLNA and AirPlay: open a URL (or feed raw NALs) -> libavformat/libavcodec decode ->
 * swscale to RGBA -> ANativeWindow. Works on Android 4.4. No libVLC, no MediaCodec, no TCP relay. */
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "ApPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- URL playback (DLNA) ----
typedef struct {
    char url[2048];
    ANativeWindow *window;
    volatile int running;
    volatile int rotate;      // 0/90/180/270
    pthread_t thread;
} UrlPlayer;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static UrlPlayer g_url;

// ---- raw H.264/HEVC playback (AirPlay) ----
typedef struct {
    AVCodecContext *dec;
    AVFrame *frame;
    AVPacket *pkt;
    struct SwsContext *sws;
    ANativeWindow *window;
    int rotate;
    int codec_inited;     // 0 none, 1 h264, 2 hevc
    pthread_mutex_t lock;
} RawPlayer;
static RawPlayer g_raw;

static void draw_frame(AVFrame *f, ANativeWindow *win, struct SwsContext **sws, int rotate) {
    if (!win || !f || f->width <= 0 || f->height <= 0) return;
    int srcW = f->width, srcH = f->height;
    int outW = srcW, outH = srcH;
    if (rotate == 90 || rotate == 270) { outW = srcH; outH = srcW; }
    ANativeWindow_setBuffersGeometry(win, outW, outH, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win, &buf, NULL) != 0) return;
    if (rotate == 0) {
        *sws = sws_getCachedContext(*sws, srcW, srcH, f->format, outW, outH, AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
        uint8_t *dst[4] = { (uint8_t*)buf.bits, NULL, NULL, NULL };
        int dstStride[4] = { buf.stride * 4, 0, 0, 0 };
        sws_scale(*sws, (const uint8_t* const*)f->data, f->linesize, 0, srcH, dst, dstStride);
    } else {
        // decode to a temp RGBA of source size, then rotate copy into window
        AVFrame *rgba = av_frame_alloc();
        rgba->format = AV_PIX_FMT_RGBA; rgba->width = srcW; rgba->height = srcH;
        av_frame_get_buffer(rgba, 32);
        *sws = sws_getCachedContext(*sws, srcW, srcH, f->format, srcW, srcH, AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
        sws_scale(*sws, (const uint8_t* const*)f->data, f->linesize, 0, srcH, rgba->data, rgba->linesize);
        uint32_t *src = (uint32_t*)rgba->data[0];
        int sStride = rgba->linesize[0] / 4;
        uint32_t *dst = (uint32_t*)buf.bits;
        int dStride = buf.stride;
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int sx, sy;
                if (rotate == 90) { sx = y; sy = srcH - 1 - x; }
                else if (rotate == 180) { sx = srcW - 1 - x; sy = srcH - 1 - y; }
                else { sx = srcW - 1 - y; sy = x; } // 270
                dst[y * dStride + x] = src[sy * sStride + sx];
            }
        }
        av_frame_free(&rgba);
    }
    ANativeWindow_unlockAndPost(win);
}

// ================= URL (DLNA) =================
static void *url_thread(void *arg) {
    UrlPlayer *p = (UrlPlayer*)arg;
    AVFormatContext *fmt = NULL;
    AVCodecContext *dec = NULL;
    struct SwsContext *sws = NULL;
    AVFrame *frame = av_frame_alloc();
    AVPacket *pkt = av_packet_alloc();
    int vstream = -1;
    if (avformat_open_input(&fmt, p->url, NULL, NULL) != 0) { LOGE("open_input failed: %s", p->url); goto done; }
    if (avformat_find_stream_info(fmt, NULL) < 0) { LOGE("find_stream_info failed"); goto done; }
    vstream = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (vstream < 0) { LOGE("no video stream"); goto done; }
    {
        const AVCodec *codec = avcodec_find_decoder(fmt->streams[vstream]->codecpar->codec_id);
        if (!codec) { LOGE("no decoder"); goto done; }
        dec = avcodec_alloc_context3(codec);
        avcodec_parameters_to_context(dec, fmt->streams[vstream]->codecpar);
        dec->thread_count = 4;
        if (avcodec_open2(dec, codec, NULL) < 0) { LOGE("open2 failed"); goto done; }
    }
    LOGI("DLNA ffmpeg playing: %s", p->url);
    while (p->running && av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index == vstream) {
            if (avcodec_send_packet(dec, pkt) >= 0) {
                while (avcodec_receive_frame(dec, frame) == 0) {
                    draw_frame(frame, p->window, &sws, p->rotate);
                    av_frame_unref(frame);
                }
            }
        }
        av_packet_unref(pkt);
    }
done:
    if (sws) sws_freeContext(sws);
    if (dec) avcodec_free_context(&dec);
    if (fmt) avformat_close_input(&fmt);
    av_frame_free(&frame);
    av_packet_free(&pkt);
    LOGI("DLNA ffmpeg stopped");
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_gathin_tvhome_NativePlayer_nativePlayUrl(JNIEnv *env, jclass c, jstring jurl, jobject surface, jint rotate) {
    pthread_mutex_lock(&g_lock);
    if (g_url.running) { g_url.running = 0; pthread_join(g_url.thread, NULL); }
    if (g_url.window) { ANativeWindow_release(g_url.window); g_url.window = NULL; }
    const char *u = (*env)->GetStringUTFChars(env, jurl, NULL);
    strncpy(g_url.url, u, sizeof(g_url.url)-1); g_url.url[sizeof(g_url.url)-1]=0;
    (*env)->ReleaseStringUTFChars(env, jurl, u);
    g_url.window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    g_url.rotate = rotate;
    g_url.running = 1;
    if (pthread_create(&g_url.thread, NULL, url_thread, &g_url) != 0) { g_url.running = 0; pthread_mutex_unlock(&g_lock); return JNI_FALSE; }
    pthread_mutex_unlock(&g_lock);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_gathin_tvhome_NativePlayer_nativeStopUrl(JNIEnv *env, jclass c) {
    pthread_mutex_lock(&g_lock);
    if (g_url.running) { g_url.running = 0; pthread_join(g_url.thread, NULL); }
    if (g_url.window) { ANativeWindow_release(g_url.window); g_url.window = NULL; }
    pthread_mutex_unlock(&g_lock);
}

// ================= Raw NAL (AirPlay) =================
JNIEXPORT void JNICALL
Java_com_gathin_tvhome_NativePlayer_nativeSetRawSurface(JNIEnv *env, jclass c, jobject surface, jint rotate) {
    pthread_mutex_lock(&g_raw.lock);
    if (g_raw.window) { ANativeWindow_release(g_raw.window); g_raw.window = NULL; }
    g_raw.window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    g_raw.rotate = rotate;
    LOGI("raw surface set %p rotate=%d", g_raw.window, rotate);
    pthread_mutex_unlock(&g_raw.lock);
}

JNIEXPORT void JNICALL
Java_com_gathin_tvhome_NativePlayer_nativeFeedRaw(JNIEnv *env, jclass c, jint isH265, jbyteArray arr) {
    pthread_mutex_lock(&g_raw.lock);
    if (!g_raw.window) { pthread_mutex_unlock(&g_raw.lock); return; }
    int want = isH265 ? 2 : 1;
    if (g_raw.codec_inited != want) {
        if (g_raw.dec) { avcodec_free_context(&g_raw.dec); g_raw.dec = NULL; }
        const AVCodec *codec = avcodec_find_decoder(isH265 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264);
        if (!codec) { LOGE("no raw decoder"); pthread_mutex_unlock(&g_raw.lock); return; }
        g_raw.dec = avcodec_alloc_context3(codec);
        g_raw.dec->thread_count = 4;
        if (avcodec_open2(g_raw.dec, codec, NULL) < 0) { LOGE("raw open2 failed"); avcodec_free_context(&g_raw.dec); g_raw.dec=NULL; pthread_mutex_unlock(&g_raw.lock); return; }
        if (!g_raw.frame) g_raw.frame = av_frame_alloc();
        if (!g_raw.pkt) g_raw.pkt = av_packet_alloc();
        g_raw.codec_inited = want;
        LOGI("raw decoder inited %s", isH265?"hevc":"h264");
    }
    jsize len = (*env)->GetArrayLength(env, arr);
    jbyte *data = (*env)->GetByteArrayElements(env, arr, NULL);
    g_raw.pkt->data = (uint8_t*)data;
    g_raw.pkt->size = len;
    if (avcodec_send_packet(g_raw.dec, g_raw.pkt) >= 0) {
        while (avcodec_receive_frame(g_raw.dec, g_raw.frame) == 0) {
            draw_frame(g_raw.frame, g_raw.window, &g_raw.sws, g_raw.rotate);
            av_frame_unref(g_raw.frame);
        }
    }
    (*env)->ReleaseByteArrayElements(env, arr, data, JNI_ABORT);
    pthread_mutex_unlock(&g_raw.lock);
}

JNIEXPORT void JNICALL
Java_com_gathin_tvhome_NativePlayer_nativeReleaseRaw(JNIEnv *env, jclass c) {
    pthread_mutex_lock(&g_raw.lock);
    if (g_raw.dec) { avcodec_free_context(&g_raw.dec); g_raw.dec = NULL; }
    if (g_raw.sws) { sws_freeContext(g_raw.sws); g_raw.sws = NULL; }
    if (g_raw.window) { ANativeWindow_release(g_raw.window); g_raw.window = NULL; }
    g_raw.codec_inited = 0;
    pthread_mutex_unlock(&g_raw.lock);
}
