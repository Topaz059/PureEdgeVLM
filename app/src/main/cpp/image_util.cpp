#include "image_util.h"
#include <android/bitmap.h>

unsigned char* lockBitmap(JNIEnv* env, jobject bitmap, int* out_w, int* out_h) {
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    void* pixels = nullptr;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (out_w) *out_w = info.width;
    if (out_h) *out_h = info.height;
    return (unsigned char*)pixels;
}

void unlockBitmap(JNIEnv* env, jobject bitmap) {
    AndroidBitmap_unlockPixels(env, bitmap);
}
