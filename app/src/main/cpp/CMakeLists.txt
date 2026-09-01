cmake_minimum_required(VERSION 3.22.1)

project("qmx_native")

set(CMAKE_CXX_STANDARD 17)

# Include directories for llama.cpp and ggml headers
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp/ggml/include
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

# Native JNI shared library
add_library(qmx_native SHARED
    native-lib.cpp
)

target_link_libraries(qmx_native
    llama
    ggml-cpu
    ggml-base
    ggml
    omp
    log
    android
)
