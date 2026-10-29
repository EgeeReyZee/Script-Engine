#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstdint>

#define TAG "ScreenCapture"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNICALL
Java_com_scriptengine_engine_ScreenCapture_nativeGetColor(
        JNIEnv *env, jobject,
        jobject bitmap, jint x, jint y) {

    AndroidBitmapInfo info;
    void *pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;
    if (x < 0 || y < 0 || x >= (jint)info.width || y >= (jint)info.height) return -1;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;

    jint result = -1;
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        auto *row = reinterpret_cast<uint32_t *>(
                reinterpret_cast<uint8_t *>(pixels) + y * info.stride);
        uint32_t px = row[x];
        uint8_t r = px & 0xFF, g = (px >> 8) & 0xFF, b = (px >> 16) & 0xFF, a = (px >> 24) & 0xFF;
        result = (a << 24) | (r << 16) | (g << 8) | b;
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        auto *row = reinterpret_cast<uint16_t *>(
                reinterpret_cast<uint8_t *>(pixels) + y * info.stride);
        uint16_t px = row[x];
        uint8_t r = ((px >> 11) & 0x1F) << 3, g = ((px >> 5) & 0x3F) << 2, b = (px & 0x1F) << 3;
        result = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_scriptengine_engine_ScreenCapture_nativeGetColors(
        JNIEnv *env, jobject,
        jobject bitmap, jintArray xArray, jintArray yArray) {

    AndroidBitmapInfo info;
    void *pixels = nullptr;
    jsize count = env->GetArrayLength(xArray);

    jintArray result = env->NewIntArray(count);
    if (!result) return nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return result;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return result;

    jint *xs  = env->GetIntArrayElements(xArray, nullptr);
    jint *ys  = env->GetIntArrayElements(yArray, nullptr);
    jint *out = env->GetIntArrayElements(result,  nullptr);

    auto *base = reinterpret_cast<uint8_t *>(pixels);

    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        for (jsize i = 0; i < count; i++) {
            int x = xs[i], y = ys[i];
            if (x < 0 || y < 0 || x >= (int)info.width || y >= (int)info.height) { out[i] = -1; continue; }
            uint32_t px = reinterpret_cast<uint32_t *>(base + y * info.stride)[x];
            uint8_t r = px & 0xFF, g = (px >> 8) & 0xFF, b = (px >> 16) & 0xFF, a = (px >> 24) & 0xFF;
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        for (jsize i = 0; i < count; i++) {
            int x = xs[i], y = ys[i];
            if (x < 0 || y < 0 || x >= (int)info.width || y >= (int)info.height) { out[i] = -1; continue; }
            uint16_t px = reinterpret_cast<uint16_t *>(base + y * info.stride)[x];
            uint8_t r = ((px >> 11) & 0x1F) << 3, g = ((px >> 5) & 0x3F) << 2, b = (px & 0x1F) << 3;
            out[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    }

    env->ReleaseIntArrayElements(xArray, xs,  JNI_ABORT);
    env->ReleaseIntArrayElements(yArray, ys,  JNI_ABORT);
    env->ReleaseIntArrayElements(result, out, 0);

    AndroidBitmap_unlockPixels(env, bitmap);
    return result;
}

} // extern "C"
