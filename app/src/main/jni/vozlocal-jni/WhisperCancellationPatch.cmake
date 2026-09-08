# Generate an app-owned translation unit; never dirty the upstream submodule.
# whisper.cpp 1.9.2 forwards abort callbacks to its non-scheduled graph helper,
# but not to the scheduler path used by the encoder/decoder. CPU graph execution
# consequently runs to completion even when the Kotlin cancellation signal fires.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${WHISPER_LIB_DIR}/src/whisper.cpp")
file(READ "${WHISPER_LIB_DIR}/src/whisper.cpp" VOZLOCAL_WHISPER_SOURCE)

function(vozlocal_replace_exact BEFORE AFTER EXPECTED)
    set(REMAINDER "${VOZLOCAL_WHISPER_SOURCE}")
    set(COUNT 0)
    string(LENGTH "${BEFORE}" NEEDLE_LENGTH)
    while(TRUE)
        string(FIND "${REMAINDER}" "${BEFORE}" POSITION)
        if(POSITION EQUAL -1)
            break()
        endif()
        math(EXPR COUNT "${COUNT} + 1")
        math(EXPR NEXT "${POSITION} + ${NEEDLE_LENGTH}")
        string(SUBSTRING "${REMAINDER}" ${NEXT} -1 REMAINDER)
    endwhile()
    if(NOT COUNT EQUAL EXPECTED)
        message(FATAL_ERROR "Whisper cancellation patch expected ${EXPECTED} matches, found ${COUNT}. Re-review the upstream scheduler before building.")
    endif()
    string(REPLACE "${BEFORE}" "${AFTER}" PATCHED "${VOZLOCAL_WHISPER_SOURCE}")
    set(VOZLOCAL_WHISPER_SOURCE "${PATCHED}" PARENT_SCOPE)
endfunction()

vozlocal_replace_exact(
    "bool   sched_reset = true) {"
    "bool   sched_reset = true,\n         ggml_abort_callback abort_callback = nullptr,\n         void * abort_callback_data = nullptr) {"
    1)

vozlocal_replace_exact(
    "        auto * fn_set_n_threads = (ggml_backend_set_n_threads_t) ggml_backend_reg_get_proc_address(reg, \"ggml_backend_set_n_threads\");"
    "        // Forward (or clear) the callback on every scheduled graph, including warmup.\n        auto * fn_set_abort = (ggml_backend_set_abort_callback_t) ggml_backend_reg_get_proc_address(reg, \"ggml_backend_set_abort_callback\");\n        if (fn_set_abort) {\n            fn_set_abort(backend, abort_callback, abort_callback_data);\n        }\n\n        auto * fn_set_n_threads = (ggml_backend_set_n_threads_t) ggml_backend_reg_get_proc_address(reg, \"ggml_backend_set_n_threads\");"
    1)

# Three encoder graphs (convolution, encoder, cross-attention) and one decoder.
# VAD's separate call retains its default null callback and scheduler policy.
vozlocal_replace_exact(
    "ggml_graph_compute_helper(sched, gf, n_threads)"
    "ggml_graph_compute_helper(sched, gf, n_threads, true, abort_callback, abort_callback_data)"
    4)

# The public whisper_get_timings API returns averages and allocates with C++ new.
# Read totals in this translation unit instead: no cross-language deallocation,
# and counters remain scoped to the last whisper_reset_timings/inference lease.
string(APPEND VOZLOCAL_WHISPER_SOURCE [=[

extern "C" void vozlocal_get_timing_totals(struct whisper_context * ctx, float * values) {
    for (int i = 0; i < 5; ++i) values[i] = 0.0f;
    if (ctx == nullptr || ctx->state == nullptr) return;
    values[0] = 1e-3f * ctx->state->t_sample_us;
    values[1] = 1e-3f * ctx->state->t_encode_us;
    values[2] = 1e-3f * ctx->state->t_decode_us;
    values[3] = 1e-3f * ctx->state->t_batchd_us;
    values[4] = 1e-3f * ctx->state->t_prompt_us;
}
]=])

set(VOZLOCAL_WHISPER_CPP "${CMAKE_CURRENT_BINARY_DIR}/whisper-vozlocal.cpp")
file(WRITE "${VOZLOCAL_WHISPER_CPP}" "${VOZLOCAL_WHISPER_SOURCE}")
string(SHA256 VOZLOCAL_WHISPER_HASH "${VOZLOCAL_WHISPER_SOURCE}")
string(SUBSTRING "${VOZLOCAL_WHISPER_HASH}" 0 12 VOZLOCAL_WHISPER_HASH)
# Distinguish this native implementation in benchmark/calibration provenance.
set(WHISPER_VERSION "${WHISPER_VERSION}-vozlocal-${VOZLOCAL_WHISPER_HASH}")
