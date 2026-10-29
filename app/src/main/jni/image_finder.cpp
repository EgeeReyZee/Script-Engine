#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vector>
#include <cmath>
#include <algorithm>

// Include only what we need from OpenCV — avoids pulling in heavy STL streams
// from opencv2/opencv.hpp monolith header
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#define TAG "ImageFinder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Reusable Mats — avoid per-call heap allocation
static cv::Mat g_screenGray;
static cv::Mat g_templateGray;
static cv::Mat g_resultMat;
static cv::Mat g_regionMat;

/**
 * Convert Android Bitmap (RGBA_8888 or RGB_565) to grayscale cv::Mat.
 * Caller must have called AndroidBitmap_lockPixels before invoking this.
 * Returns false on unsupported format.
 */
static bool bitmapToGrayMat(JNIEnv *env, jobject bitmap, cv::Mat &out) {
    AndroidBitmapInfo info;
    void *pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("bitmapToGrayMat: getInfo failed");
        return false;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("bitmapToGrayMat: lockPixels failed");
        return false;
    }

    bool ok = true;
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        cv::Mat rgba(info.height, info.width, CV_8UC4, pixels, info.stride);
        cv::cvtColor(rgba, out, cv::COLOR_RGBA2GRAY);
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        cv::Mat rgb565(info.height, info.width, CV_8UC2, pixels, info.stride);
        cv::cvtColor(rgb565, out, cv::COLOR_BGR5652GRAY);
    } else {
        LOGE("bitmapToGrayMat: unsupported format %d", info.format);
        ok = false;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
}

extern "C" {

/**
 * Find single best match of templateBitmap within screenBitmap region [x1,y1,x2,y2].
 * Returns float[3] = {centerX, centerY, confidence}, or null if below threshold.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_scriptengine_engine_ImageFinder_nativeFindImage(
        JNIEnv *env, jobject,
        jobject screenBitmap, jobject templateBitmap,
        jint x1, jint y1, jint x2, jint y2,
        jfloat threshold) {

    if (!bitmapToGrayMat(env, screenBitmap,  g_screenGray))   return nullptr;
    if (!bitmapToGrayMat(env, templateBitmap, g_templateGray)) return nullptr;

    int sw = g_screenGray.cols, sh = g_screenGray.rows;
    x1 = std::max(0, (int)x1); y1 = std::max(0, (int)y1);
    x2 = std::min(sw, (int)x2); y2 = std::min(sh, (int)y2);
    if (x2 <= x1 || y2 <= y1) return nullptr;

    int tw = g_templateGray.cols, th = g_templateGray.rows;
    if (tw > (x2 - x1) || th > (y2 - y1)) return nullptr;

    // submat — no copy, search only in ROI
    g_regionMat = g_screenGray(cv::Rect(x1, y1, x2 - x1, y2 - y1));

    int rw = g_regionMat.cols - tw + 1;
    int rh = g_regionMat.rows - th + 1;
    if (rw <= 0 || rh <= 0) return nullptr;
    g_resultMat.create(rh, rw, CV_32FC1);

    cv::matchTemplate(g_regionMat, g_templateGray, g_resultMat, cv::TM_CCOEFF_NORMED);

    double minVal, maxVal;
    cv::Point minLoc, maxLoc;
    cv::minMaxLoc(g_resultMat, &minVal, &maxVal, &minLoc, &maxLoc);

    if ((float)maxVal < threshold) return nullptr;

    jfloatArray result = env->NewFloatArray(3);
    if (!result) return nullptr;
    float vals[3] = {
        (float)(x1 + maxLoc.x + tw / 2),
        (float)(y1 + maxLoc.y + th / 2),
        (float)maxVal
    };
    env->SetFloatArrayRegion(result, 0, 3, vals);
    return result;
}

/**
 * Find ALL matches above threshold, with simple non-maximum suppression.
 * Returns float[] packed as [x0,y0,conf0, x1,y1,conf1, ...], or null.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_scriptengine_engine_ImageFinder_nativeFindImageAll(
        JNIEnv *env, jobject,
        jobject screenBitmap, jobject templateBitmap,
        jint x1, jint y1, jint x2, jint y2,
        jfloat threshold) {

    if (!bitmapToGrayMat(env, screenBitmap,  g_screenGray))   return nullptr;
    if (!bitmapToGrayMat(env, templateBitmap, g_templateGray)) return nullptr;

    int sw = g_screenGray.cols, sh = g_screenGray.rows;
    x1 = std::max(0, (int)x1); y1 = std::max(0, (int)y1);
    x2 = std::min(sw, (int)x2); y2 = std::min(sh, (int)y2);
    if (x2 <= x1 || y2 <= y1) return nullptr;

    int tw = g_templateGray.cols, th = g_templateGray.rows;
    if (tw > (x2 - x1) || th > (y2 - y1)) return nullptr;

    g_regionMat = g_screenGray(cv::Rect(x1, y1, x2 - x1, y2 - y1));
    int rw = g_regionMat.cols - tw + 1;
    int rh = g_regionMat.rows - th + 1;
    if (rw <= 0 || rh <= 0) return nullptr;
    g_resultMat.create(rh, rw, CV_32FC1);

    cv::matchTemplate(g_regionMat, g_templateGray, g_resultMat, cv::TM_CCOEFF_NORMED);

    // Collect all local maxima above threshold
    std::vector<float> matches;
    int halfW = tw / 2, halfH = th / 2;

    for (int row = 0; row < g_resultMat.rows; row++) {
        const float *ptr = g_resultMat.ptr<float>(row);
        for (int col = 0; col < g_resultMat.cols; col++) {
            float val = ptr[col];
            if (val < threshold) continue;

            // Simple NMS: check 5x5 neighborhood
            int r0 = std::max(0, row - 2), r1 = std::min(g_resultMat.rows - 1, row + 2);
            int c0 = std::max(0, col - 2), c1 = std::min(g_resultMat.cols - 1, col + 2);
            double nbMax;
            cv::minMaxLoc(g_resultMat(cv::Rect(c0, r0, c1-c0+1, r1-r0+1)), nullptr, &nbMax);
            if (val < (float)nbMax - 1e-4f) continue;

            matches.push_back((float)(x1 + col + halfW));
            matches.push_back((float)(y1 + row + halfH));
            matches.push_back(val);
        }
    }

    if (matches.empty()) return nullptr;

    jfloatArray result = env->NewFloatArray((jsize)matches.size());
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, (jsize)matches.size(), matches.data());
    return result;
}

} // extern "C"
