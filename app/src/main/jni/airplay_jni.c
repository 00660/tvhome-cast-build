#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>

#include "raop.h"
#include "stream.h"
#include "logger.h"
#include "dnssd.h"

#define TAG "AirPlayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_vm = NULL;
static jobject g_obj = NULL;        // global ref to AirPlayReceiver
static jmethodID m_on_video = NULL; // (I[B)V  isH265, data
static jmethodID m_on_audio = NULL; // ([B V
static jmethodID m_on_conn = NULL;  // (Z)V
static jmethodID m_on_mirror = NULL;// (Z)V

static raop_t *g_raop = NULL;
static dnssd_t *g_dnssd = NULL;
static logger_t *g_logger = NULL;
static int g_running = 0;

static JNIEnv *get_env(int *attached) {
    *attached = 0;
    if (!g_vm) return NULL;
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return NULL;
        *attached = 1;
    }
    return env;
}

// ---- raop callbacks ----
static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    if (!g_obj || !m_on_video || !data || !data->data || data->data_len <= 0) return;
    int attached; JNIEnv *env = get_env(&attached);
    if (!env) return;
    jbyteArray arr = (*env)->NewByteArray(env, data->data_len);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, data->data_len, (const jbyte *) data->data);
        (*env)->CallVoidMethod(env, g_obj, m_on_video, data->is_h265 ? JNI_TRUE : JNI_FALSE, arr);
        (*env)->DeleteLocalRef(env, arr);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    if (!g_obj || !m_on_audio || !data || !data->data || data->data_len <= 0) return;
    int attached; JNIEnv *env = get_env(&attached);
    if (!env) return;
    jbyteArray arr = (*env)->NewByteArray(env, data->data_len);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, data->data_len, (const jbyte *) data->data);
        (*env)->CallVoidMethod(env, g_obj, m_on_audio, arr);
        (*env)->DeleteLocalRef(env, arr);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

static void cb_conn_init(void *cls) {
    if (!g_obj || !m_on_conn) return;
    int attached; JNIEnv *env = get_env(&attached);
    if (env) { (*env)->CallVoidMethod(env, g_obj, m_on_conn, JNI_TRUE);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}
static void cb_conn_destroy(void *cls) {
    if (!g_obj || !m_on_conn) return;
    int attached; JNIEnv *env = get_env(&attached);
    if (env) { (*env)->CallVoidMethod(env, g_obj, m_on_conn, JNI_FALSE);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}
static void cb_mirror_video_running(void *cls, bool is_running) {
    if (!g_obj || !m_on_mirror) return;
    int attached; JNIEnv *env = get_env(&attached);
    if (env) { (*env)->CallVoidMethod(env, g_obj, m_on_mirror, is_running ? JNI_TRUE : JNI_FALSE);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}
static void cb_video_pause(void *cls) {}
static void cb_video_resume(void *cls) {}
static void cb_video_flush(void *cls) {}
static void cb_audio_flush(void *cls) {}
static void cb_conn_reset(void *cls, int reason) {}
static void cb_video_reset(void *cls, reset_type_t reset_type) {}
static void cb_conn_feedback(void *cls) {}
static double cb_audio_set_client_volume(void *cls) { return 0.0; }
static void cb_audio_set_volume(void *cls, float volume) {}
static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) {}
static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) {}
static void cb_audio_stop_coverart_rendering(void *cls) {}
static void cb_audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote_header) {}
static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {}
static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    if (ct) *ct = 1;            // AAC
    if (spf) *spf = 1024;
    if (usingScreen) *usingScreen = false;
    if (isMedia) *isMedia = true;
}
static void cb_video_report_size(void *cls, float *w_src, float *h_src, float *w, float *h) {}
static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) { if (admit) *admit = true; }
static void cb_display_pin(void *cls, char *pin) {}
static void cb_register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {}
static bool cb_check_register(void *cls, const char *pk_str) { return true; }
static const char *cb_passwd(void *cls, int *len) { if (len) *len = 0; return NULL; }
static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) {}
static int cb_video_set_codec(void *cls, video_codec_t codec) { return 0; }
static void cb_on_video_play(void *cls, const char *location, const float start_position) {}
static void cb_on_video_scrub(void *cls, const float position) {}
static void cb_on_video_rate(void *cls, const float rate) {}
static void cb_on_video_stop(void *cls) {}
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *p) {}
static float cb_on_video_playlist_remove(void *cls) { return 0; }
static void cb_get_custom_profile(void *cls, const char **custom_profile) {}
static void cb_log(void *cls, int level, const char *msg) { LOGI("[uxplay] %s", msg ? msg : ""); }

