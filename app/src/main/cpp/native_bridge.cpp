#include <jni.h>
#include <vector>
#include <string>
#include <chrono>
#include <algorithm>
#include <fstream>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include "yolo_detector.h"
#include "scene_classifier.h"
#include "image_util.h"
#include "ocr/ppocrv5.h"
#include "ocr/ppocrv5_dict.h"
#include "llm_engine.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "YOLO", __VA_ARGS__)
#define LOGS(...) __android_log_print(ANDROID_LOG_INFO, "SCENE", __VA_ARGS__)
#define LOGO(...) __android_log_print(ANDROID_LOG_INFO, "OCR", __VA_ARGS__)

static YoloDetector g_detector;
static bool g_loaded = false;
static SceneClassifier g_scene;
static bool g_scene_loaded = false;
static PPOCRv5 g_ocr;
static bool g_ocr_loaded = false;
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

    // 顺带加载 OCR 模型（PP-OCRv5 mobile：det + rec）
    // OCR 库自带从 AAssetManager 直接读取的 load 版本，路径相对 assets 根目录。
    // mobile 模型用 fp16（与上游一致，更快、精度基本无损）；纯 CPU 不开 GPU。
    int oCode = g_ocr.load(g_am,
                           "models/ocr/PP_OCRv5_mobile_det.ncnn.param",
                           "models/ocr/PP_OCRv5_mobile_det.ncnn.bin",
                           "models/ocr/PP_OCRv5_mobile_rec.ncnn.param",
                           "models/ocr/PP_OCRv5_mobile_rec.ncnn.bin",
                           /*use_fp16=*/true, /*use_gpu=*/false);
    g_ocr_loaded = (oCode == 0);
    LOGO("ocr init: load=%d loaded=%d", oCode, g_ocr_loaded ? 1 : 0);
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
    float yolo_ms = 0.0f;
    if (g_loaded) {
        auto t0 = std::chrono::high_resolution_clock::now();
        boxes = g_detector.detect(env, bitmap, conf, nms, &max_score, &max_label);
        auto t1 = std::chrono::high_resolution_clock::now();
        yolo_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();
    }
    setDetectDebug(std::string("loaded=") + (g_loaded ? "1" : "0") +
                   " maxScore=" + std::to_string(max_score) +
                   " maxLabel=" + std::to_string(max_label) +
                   " boxes=" + std::to_string(boxes.size()) +
                   " time=" + std::to_string(yolo_ms) + "ms");
    if (g_loaded) LOGI("yolo time=%.1f ms", yolo_ms);

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
    auto t0 = std::chrono::high_resolution_clock::now();
    std::vector<SceneTop> tops = g_scene.classify(env, bitmap, 5);
    auto t1 = std::chrono::high_resolution_clock::now();
    float scene_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();
    LOGS("scene time=%.1f ms", scene_ms);

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

// OCR 文字识别：传 Bitmap，返回识别出的文字（多行文本框之间用换行拼接）
extern "C" JNIEXPORT jstring JNICALL
Java_com_topaz_pureedgevlm_NativeBridge_ocrRecognize(JNIEnv* env, jclass, jobject bitmap) {
    if (!g_ocr_loaded) {
        LOGO("ocr: model not loaded");
        return env->NewStringUTF("（OCR 模型未加载）");
    }

    // 1) 锁定 Bitmap 像素（Android ARGB_8888 内存序为 RGBA），转成 OpenCV 的 RGB Mat
    int w = 0, h = 0;
    unsigned char* pixels = lockBitmap(env, bitmap, &w, &h);
    if (!pixels || w <= 0 || h <= 0) {
        if (pixels) unlockBitmap(env, bitmap);
        LOGO("ocr: lock bitmap failed w=%d h=%d", w, h);
        return env->NewStringUTF("");
    }
    cv::Mat rgba(h, w, CV_8UC4, pixels);
    cv::Mat rgb;
    cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
    unlockBitmap(env, bitmap);

    // 2) 检测 + 识别
    auto t0 = std::chrono::high_resolution_clock::now();
    std::vector<Object> objects;
    g_ocr.detect_and_recognize(rgb, objects);
    auto t1 = std::chrono::high_resolution_clock::now();
    float ocr_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();

    // 3) 按阅读顺序排序（上→下，同一行再左→右）。行容差取一个经验值。
    std::sort(objects.begin(), objects.end(), [](const Object& a, const Object& b) {
        float ay = a.rrect.center.y, by = b.rrect.center.y;
        float tol = std::max(a.rrect.size.height, b.rrect.size.height) * 0.6f;
        if (std::abs(ay - by) > tol) return ay < by;      // 不在同一行：按 y
        return a.rrect.center.x < b.rrect.center.x;         // 同一行：按 x
    });

    // 4) 把每个文本框的字符 id 转成文字，框之间换行拼接
    std::string result;
    for (size_t i = 0; i < objects.size(); i++) {
        std::string line;
        const std::vector<Character>& chars = objects[i].text;
        for (size_t j = 0; j < chars.size(); j++) {
            int id = chars[j].id;
            if (id < 0 || id >= character_dict_size) {
                if (!line.empty() && line.back() != ' ') line += " ";
                continue;
            }
            line += character_dict[id];
        }
        if (!line.empty()) {
            if (!result.empty()) result += "\n";
            result += line;
        }
    }

    LOGO("ocr time=%.1f ms, boxes=%zu", ocr_ms, objects.size());
    if (result.empty()) result = "（未识别到文字）";
    return env->NewStringUTF(result.c_str());
}

