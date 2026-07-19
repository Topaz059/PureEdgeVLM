#pragma once
#include <jni.h>

// 锁定 Android Bitmap，返回像素指针和尺寸。
// 用完必须调用 unlockBitmap 解锁（否则跨线程访问会崩）。
unsigned char* lockBitmap(JNIEnv* env, jobject bitmap, int* out_w, int* out_h);
void unlockBitmap(JNIEnv* env, jobject bitmap);
