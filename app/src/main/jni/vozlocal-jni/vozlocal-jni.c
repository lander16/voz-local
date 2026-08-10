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

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribeWithParams(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data,
        jstring lang, jstring initial_prompt,
        jboolean single_segment, jboolean print_timestamps,
        jfloat no_speech_thold, jfloat logprob_thold, jfloat entropy_thold,
        jstring vad_model_path, jint beam_size, jboolean no_timestamps,
        jfloat temperature_inc, jboolean no_context) {
    UNUSED(thiz);

    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) {
        LOGE("fullTranscribeWithParams: null context");
        return;
    }

    const char *lang_chars = NULL;
    if (lang != NULL) {
        lang_chars = (*env)->GetStringUTFChars(env, lang, NULL);
        if (lang_chars == NULL) {
            LOGE("fullTranscribeWithParams: failed to read lang string");
            return;
        }
    }

    const char *prompt_chars = NULL;
    if (initial_prompt != NULL) {
        prompt_chars = (*env)->GetStringUTFChars(env, initial_prompt, NULL);
        if (prompt_chars == NULL) {
            LOGE("fullTranscribeWithParams: failed to read initial_prompt string");
            if (lang != NULL) (*env)->ReleaseStringUTFChars(env, lang, lang_chars);
            return;
        }
    }

    const char *vad_chars = NULL;
    if (vad_model_path != NULL) {
        vad_chars = (*env)->GetStringUTFChars(env, vad_model_path, NULL);
        if (vad_chars == NULL) {
            LOGE("fullTranscribeWithParams: failed to read vad_model_path string");
            if (lang != NULL) (*env)->ReleaseStringUTFChars(env, lang, lang_chars);
            if (initial_prompt != NULL) (*env)->ReleaseStringUTFChars(env, initial_prompt, prompt_chars);
            return;
        }
    }

    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    if (audio_data_arr == NULL) {
        LOGE("fullTranscribeWithParams: failed to read audio data");
        if (lang != NULL) (*env)->ReleaseStringUTFChars(env, lang, lang_chars);
        if (initial_prompt != NULL) (*env)->ReleaseStringUTFChars(env, initial_prompt, prompt_chars);
        if (vad_model_path != NULL) (*env)->ReleaseStringUTFChars(env, vad_model_path, vad_chars);
        return;
    }

    // Sanity-clamp the caller-provided thresholds.
    if (no_speech_thold < 0.0f) no_speech_thold = 0.0f;
    if (no_speech_thold > 1.0f) no_speech_thold = 1.0f;
    if (entropy_thold < 0.0f) entropy_thold = 0.0f;
    if (entropy_thold > 10.0f) entropy_thold = 10.0f;

    enum whisper_sampling_strategy strategy = WHISPER_SAMPLING_GREEDY;
    if (beam_size > 1) {
        strategy = WHISPER_SAMPLING_BEAM_SEARCH;
    }

    struct whisper_full_params params = whisper_full_default_params(strategy);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_special = false;
    params.translate = false;
    params.language = (lang_chars != NULL) ? lang_chars : "auto";
    if (prompt_chars != NULL) {
        params.initial_prompt = prompt_chars;
    }
    params.single_segment = single_segment;
    params.print_timestamps = print_timestamps;
    params.no_timestamps = no_timestamps;
    params.temperature_inc = temperature_inc < 0.0f ? 0.0f : temperature_inc;
    params.no_speech_thold = no_speech_thold;
    params.logprob_thold = logprob_thold;
    params.entropy_thold = entropy_thold;
    if (vad_chars != NULL) {
        params.vad = true;
        params.vad_model_path = vad_chars;
    }
    if (strategy == WHISPER_SAMPLING_BEAM_SEARCH) {
        params.beam_search.beam_size = beam_size;
    }
    params.n_threads = num_threads;
    params.no_context = no_context;
    params.offset_ms = 0;

    LOGI("whisper_full: strategy=%s, language=%s, threads=%d, samples=%d, "
         "single_segment=%d, print_timestamps=%d, no_timestamps=%d, no_speech_thold=%.2f, "
         "logprob_thold=%.2f, entropy_thold=%.2f, vad=%d, beam_size=%d, temperature_inc=%.2f",
         (strategy == WHISPER_SAMPLING_BEAM_SEARCH) ? "beam" : "greedy",
         params.language, num_threads, audio_data_length,
         (int) single_segment, (int) print_timestamps, (int) no_timestamps,
         no_speech_thold, logprob_thold, entropy_thold,
         (int) params.vad, beam_size, params.temperature_inc);

    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGE("whisper_full failed");
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    if (lang != NULL) (*env)->ReleaseStringUTFChars(env, lang, lang_chars);
    if (initial_prompt != NULL) (*env)->ReleaseStringUTFChars(env, initial_prompt, prompt_chars);
    if (vad_model_path != NULL) (*env)->ReleaseStringUTFChars(env, vad_model_path, vad_chars);
}