// ===== 阶段四：大模型 JNI 桥 =====

// 从绝对路径加载 GGUF 模型
extern "C" JNIEXPORT jboolean JNICALL
Java_com_topaz_pureedgevlm_NativeBridge_llmLoad(JNIEnv* env, jobject, jstring jpath) {
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    if (!p) return JNI_FALSE;
    bool ok = g_llm.load(std::string(p));
    env->ReleaseStringUTFChars(jpath, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// 生成：把中文指令喂给模型，每出一个片段通过 callback.onToken 回传（打字机效果）
extern "C" JNIEXPORT void JNICALL
Java_com_topaz_pureedgevlm_NativeBridge_llmGenerate(
        JNIEnv* env, jobject, jstring jprompt, jint maxTokens, jobject callback) {
    const char* p = env->GetStringUTFChars(jprompt, nullptr);
    if (!p) return;
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);

    // 从 callback 实例拿类（坑五：别写死类名，内部类带 $，FindClass 会找不到）
    jclass cbClass = env->GetObjectClass(callback);
    if (!cbClass) return;
    // 回传用 byte[]（而非 String）：NewStringUTF 只认 Java 的 Modified UTF-8，
    // 遇到标准 4 字节字符（如 emoji）会直接 abort 崩溃。改传原始 UTF-8 字节，
    // 由 Kotlin 侧用 String(bytes, UTF-8) 正确解码。llm_engine 已保证只发完整字符。
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)V");
    if (!onToken) { env->DeleteLocalRef(cbClass); return; }

    auto* envPtr = env;
    g_llm.generate(prompt, maxTokens, [envPtr, callback, onToken](const std::string& piece) {
        jbyteArray arr = envPtr->NewByteArray((jsize)piece.size());
        envPtr->SetByteArrayRegion(arr, 0, (jsize)piece.size(),
                                   reinterpret_cast<const jbyte*>(piece.data()));
        envPtr->CallVoidMethod(callback, onToken, arr);
        envPtr->DeleteLocalRef(arr);
    });
    env->DeleteLocalRef(cbClass);
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

// 返回各模型加载状态，形如 "yolo=ok;scene=ok;ocr=ok;llm=missing"，
// 供 Kotlin 启动时检查并提示用户（缺哪个模型就说哪个，不阻断其它功能）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_topaz_pureedgevlm_NativeBridge_modelStatus(JNIEnv* env, jclass) {
    std::string s;
    s += "yolo=";   s += g_loaded ? "ok" : "missing";
    s += ";scene="; s += g_scene_loaded ? "ok" : "missing";
    s += ";ocr=";   s += g_ocr_loaded ? "ok" : "missing";
    s += ";llm=";   s += g_llm.isLoaded() ? "ok" : "missing";
    return env->NewStringUTF(s.c_str());
}

