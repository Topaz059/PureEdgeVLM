#include <jni.h>
#include <vector>
#include <string>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "yolo_detector.h"
#include "scene_classifier.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "YOLO", __VA_ARGS__)
#define LOGS(...) __android_log_print(ANDROID_LOG_INFO, "SCENE", __VA_ARGS__)

static YoloDetector g_detector;
static bool g_loaded = false;
static SceneClassifier g_scene;
static bool g_scene_loaded = false;
static AAssetManager* g_am = nullptr;
static std::string g_init_debug;   // 初始化（加载模型）的结果，长期保留
static std::string g_detect_debug; // 最近一次检测的结果

static void setInitDebug(const std::string& s) {
    g_init_debug = s;
    LOGI("%s", s.c_str());
}

static void setDetectDebug(const std::string& s) {
    g_detect_debug = s;
    LOGI("%s", s.c_str());
}

// 从 assets 里把模型文件整段读进内存
static std::vector<unsigned char> readAsset(const char* path) {
    std::vector<unsigned char> buf;
    if (!g_am) return buf;
    AAsset* asset = AAssetManager_open(g_am, path, AASSET_MODE_BUFFER);
    if (!asset) return buf;
    size_t len = AAsset_getLength(asset);
    buf.resize(len);
    AAsset_read(asset, buf.data(), len);
    AAsset_close(asset);
    return buf;
}

// 初始化：拿到 AssetManager，加载 YOLO 模型
extern "C" JNIEXPORT void JNICALL
Java_com_topaz_pureedgevlm_NativeBridge_nativeInit(JNIEnv* env, jclass, jobject assetManager) {
    g_am = AAssetManager_fromJava(env, assetManager);
    if (!g_am) {
        setInitDebug("init: AAssetManager is null");
        return;
    }
    if (g_loaded) return;
    auto param = readAsset("models/yolo/model.ncnn.param");
    auto bin = readAsset("models/yolo/model.ncnn.bin");
    if (param.empty() || bin.empty()) {
        setInitDebug("init: asset read empty param=" + std::to_string(param.size()) +
                     " bin=" + std::to_string(bin.size()));
        return;
    }
    int loadCode = g_detector.loadFromMemory(param.data(), param.size(),
                                              bin.data(), bin.size());
    g_loaded = (loadCode == 0);
    std::string loadMsg = (loadCode == 0) ? "ok" :
                          (loadCode == 1) ? "param_failed" : "model_failed";
    setInitDebug("init: param=" + std::to_string(param.size()) +
                 " bin=" + std::to_string(bin.size()) +
                 " load=" + loadMsg);

    // 顺带加载场景识别模型（ResNet50 Places365，fp32 版）
    auto sparam = readAsset("models/scene/resnet50_fp32.param");
    auto sbin = readAsset("models/scene/resnet50_fp32.bin");
    int sCode = 0;
    if (sparam.empty() || sbin.empty()) {
        sCode = -1;
    } else {
        sCode = g_scene.loadFromMemory(sparam.data(), sparam.size(),
                                         sbin.data(), sbin.size());
    }
    g_scene_loaded = (sCode == 0);
    LOGS("scene init: param=%zu bin=%zu load=%d",
          sparam.size(), sbin.size(), sCode);
}

// YOLO 检测：返回 YoloBox 对象数组
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_topaz_pureedgevlm_NativeBridge_yoloDetect(JNIEnv* env, jclass, jobject bitmap, jfloat conf, jfloat nms) {
    if (!g_loaded) {
        // 模型没加载成功，返回空数组，避免 Kotlin 侧拿 null 崩溃
        setDetectDebug("detect: model not loaded");
        jclass cls = env->FindClass("com/topaz/pureedgevlm/YoloBox");
        return env->NewObjectArray(0, cls, nullptr);
    }
    std::vector<YoloBox> boxes;
    float max_score = 0.0f;
    int max_label = -1;
    if (g_loaded) {
        boxes = g_detector.detect(env, bitmap, conf, nms, &max_score, &max_label);
    }
    setDetectDebug(std::string("loaded=") + (g_loaded ? "1" : "0") +
                   " maxScore=" + std::to_string(max_score) +
                   " maxLabel=" + std::to_string(max_label) +
                   " boxes=" + std::to_string(boxes.size()));

    jclass cls = env->FindClass("com/topaz/pureedgevlm/YoloBox");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(FFFFIF)V");
    jobjectArray arr = env->NewObjectArray((jsize)boxes.size(), cls, nullptr);
    for (size_t i = 0; i < boxes.size(); i++) {
        jobject obj = env->NewObject(cls, ctor,
            boxes[i].x1, boxes[i].y1, boxes[i].x2, boxes[i].y2,
            boxes[i].label, boxes[i].score);
        env->SetObjectArrayElement(arr, (jsize)i, obj);
        env->DeleteLocalRef(obj);
    }
    env->DeleteLocalRef(cls);
    return arr;
}

// 场景识别：返回 SceneResult 对象数组（前 5 个，按概率降序）
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_topaz_pureedgevlm_NativeBridge_sceneRecognize(JNIEnv* env, jclass, jobject bitmap) {
    if (!g_scene_loaded) {
        LOGS("scene: model not loaded");
        jclass cls = env->FindClass("com/topaz/pureedgevlm/SceneResult");
        return env->NewObjectArray(0, cls, nullptr);
    }
    std::vector<SceneTop> tops = g_scene.classify(env, bitmap, 5);

    jclass cls = env->FindClass("com/topaz/pureedgevlm/SceneResult");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IF)V");
    jobjectArray arr = env->NewObjectArray((jsize)tops.size(), cls, nullptr);
    for (size_t i = 0; i < tops.size(); i++) {
        jobject obj = env->NewObject(cls, ctor, tops[i].index, tops[i].prob);
        env->SetObjectArrayElement(arr, (jsize)i, obj);
        env->DeleteLocalRef(obj);
    }
    env->DeleteLocalRef(cls);
    return arr;
}

// 返回初始化 + 检测两段调试信息，供 Kotlin 显示在界面上
extern "C" JNIEXPORT jstring JNICALL
Java_com_topaz_pureedgevlm_NativeBridge_getDebug(JNIEnv* env, jclass) {
    std::string all;
    if (g_init_debug.empty()) {
        all = "init: (no log - nativeInit may not have run)";
    } else {
        all = g_init_debug;
    }
    if (!g_detect_debug.empty()) {
        all += "\n";
        all += g_detect_debug;
    }
    return env->NewStringUTF(all.c_str());
}
