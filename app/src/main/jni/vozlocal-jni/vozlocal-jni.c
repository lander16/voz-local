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
#include <stdatomic.h>
#include <stdbool.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#if defined(__aarch64__)
#include <sys/auxv.h>
#endif
#include "ggml-backend.h"
#include "whisper.h"

#define LOG_TAG "VozLocalJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define UNUSED(x) (void)(x)

// whisper_full is synchronous. Keep an abort flag per native context so a
// cancelled Kotlin coroutine can interrupt encoder/decoder work promptly.
struct vozlocal_abort_state {
    struct whisper_context *context;
    atomic_bool requested;
    struct vozlocal_abort_state *next;
};

static pthread_mutex_t abort_states_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct vozlocal_abort_state *abort_states = NULL;

// ggml's backend registry is process-global. Select exactly once, before the
// first Whisper context is created, and keep a stable diagnostic snapshot.
static pthread_mutex_t cpu_backend_mutex = PTHREAD_MUTEX_INITIALIZER;
static bool cpu_backend_initialized = false;
static char cpu_backend_diagnostics[1024] =
        "status=uninitialized;mode=compatibility;tier=none;features=none";

static void append_text(char *buffer, size_t capacity, const char *text) {
    const size_t used = strlen(buffer);
    if (used + 1 < capacity) {
        snprintf(buffer + used, capacity - used, "%s", text);
    }
}

static void describe_cpu_backend(bool automatic) {
    ggml_backend_reg_t reg = ggml_backend_reg_by_name("CPU");
    if (reg == NULL) {
        snprintf(cpu_backend_diagnostics, sizeof(cpu_backend_diagnostics),
                 "status=error;mode=%s;tier=none;features=none;error=cpu_backend_unavailable",
                 automatic ? "automatic" : "compatibility");
        return;
    }

    char features[384] = "";
    bool has_dotprod = false;
    bool has_fp16 = false;
    bool has_i8mm = false;
    bool has_sve = false;
    bool has_sme = false;
    ggml_backend_get_features_t get_features =
            (ggml_backend_get_features_t) ggml_backend_reg_get_proc_address(
                    reg, "ggml_backend_get_features");
    if (get_features != NULL) {
        struct ggml_backend_feature *feature = get_features(reg);
        for (; feature != NULL && feature->name != NULL; ++feature) {
            if (features[0] != '\0') append_text(features, sizeof(features), ",");
            append_text(features, sizeof(features), feature->name);
            if (strcmp(feature->name, "DOTPROD") == 0) has_dotprod = true;
            if (strcmp(feature->name, "FP16_VA") == 0) has_fp16 = true;
            if (strcmp(feature->name, "MATMUL_INT8") == 0) has_i8mm = true;
            if (strcmp(feature->name, "SVE") == 0) has_sve = true;
            if (strcmp(feature->name, "SME") == 0) has_sme = true;
        }
    }
    if (features[0] == '\0') snprintf(features, sizeof(features), "none");

    const char *tier = "baseline";
    if (has_sme) tier = "sme";
    else if (has_sve && has_i8mm) tier = "sve-i8mm";
    else if (has_i8mm) tier = "i8mm";
    else if (has_fp16) tier = "fp16-dotprod";
    else if (has_dotprod) tier = "dotprod";

#if defined(__aarch64__)
    const unsigned long hwcap = getauxval(AT_HWCAP);
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);
    snprintf(cpu_backend_diagnostics, sizeof(cpu_backend_diagnostics),
             "status=ready;mode=%s;tier=%s;features=%s;hwcap=%lx;hwcap2=%lx;build=%s",
             automatic ? "automatic" : "compatibility", tier, features, hwcap, hwcap2,
             WHISPER_VERSION);
#else
    snprintf(cpu_backend_diagnostics, sizeof(cpu_backend_diagnostics),
             "status=ready;mode=%s;tier=%s;features=%s;build=%s",
             automatic ? "automatic" : "compatibility", tier, features, WHISPER_VERSION);
#endif
}

