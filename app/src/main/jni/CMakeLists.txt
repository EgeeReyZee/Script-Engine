cmake_minimum_required(VERSION 3.22)
project("scriptengine")

# ── OpenCV SDK path ───────────────────────────────────────────────────────────
set(OPENCV_SDK "${CMAKE_CURRENT_SOURCE_DIR}/OpenCV-android-sdk/sdk/native")

# Import libopencv_java4.so as pre-built shared lib
add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES
    IMPORTED_LOCATION "${OPENCV_SDK}/libs/${ANDROID_ABI}/libopencv_java4.so"
)

include_directories("${OPENCV_SDK}/jni/include")

# ── Our native library ────────────────────────────────────────────────────────
add_library(scriptengine SHARED
    screen_capture.cpp
    image_finder.cpp
)

find_library(jnigraphics-lib jnigraphics)
find_library(log-lib log)
find_library(android-lib android)

# ── Per-ABI compile flags — NEVER use ARM flags for x86_64 ───────────────────
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(scriptengine PRIVATE -O3 -march=armv8-a+simd -ffast-math)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(scriptengine PRIVATE -O3 -march=armv7-a -mfpu=neon -ffast-math)
else()
    # x86, x86_64 — generic only
    target_compile_options(scriptengine PRIVATE -O2)
endif()

target_link_libraries(scriptengine
    opencv_java4
    ${jnigraphics-lib}
    ${log-lib}
    ${android-lib}
)
