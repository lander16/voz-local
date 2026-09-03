cmake_minimum_required(VERSION 3.10)

# Project-owned build config. Based on the vendored
# app/src/main/jni/whisper/examples/whisper.android/lib/src/main/jni/whisper/CMakeLists.txt
# (which we do NOT modify). Adds vozlocal-jni.c (the JNI shim) to the source list
# so the resulting .so exports both the vendored fullTranscribe and the new
# fullTranscribeWithLang symbols.
project(whisper.cpp_vozlocal)
set(CMAKE_CXX_STANDARD 17)

# Path to the vendored whisper source tree (sibling of vozlocal-jni/).
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../whisper)

# Get whisper.cpp version
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()

message(STATUS " VozLocal JNI: Whisper version ${WHISPER_VERSION}")

# Source list: vendored whisper.cpp + vendored jni.c + project shim.
# jni.c is read but not modified; vozlocal-jni.c is project-owned.
set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${WHISPER_LIB_DIR}/examples/whisper.android/lib/src/main/jni/whisper/jni.c
    ${CMAKE_CURRENT_SOURCE_DIR}/vozlocal-jni.c
    )

find_library(LOG_LIB log)

include(FetchContent)

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        # Keep the distributed ARM64 path compatible with CPUs that do not
        # implement optional FEAT_I8MM. Configure ggml itself (not only the
        # JNI wrapper) so its CPU kernels receive the same safe dot-product
        # baseline selected for this library.
        set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "ggml ARM CPU architecture" FORCE)
        target_compile_options(${target_name} PRIVATE -march=${GGML_CPU_ARM_ARCH})
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        set(GGML_COMPILE_OPTIONS -mfpu=neon-vfpv4)
    endif ()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)
    endif ()

    if (GGML_HOME)
        FetchContent_Declare(ggml SOURCE_DIR ${GGML_HOME})
    else()
        FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    endif()
    FetchContent_MakeAvailable(ggml)
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)


endfunction()

# Match the vendored output names so the Kotlin LibWhisper.kt loader (which
# looks for whisper_v8fp16_va / whisper_vfpv4 / whisper) keeps working.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

build_library("whisper") # Default target

# Match the vendored CMakeLists: add the include paths that whisper.cpp / jni.c
# need. whisper.cpp does `#include "whisper.h"` (quotes, relative lookup), so the
# include dir must contain the vendored include/ tree.
include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
