/*
 * Project-owned JNI shim for VozLocal.
 *
 * The vendored whisper.cpp Android example ships only the 3-arg
 * Java_..._fullTranscribe (no language), so it hardcodes
 * params.language = "en" in the native call. That makes Whisper
 * treat Spanish (or any non-English) audio as if it were English,
 * which is why the previous workaround produced English output.
 *
 * This file lives at app/src/main/jni/vozlocal-jni/ and is built
 * alongside the vendored whisper.cpp into the same .so via the
 * CMakeLists in the same directory. It adds the 4-arg
 * Java_..._fullTranscribeWithLang JNI symbol so the Kotlin
 * LibWhisper.kt can pass the user's selected language through to
 * whisper_full_params::language. The vendored jni.c is untouched
 * and continues to provide fullTranscribe + all the other
 * symbols in the same .so.
 */
#include <jni.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "VozLocalJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define UNUSED(x) (void)(x)

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribeWithLang(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring lang) {
    UNUSED(thiz);

    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) {
        LOGE("fullTranscribeWithLang: null context");
        return;
    }

    const char *lang_chars = (*env)->GetStringUTFChars(env, lang, NULL);
    if (lang_chars == NULL) {
        LOGE("fullTranscribeWithLang: failed to read lang string");
        return;
    }

    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang_chars;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    LOGI("whisper_full: language=%s, threads=%d, samples=%d",
         lang_chars, num_threads, audio_data_length);

    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGE("whisper_full failed");
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, lang, lang_chars);
}