// ===== 阶段五：Benchmark 测速 =====
// 对四个模型按 threads ∈ {1,2,4,8} 各跑 iterations 次（首跑为 warmup 不计时），
// 统计 avg/min/max 延迟并落盘 CSV。LLM 用固定短提示词 + 短 maxTokens 测速，
// 且每个线程设置只跑 min(iterations,3) 次（LLM 慢，避免按 10 次跑太久）。
// 另跑一次"完整视觉 pipeline"（yolo+scene+ocr，默认 4 线程）取平均。
//
// 注意：OCR（PP-OCRv5）内部用 OpenCV 全局线程 + OpenMP 并行，不响应 NCNN 的
// num_threads，所以 OCR 只按"默认/自动"测一次，不扫线程。
extern "C" JNIEXPORT jstring JNICALL
Java_com_topaz_pureedgevlm_NativeBridge_benchmarkRun(
        JNIEnv* env, jclass, jobject bitmap, jstring jcsvPath, jint iterations, jint resolution) {
    const char* cp = env->GetStringUTFChars(jcsvPath, nullptr);
    if (!cp) return env->NewStringUTF("csv path null");
    std::string csvPath(cp);
    env->ReleaseStringUTFChars(jcsvPath, cp);

    int iters = (iterations > 0) ? (int)iterations : 10;
    int llmIters = std::min(iters, 10);  // LLM 慢，但仍保证足够采样次数（默认 iters=10）
    std::vector<int> threadsList = {1, 2, 4, 8};

    // 先确认 bitmap 有效（benchmark 需要真实像素）
    {
        int w = 0, h = 0;
        unsigned char* px = lockBitmap(env, bitmap, &w, &h);
        if (!px || w <= 0 || h <= 0) {
            if (px) unlockBitmap(env, bitmap);
            return env->NewStringUTF("benchmark: invalid bitmap");
        }
        unlockBitmap(env, bitmap);
    }

    // 追加模式：Kotlin 端会为每种分辨率各调一次（调用前先删旧文件），
    // 这样多次调用拼成一个完整 CSV（含 resolution 列），互不覆盖。
    std::ofstream f(csvPath, std::ios::app);
    if (!f.is_open()) {
        return env->NewStringUTF(("benchmark: cannot open csv " + csvPath).c_str());
    }
    if (f.tellp() == 0) {  // 仅文件为空时写表头（第一次调用）
        f << "model,threads,resolution,runs,avg_ms,min_ms,max_ms,fps\n";
    }

    auto timeOne = [&](const std::function<void()>& fn) -> double {
        auto t0 = std::chrono::high_resolution_clock::now();
        fn();
        auto t1 = std::chrono::high_resolution_clock::now();
        return std::chrono::duration<double, std::milli>(t1 - t0).count();
    };
    auto writeRow = [&](const char* model, int t, int res, int runs, double avg, double mn, double mx) {
        f << model << "," << t << "," << res << "," << runs << ","
          << avg << "," << mn << "," << mx << "," << (1000.0 / avg) << "\n";
    };

    // 固定短提示词（中文），让 LLM 输出可控、测速稳定
    const std::string llmPrompt =
        "<|im_start|>system\n你是一个运行在手机上的本地智能助手，请用简洁的简体中文回答。<|im_end|>\n"
        "<|im_start|>user\n用一句话介绍你自己。<|im_end|>\n"
        "<|im_start|>assistant\n";

    // ---- YOLO ----
    for (int t : threadsList) {
        g_detector.setNumThreads(t);
        g_detector.detect(env, bitmap, 0.2f, 0.45f);  // warmup
        double sum = 0, mn = 1e30, mx = 0;
        for (int i = 0; i < iters; i++) {
            double ms = timeOne([&]() { g_detector.detect(env, bitmap, 0.2f, 0.45f); });
            sum += ms; mn = std::min(mn, ms); mx = std::max(mx, ms);
        }
        double avg = sum / iters;
        writeRow("yolo", t, resolution, iters, avg, mn, mx);
        LOGI("bench yolo t=%d avg=%.1f min=%.1f max=%.1f", t, avg, mn, mx);
    }

    // ---- Scene ----
    for (int t : threadsList) {
        g_scene.setNumThreads(t);
        g_scene.classify(env, bitmap, 5);  // warmup
        double sum = 0, mn = 1e30, mx = 0;
        for (int i = 0; i < iters; i++) {
            double ms = timeOne([&]() { g_scene.classify(env, bitmap, 5); });
            sum += ms; mn = std::min(mn, ms); mx = std::max(mx, ms);
        }
        double avg = sum / iters;
        writeRow("scene", t, resolution, iters, avg, mn, mx);
        LOGS("bench scene t=%d avg=%.1f", t, avg);
    }

    // ---- OCR（默认/自动线程，只测一次）----
    {
        // warmup：先跑一次（OpenCV/OpenMP 首次有初始化开销）
        {
            int w = 0, h = 0;
            unsigned char* pixels = lockBitmap(env, bitmap, &w, &h);
            if (pixels) {
                cv::Mat rgba(h, w, CV_8UC4, pixels);
                cv::Mat rgb;
                cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
                unlockBitmap(env, bitmap);
                std::vector<Object> objs;
                g_ocr.detect_and_recognize(rgb, objs);
            }
        }
        double sum = 0, mn = 1e30, mx = 0;
        for (int i = 0; i < iters; i++) {
            double ms = timeOne([&]() {
                int w = 0, h = 0;
                unsigned char* pixels = lockBitmap(env, bitmap, &w, &h);
                if (!pixels) return;
                cv::Mat rgba(h, w, CV_8UC4, pixels);
                cv::Mat rgb;
                cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
                unlockBitmap(env, bitmap);
                std::vector<Object> objs;
                g_ocr.detect_and_recognize(rgb, objs);
            });
            sum += ms; mn = std::min(mn, ms); mx = std::max(mx, ms);
        }
        double avg = sum / iters;
        writeRow("ocr", -1, resolution, iters, avg, mn, mx);  // threads=-1 表示"自动"
        LOGO("bench ocr(auto) avg=%.1f", avg);
    }

    // ---- LLM（短输出，扫线程）----
    std::string summary;
    if (g_llm.isLoaded()) {
        const int llmMax = 48;  // 短输出，控制单次测速时长
        g_llm.setNumThreads(4);
        g_llm.generate(llmPrompt, llmMax, [](const std::string&){});  // warmup
        for (int t : threadsList) {
            g_llm.setNumThreads(t);
            double sum = 0, mn = 1e30, mx = 0;
            for (int i = 0; i < llmIters; i++) {
                double ms = timeOne([&]() {
                    g_llm.generate(llmPrompt, llmMax, [](const std::string&){});
                });
                sum += ms; mn = std::min(mn, ms); mx = std::max(mx, ms);
            }
            double avg = sum / llmIters;
            writeRow("llm", t, resolution, llmIters, avg, mn, mx);
            LOGI("bench llm t=%d avg=%.1f", t, avg);
        }
    } else {
        LOGI("bench: llm not loaded, skip");
        summary = " (llm skipped: not loaded)";
    }

    // ---- 完整视觉 pipeline（默认 4 线程，跑 10 次取平均）----
    {
        int w = 0, h = 0;
        unsigned char* px = lockBitmap(env, bitmap, &w, &h);
        if (px && w > 0 && h > 0) {
            unlockBitmap(env, bitmap);
            double sum = 0, mn = 1e30, mx = 0;
            int pruns = (iters >= 10) ? 10 : iters;
            g_detector.setNumThreads(4); g_scene.setNumThreads(4);  // OCR 用默认/自动线程
            for (int i = 0; i < pruns; i++) {
                double ms = timeOne([&]() {
                    g_detector.detect(env, bitmap, 0.2f, 0.45f);
                    g_scene.classify(env, bitmap, 5);
                    int ww = 0, hh = 0;
                    unsigned char* pp = lockBitmap(env, bitmap, &ww, &hh);
                    if (!pp) return;
                    cv::Mat rgba(hh, ww, CV_8UC4, pp);
                    cv::Mat rgb; cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
                    unlockBitmap(env, bitmap);
                    std::vector<Object> objs;
                    g_ocr.detect_and_recognize(rgb, objs);
                });
                sum += ms; mn = std::min(mn, ms); mx = std::max(mx, ms);
            }
            double avg = sum / pruns;
            writeRow("pipeline", 4, resolution, pruns, avg, mn, mx);
            LOGI("bench pipeline avg=%.1f", avg);
        }
    }

    f.close();
    std::string msg = "benchmark done -> " + csvPath + summary;
    return env->NewStringUTF(msg.c_str());
}
