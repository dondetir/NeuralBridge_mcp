/**
 * JPEG Encoder - JNI Native Implementation
 *
 * Hardware-accelerated JPEG encoding using libjpeg-turbo.
 * Called from ScreenshotPipeline.kt for fast screenshot compression.
 *
 * Performance target: <20ms for 1080p JPEG encoding with quality 80
 *
 * Build requirements:
 * - libjpeg-turbo library
 * - Android NDK with NEON support
 */

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <vector>

// TODO Week 5: Include libjpeg-turbo headers after library integration
// #include <turbojpeg.h>

#define LOG_TAG "NeuralBridge-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/**
 * Encode Android Bitmap to JPEG using libjpeg-turbo
 *
 * @param env JNI environment
 * @param thiz Object instance (ScreenshotPipeline)
 * @param bitmap Source bitmap (ARGB_8888)
 * @param quality JPEG quality (1-100)
 * @return JPEG bytes as jbyteArray
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_neuralbridge_companion_screenshot_ScreenshotPipeline_encodeJpegNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jint quality) {

    LOGI("JPEG encoding requested: quality=%d", quality);

    // Get bitmap info
    AndroidBitmapInfo info;
    int result = AndroidBitmap_getInfo(env, bitmap, &info);
    if (result != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get bitmap info: %d", result);
        return nullptr;
    }

    LOGD("Bitmap info: %dx%d, format=%d", info.width, info.height, info.format);

    // Validate format
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format: %d (expected RGBA_8888)", info.format);
        return nullptr;
    }

    // Lock bitmap pixels
    void* pixels;
    result = AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (result != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock bitmap pixels: %d", result);
        return nullptr;
    }

    // TODO Week 5: Implement libjpeg-turbo encoding
    // 1. Create tjhandle with tjInitCompress()
    // 2. Convert RGBA to RGB (remove alpha channel)
    // 3. Call tjCompress2() with:
    //    - TJPF_RGB pixel format
    //    - TJSAMP_420 subsampling for quality/size balance
    //    - Quality parameter from argument
    // 4. Get compressed JPEG bytes and size
    // 5. Destroy tjhandle with tjDestroy()
    //
    // Example code structure:
    //
    // tjhandle tj = tjInitCompress();
    //
    // // Convert RGBA to RGB
    // std::vector<uint8_t> rgb_buffer(info.width * info.height * 3);
    // uint8_t* src = (uint8_t*)pixels;
    // uint8_t* dst = rgb_buffer.data();
    // for (uint32_t i = 0; i < info.width * info.height; i++) {
    //     dst[0] = src[0]; // R
    //     dst[1] = src[1]; // G
    //     dst[2] = src[2]; // B
    //     // Skip alpha (src[3])
    //     src += 4;
    //     dst += 3;
    // }
    //
    // // Compress to JPEG
    // unsigned char* jpeg_buf = nullptr;
    // unsigned long jpeg_size = 0;
    // int result = tjCompress2(
    //     tj,
    //     rgb_buffer.data(),
    //     info.width,
    //     0, // pitch (0 = automatic)
    //     info.height,
    //     TJPF_RGB,
    //     &jpeg_buf,
    //     &jpeg_size,
    //     TJSAMP_420,
    //     quality,
    //     TJFLAG_FASTDCT | TJFLAG_NOREALLOC
    // );
    //
    // if (result != 0) {
    //     LOGE("JPEG compression failed: %s", tjGetErrorStr());
    //     tjDestroy(tj);
    //     return nullptr;
    // }
    //
    // // Copy to Java byte array
    // jbyteArray jpeg_array = env->NewByteArray(jpeg_size);
    // env->SetByteArrayRegion(jpeg_array, 0, jpeg_size, (jbyte*)jpeg_buf);
    //
    // // Cleanup
    // tjFree(jpeg_buf);
    // tjDestroy(tj);
    //
    // return jpeg_array;

    // Placeholder: Return empty array until libjpeg-turbo is integrated
    LOGE("libjpeg-turbo integration not yet implemented");

    // Unlock bitmap
    AndroidBitmap_unlockPixels(env, bitmap);

    // Return empty array
    jbyteArray empty_array = env->NewByteArray(0);
    return empty_array;
}

/**
 * Get library version
 *
 * Utility function to verify JNI is working correctly.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_neuralbridge_companion_screenshot_ScreenshotPipeline_getNativeLibraryVersion(
    JNIEnv* env,
    jobject /* thiz */) {

    // TODO Week 5: Return libjpeg-turbo version
    // return env->NewStringUTF(tjGetVersionString());

    return env->NewStringUTF("NeuralBridge JNI v0.1.0 (libjpeg-turbo not yet integrated)");
}

/**
 * JNI_OnLoad - Called when library is loaded
 */
JNIEXPORT jint JNI_OnLoad(JavaVM* /* vm */, void* /* reserved */) {
    LOGI("NeuralBridge JNI library loaded");
    return JNI_VERSION_1_6;
}

/**
 * JNI_OnUnload - Called when library is unloaded
 */
JNIEXPORT void JNI_OnUnload(JavaVM* /* vm */, void* /* reserved */) {
    LOGI("NeuralBridge JNI library unloaded");
}