static raop_callbacks_t make_callbacks(void) {
    raop_callbacks_t c;
    memset(&c, 0, sizeof(c));
    c.video_process = cb_video_process;
    c.audio_process = cb_audio_process;
    c.conn_init = cb_conn_init;
    c.conn_destroy = cb_conn_destroy;
    c.mirror_video_running = cb_mirror_video_running;
    c.video_pause = cb_video_pause;
    c.video_resume = cb_video_resume;
    c.video_flush = cb_video_flush;
    c.audio_flush = cb_audio_flush;
    c.conn_reset = cb_conn_reset;
    c.video_reset = cb_video_reset;
    c.conn_feedback = cb_conn_feedback;
    c.audio_set_client_volume = cb_audio_set_client_volume;
    c.audio_set_volume = cb_audio_set_volume;
    c.audio_set_metadata = cb_audio_set_metadata;
    c.audio_set_coverart = cb_audio_set_coverart;
    c.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    c.audio_remote_control_id = cb_audio_remote_control_id;
    c.audio_set_progress = cb_audio_set_progress;
    c.audio_get_format = cb_audio_get_format;
    c.video_report_size = cb_video_report_size;
    c.report_client_request = cb_report_client_request;
    c.display_pin = cb_display_pin;
    c.register_client = cb_register_client;
    c.check_register = cb_check_register;
    c.passwd = cb_passwd;
    c.export_dacp = cb_export_dacp;
    c.video_set_codec = cb_video_set_codec;
    c.on_video_play = cb_on_video_play;
    c.on_video_scrub = cb_on_video_scrub;
    c.on_video_rate = cb_on_video_rate;
    c.on_video_stop = cb_on_video_stop;
    c.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    c.on_video_playlist_remove = cb_on_video_playlist_remove;
    c.get_custom_profile = cb_get_custom_profile;
    return c;
}

// ---- JNI entry points ----
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_gathin_tvhome_AirPlayReceiver_nativeStart(JNIEnv *env, jobject thiz,
        jstring jName, jstring jMac, jstring jKeyfile, jint width, jint height) {
    if (g_running) return JNI_TRUE;
    g_obj = (*env)->NewGlobalRef(env, thiz);
    jclass cls = (*env)->GetObjectClass(env, thiz);
    m_on_video = (*env)->GetMethodID(env, cls, "onVideo", "(I[B)V");
    m_on_audio = (*env)->GetMethodID(env, cls, "onAudio", "([B)V");
    m_on_conn  = (*env)->GetMethodID(env, cls, "onConnection", "(Z)V");
    m_on_mirror= (*env)->GetMethodID(env, cls, "onMirrorRunning", "(Z)V");
    if (!m_on_video || !m_on_audio || !m_on_conn) {
        LOGE("method lookup failed");
        (*env)->DeleteGlobalRef(env, g_obj); g_obj = NULL;
        m_on_video = m_on_audio = m_on_conn = m_on_mirror = NULL;
        return JNI_FALSE;
    }

    const char *name = (*env)->GetStringUTFChars(env, jName, NULL);
    const char *mac  = (*env)->GetStringUTFChars(env, jMac, NULL);
    const char *key  = (*env)->GetStringUTFChars(env, jKeyfile, NULL);

    raop_callbacks_t cbs = make_callbacks();
    g_raop = raop_init(&cbs);
    if (!g_raop) { LOGE("raop_init failed"); goto fail; }
    raop_set_log_callback(g_raop, cb_log, NULL);
    raop_set_log_level(g_raop, 2);
    if (raop_init2(g_raop, 1, mac, key)) { LOGE("raop_init2 failed"); goto fail; }
    if (width > 0) raop_set_plist(g_raop, "width", width);
    if (height > 0) raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "maxFPS", 30);

    unsigned short raop_port = 0;
    unsigned short tcp[2] = {0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);
    raop_port = raop_get_port(g_raop);
    /* httpd_start returns 1 on success, 0 if already running, <0 on error. */
    if (raop_start_httpd(g_raop, &raop_port) < 0) { LOGE("raop_start_httpd failed"); goto fail; }
    raop_set_port(g_raop, raop_port);
    LOGI("raop httpd on port %d", raop_port);

    int dnssd_error = 0;
    unsigned char hw[6] = {0xAA,0xBB,0xCC,0xDD,0xEE,0xFF};
    g_dnssd = dnssd_init(name, strlen(name), (const char *) hw, 6, 0, &dnssd_error);
    if (g_dnssd) {
        raop_set_dnssd(g_raop, g_dnssd);
        if (dnssd_register_raop(g_dnssd, raop_port) != 0) {
            LOGE("dnssd_register_raop failed");
            goto fail;
        }
        if (dnssd_register_airplay(g_dnssd, raop_port) != 0) {
            LOGE("dnssd_register_airplay failed");
            goto fail;
        }
        LOGI("dnssd registered as '%s'", name);
    } else {
        LOGE("dnssd_init failed err=%d", dnssd_error);
        goto fail;
    }

    g_running = 1;
    (*env)->ReleaseStringUTFChars(env, jName, name);
    (*env)->ReleaseStringUTFChars(env, jMac, mac);
    (*env)->ReleaseStringUTFChars(env, jKeyfile, key);
    return JNI_TRUE;

fail:
    (*env)->ReleaseStringUTFChars(env, jName, name);
    (*env)->ReleaseStringUTFChars(env, jMac, mac);
    (*env)->ReleaseStringUTFChars(env, jKeyfile, key);
    if (g_raop) { raop_destroy(g_raop); g_raop = NULL; }
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
    if (g_obj) { (*env)->DeleteGlobalRef(env, g_obj); g_obj = NULL; }
    m_on_video = m_on_audio = m_on_conn = m_on_mirror = NULL;
    g_running = 0;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_gathin_tvhome_AirPlayReceiver_nativeStop(JNIEnv *env, jobject thiz) {
    /* Stop RAOP first: active RAOP connections can still reference the dnssd_t. */
    if (g_raop) { raop_destroy(g_raop); g_raop = NULL; }
    if (g_dnssd) { dnssd_unregister_raop(g_dnssd); dnssd_unregister_airplay(g_dnssd); dnssd_destroy(g_dnssd); g_dnssd = NULL; }
    if (g_obj) { (*env)->DeleteGlobalRef(env, g_obj); g_obj = NULL; }
    g_running = 0;
}