static ggml_backend_reg_t load_android_cpu_variant(
        const char *directory, const char *filename) {
    char explicit_path[1024];
    snprintf(explicit_path, sizeof(explicit_path), "%s/%s", directory, filename);
    if (access(explicit_path, R_OK) == 0) {
        return ggml_backend_load(explicit_path);
    }
    // With extractNativeLibs=false Android's linker can resolve packaged JNI
    // libraries by soname even though they cannot be enumerated as files.
    return ggml_backend_load(filename);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initializeCpuBackend(
        JNIEnv *env, jobject thiz, jstring native_library_dir, jboolean automatic) {
    UNUSED(thiz);
    pthread_mutex_lock(&cpu_backend_mutex);
    if (!cpu_backend_initialized) {
        const char *directory = native_library_dir == NULL ? NULL :
                (*env)->GetStringUTFChars(env, native_library_dir, NULL);
        if (native_library_dir != NULL && directory == NULL) {
            snprintf(cpu_backend_diagnostics, sizeof(cpu_backend_diagnostics),
                     "status=error;mode=%s;tier=none;features=none;error=invalid_native_library_dir",
                     automatic ? "automatic" : "compatibility");
        } else {
#if defined(__aarch64__)
            if (directory == NULL || directory[0] == '\0') {
                snprintf(cpu_backend_diagnostics, sizeof(cpu_backend_diagnostics),
                         "status=error;mode=%s;tier=none;features=none;error=missing_native_library_dir",
                         automatic ? "automatic" : "compatibility");
            } else {
                if (automatic) {
                    // Preference order is capability-based. Each module's score
                    // function is compiled at the ARMv8-A baseline and returns
                    // zero before optimized code can run on an incompatible CPU.
                    // SVE/SME tiers stay benchmark-gated: architectural support
                    // alone does not establish that they outperform I8MM on a
                    // heterogeneous mobile SoC.
                    static const char *variants[] = {
                            "libggml-cpu-android_armv8.6_1.so",
                            "libggml-cpu-android_armv8.2_2.so",
                            "libggml-cpu-android_armv8.2_1.so",
                            "libggml-cpu-android_armv8.0_1.so",
                    };
                    for (size_t i = 0; i < sizeof(variants) / sizeof(variants[0]); ++i) {
                        if (load_android_cpu_variant(directory, variants[i]) != NULL) break;
                    }
                    describe_cpu_backend(true);
                } else {
                    load_android_cpu_variant(
                            directory, "libggml-cpu-android_armv8.0_1.so");
                    describe_cpu_backend(false);
                }
            }
#else
            // Non-ARM builds retain ggml's linked CPU backend.
            describe_cpu_backend(false);
#endif
        }
        if (directory != NULL) {
            (*env)->ReleaseStringUTFChars(env, native_library_dir, directory);
        }
        cpu_backend_initialized = true;
        LOGI("CPU backend: %s", cpu_backend_diagnostics);
    }
    jstring result = (*env)->NewStringUTF(env, cpu_backend_diagnostics);
    pthread_mutex_unlock(&cpu_backend_mutex);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getCpuBackendDiagnostics(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    pthread_mutex_lock(&cpu_backend_mutex);
    jstring result = (*env)->NewStringUTF(env, cpu_backend_diagnostics);
    pthread_mutex_unlock(&cpu_backend_mutex);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_warmupContext(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) return JNI_FALSE;

    // Exercise model buffers and the selected kernels without allowing silence
    // to enter the normal fallback decoder and hallucinate unbounded tokens.
    float silence[3200] = {0};
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "en";
    params.n_threads = num_threads;
    params.single_segment = true;
    params.no_timestamps = true;
    params.no_context = true;
    params.audio_ctx = 256;
    params.max_tokens = 1;
    params.temperature_inc = 0.0f;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    return whisper_full(context, params, silence, 3200) == 0 ? JNI_TRUE : JNI_FALSE;
}

static struct vozlocal_abort_state *abort_state_for_context(
        struct whisper_context *context, bool create) {
    pthread_mutex_lock(&abort_states_mutex);
    struct vozlocal_abort_state *state = abort_states;
    while (state != NULL && state->context != context) {
        state = state->next;
    }
    if (state == NULL && create) {
        state = calloc(1, sizeof(*state));
        if (state != NULL) {
            state->context = context;
            atomic_init(&state->requested, false);
            state->next = abort_states;
            abort_states = state;
        }
    }
    pthread_mutex_unlock(&abort_states_mutex);
    return state;
}

static bool vozlocal_abort_callback(void *data) {
    struct vozlocal_abort_state *state = data;
    return state != NULL && atomic_load_explicit(&state->requested, memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_requestAbort(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    pthread_mutex_lock(&abort_states_mutex);
    struct vozlocal_abort_state *state = abort_states;
    while (state != NULL && state->context != context) {
        state = state->next;
    }
    if (state != NULL) {
        atomic_store_explicit(&state->requested, true, memory_order_relaxed);
    }
    pthread_mutex_unlock(&abort_states_mutex);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_forgetAbortState(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    pthread_mutex_lock(&abort_states_mutex);
    struct vozlocal_abort_state **cursor = &abort_states;
    while (*cursor != NULL && (*cursor)->context != context) {
        cursor = &(*cursor)->next;
    }
    if (*cursor != NULL) {
        struct vozlocal_abort_state *state = *cursor;
        *cursor = state->next;
        free(state);
    }
    pthread_mutex_unlock(&abort_states_mutex);
}

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
        jfloat temperature_inc, jboolean no_context, jint audio_ctx) {
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
    params.audio_ctx = (audio_ctx > 0 && audio_ctx <= 1500) ? audio_ctx : 0;
    params.offset_ms = 0;
    struct vozlocal_abort_state *abort_state = abort_state_for_context(context, true);
    if (abort_state == NULL) {
        LOGE("fullTranscribeWithParams: could not allocate abort state");
    } else {
        atomic_store_explicit(&abort_state->requested, false, memory_order_relaxed);
        params.abort_callback = vozlocal_abort_callback;
        params.abort_callback_user_data = abort_state;
    }

    LOGI("whisper_full: strategy=%s, language=%s, threads=%d, samples=%d, "
         "single_segment=%d, print_timestamps=%d, no_timestamps=%d, no_speech_thold=%.2f, "
         "logprob_thold=%.2f, entropy_thold=%.2f, vad=%d, beam_size=%d, temperature_inc=%.2f, "
         "audio_ctx=%d",
         (strategy == WHISPER_SAMPLING_BEAM_SEARCH) ? "beam" : "greedy",
         params.language, num_threads, audio_data_length,
         (int) single_segment, (int) print_timestamps, (int) no_timestamps,
         no_speech_thold, logprob_thold, entropy_thold,
         (int) params.vad, beam_size, params.temperature_inc,
         params.audio_ctx);

    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGE("whisper_full failed");
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    if (lang != NULL) (*env)->ReleaseStringUTFChars(env, lang, lang_chars);
    if (initial_prompt != NULL) (*env)->ReleaseStringUTFChars(env, initial_prompt, prompt_chars);
    if (vad_model_path != NULL) (*env)->ReleaseStringUTFChars(env, vad_model_path, vad_chars);
}
