#include "yolo_detector.h"
#include "image_util.h"
#include <cmath>
#include <algorithm>
#include <cstring>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "YOLO", __VA_ARGS__)

int YoloDetector::loadFromMemory(const unsigned char* param_mem, size_t param_len,
                                  const unsigned char* bin_mem, size_t bin_len) {
    net.opt.use_vulkan_compute = false; // 纯 CPU，关掉显卡
    net.opt.num_threads = 4;            // 视觉模型用 4 线程

    // NCNN 的 load_param_mem 要求字符串以 \0 结尾，所以我们自己拷一份并在末尾补 \0
    param_buf.resize(param_len + 1);
    std::memcpy(param_buf.data(), param_mem, param_len);
    param_buf[param_len] = '\0';

    // NCNN 的 load_model 从内存加载权重时只是引用，不会复制。
    // 如果加载完就把内存释放，后面推理会读到垃圾数据。所以拷到成员变量里长期保存。
    bin_buf.assign(bin_mem, bin_mem + bin_len);

    bool ok_param = (net.load_param_mem(param_buf.data()) == 0);
    if (!ok_param) return 1;
    // 注意：load_model(const unsigned char*) 返回 size_t（消耗的字节数），
    // 0 表示失败、非 0 表示成功（不是错误码，与 load_param_mem 的 int 不同）
    size_t model_consumed = net.load_model(bin_buf.data());
    if (model_consumed == 0) return 2;
    return 0;
}

static inline float iou(const YoloBox& a, const YoloBox& b) {
    float xx1 = std::max(a.x1, b.x1);
    float yy1 = std::max(a.y1, b.y1);
    float xx2 = std::min(a.x2, b.x2);
    float yy2 = std::min(a.y2, b.y2);
    float w = std::max(0.0f, xx2 - xx1);
    float h = std::max(0.0f, yy2 - yy1);
    float inter = w * h;
    float area_a = std::max(0.0f, a.x2 - a.x1) * std::max(0.0f, a.y2 - a.y1);
    float area_b = std::max(0.0f, b.x2 - b.x1) * std::max(0.0f, b.y2 - b.y1);
    float union_ = area_a + area_b - inter;
    if (union_ <= 0.0f) return 0.0f;
    return inter / union_;
}

std::vector<YoloBox> YoloDetector::detect(JNIEnv* env, jobject bitmap,
                                          float conf_thresh, float nms_thresh,
                                          float* max_score_out, int* max_label_out) {
    int w = 0, h = 0;
    unsigned char* pixels = lockBitmap(env, bitmap, &w, &h);

    const int target = 640;
    float scale = std::min((float)target / (float)w, (float)target / (float)h);
    int new_w = (int)(w * scale);
    int new_h = (int)(h * scale);

    // 缩放到 new_w x new_h，再贴到 target x target 的灰底左上角（letterbox）
    ncnn::Mat resized = ncnn::Mat::from_pixels_resize(
        pixels, ncnn::Mat::PIXEL_RGBA2RGB, w, h, new_w, new_h);
    unlockBitmap(env, bitmap);

    ncnn::Mat in(target, target, 3);
    in.fill(114.0f);
    for (int y = 0; y < new_h; y++) {
        const float* s = resized.row(y);
        float* d = in.row(y);
        for (int x = 0; x < new_w; x++) {
            d[x * 3 + 0] = (float)s[x * 3 + 0];
            d[x * 3 + 1] = (float)s[x * 3 + 1];
            d[x * 3 + 2] = (float)s[x * 3 + 2];
        }
    }
    // 归一化到 0~1
    const float mean[3] = {0.0f, 0.0f, 0.0f};
    const float norm[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    in.substract_mean_normalize(mean, norm);

    // 调试：统计归一化后输入图的中心像素值，确认数值范围对不对
    {
        int cx = target / 2, cy = target / 2;
        const float* p = in.row(cy) + cx * 3;
        LOGI("input norm center px rgb=%.3f,%.3f,%.3f", p[0], p[1], p[2]);
    }

    ncnn::Extractor ex = net.create_extractor();
    auto in_names = net.input_names();
    auto out_names = net.output_names();
    ex.input(in_names[0], in);
    ncnn::Mat out;
    ex.extract(out_names[0], out);

    // 解码：YOLOv11 输出为 [8400, 84]（84 = 4 框坐标 + 80 类分数）
    // 不同 NCNN 导出对 (w,h,c) 的排布不同（可能是 w=84,h=8400,c=1，
    // 也可能是 w=1,h=8400,c=84，或转置），这里自动判断，兼容所有布局
    const int num_class = 80;
    int W = out.w, H = out.h, C = out.c;
    int propDim = (W == 8400) ? 0 : (H == 8400) ? 1 : 2; // 8400（提案数）所在的维度
    int featDim = (W == 84)   ? 0 : (H == 84)   ? 1 : 2; // 84（特征数）所在的维度
    int num = (propDim == 0) ? W : (propDim == 1) ? H : C; // 提案数，应为 8400
    int oneDim = 3 - propDim - featDim; // 值为 1 的维度
    LOGI("out w=%d h=%d c=%d num=%d conf=%.2f", W, H, C, num, conf_thresh);

    std::vector<YoloBox> boxes;
    float g_max = 0.0f; int g_label = -1;
    float feat[84];
    for (int i = 0; i < num; i++) {
        // 把第 i 个提案的 84 个值收集到 feat[]（兼容任意排布）
        for (int c = 0; c < 84; c++) {
            int xyz[3] = {0, 0, 0};
            xyz[propDim] = i;
            xyz[featDim] = c;
            xyz[oneDim] = 0;
            feat[c] = out.channel(xyz[2]).row(xyz[1])[xyz[0]];
        }
        float cx = feat[0], cy = feat[1], bw = feat[2], bh = feat[3];
        float best = 0.0f;
        int best_label = 0;
        for (int c = 0; c < num_class; c++) {
            if (feat[4 + c] > best) { best = feat[4 + c]; best_label = c; }
        }
        if (best > g_max) { g_max = best; g_label = best_label; }
        if (best < conf_thresh) continue;
        // 由 640 空间映射回原图（letterbox 左上对齐，只除 scale）
        float ocx = cx / scale;
        float ocy = cy / scale;
        float obw = bw / scale;
        float obh = bh / scale;
        YoloBox b;
        b.x1 = std::max(0.0f, ocx - obw / 2.0f);
        b.y1 = std::max(0.0f, ocy - obh / 2.0f);
        b.x2 = std::min((float)w, ocx + obw / 2.0f);
        b.y2 = std::min((float)h, ocy + obh / 2.0f);
        b.label = best_label;
        b.score = best;
        boxes.push_back(b);
    }
    LOGI("max score=%.3f label=%d preNMS=%zu", g_max, g_label, boxes.size());

    // NMS：按类别，分数高的压制重叠的同类框
    std::sort(boxes.begin(), boxes.end(),
              [](const YoloBox& a, const YoloBox& b) { return a.score > b.score; });
    std::vector<bool> suppressed(boxes.size(), false);
    std::vector<YoloBox> result;
    for (size_t i = 0; i < boxes.size(); i++) {
        if (suppressed[i]) continue;
        result.push_back(boxes[i]);
        for (size_t j = i + 1; j < boxes.size(); j++) {
            if (suppressed[j]) continue;
            if (boxes[j].label == boxes[i].label && iou(boxes[i], boxes[j]) > nms_thresh)
                suppressed[j] = true;
        }
    }
    LOGI("final boxes=%zu", result.size());
    if (max_score_out) *max_score_out = g_max;
    if (max_label_out) *max_label_out = g_label;
    return result;
}
