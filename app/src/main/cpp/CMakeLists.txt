cmake_minimum_required(VERSION 3.22.1)

project("qmx_native")

set(CMAKE_CXX_STANDARD 17)

# Include directories for llama.cpp, ggml, and mtmd headers
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp/tools/mtmd
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp/common
)

# Prebuilt library path
set(JNI_LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libllama.so)

add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libggml-cpu.so)

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libggml-base.so)

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libggml.so)

add_library(omp SHARED IMPORTED)
set_target_properties(omp PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libomp.so)

# TTS: mtmd multimodal library (Qwen3-TTS audio generation)
add_library(mtmd SHARED IMPORTED)
set_target_properties(mtmd PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libmtmd.so)

# TTS: llama-common (required by mtmd for param parsing and sampling)
add_library(llama-common SHARED IMPORTED)
set_target_properties(llama-common PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libllama-common.so)

# Native JNI shared library
add_library(qmx_native SHARED
    native-lib.cpp
)

target_link_libraries(qmx_native
    llama
    llama-common
    mtmd
    ggml-cpu
    ggml-base
    ggml
    omp
    log
    android
)
