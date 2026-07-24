#include "scene_classifier.h"
#include "image_util.h"
#include <cmath>
#include <algorithm>
#include <cstring>
#include <android/log.h>

#define LOGS(...) __android_log_print(ANDROID_LOG_INFO, "SCENE", __VA_ARGS__)

int SceneClassifier::loadFromMemory(const unsigned char* param_mem, size_t param_len,
                                   const unsigned char* bin_mem, size_t bin_len) {
    net.opt.use_vulkan_compute = false; // 纯 CPU
    net.opt.num_threads = 1;  // 并行模式：场景识别最轻，给 1 线程，算力让给 YOLO/OCR

    // load_param_mem 要求以 \0 结尾，自己拷一份补 \0
    param_buf.resize(param_len + 1);
    std::memcpy(param_buf.data(), param_mem, param_len);
    param_buf[param_len] = '\0';

    // load_model 从内存加载只是引用，必须自己长期保存
    bin_buf.assign(bin_mem, bin_mem + bin_len);

    bool ok_param = (net.load_param_mem(param_buf.data()) == 0);
    if (!ok_param) return 1;
    // load_model(...) 返回消耗的字节数，0=失败，非0=成功
    size_t model_consumed = net.load_model(bin_buf.data());
    if (model_consumed == 0) return 2;
    return 0;
}

std::vector<SceneTop> SceneClassifier::classify(JNIEnv* env, jobject bitmap, int topk) {
    int w = 0, h = 0;
    unsigned char* pixels = lockBitmap(env, bitmap, &w, &h);

    // ResNet50 要求 224x224 输入，直接缩放到 224（场景识别不补灰边）
    const int target = 224;
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
        pixels, ncnn::Mat::PIXEL_RGBA2RGB, w, h, target, target);
    unlockBitmap(env, bitmap);

    // ⚠️ 关键修正：ncnn 的 substract_mean_normalize 做的是  out = (x - mean) * norm（乘法，不是除法）
    // 而 ImageNet 标准是  (pixel/255 - mean) / std
    // 令 mean_vals = mean*255，norm_vals = 1/(255*std)，两式就等价了
    const float mean[3] = {123.675f, 116.28f, 103.53f};
    const float norm[3] = {0.017129f, 0.017507f, 0.017425f};
    in.substract_mean_normalize(mean, norm);

    // 调试：看输入图归一化后是不是"活"的（方差接近 0 说明图没真正送进来）
    {
        int n = in.w * in.h * in.c;
        float s = 0.0f, s2 = 0.0f;
        for (int i = 0; i < n; i++) { float v = (float)in[i]; s += v; s2 += v * v; }
        float mean_v = s / n, var = s2 / n - mean_v * mean_v;
        LOGS("scene input: n=%d mean=%.3f std=%.3f", n, mean_v, std::sqrt(var));
    }

    ncnn::Extractor ex = net.create_extractor();
    auto in_names = net.input_names();
    auto out_names = net.output_names();
    ncnn::Mat out;
    ex.input(in_names[0], in);
    ex.extract(out_names[0], out);
    LOGS("scene io: in=%s out=%s outShape w=%d h=%d c=%d",
          in_names[0], out_names[0], out.w, out.h, out.c);

    int dim = out.w * out.h * out.c; // 应为 365（类别数）
    LOGS("scene out dim=%d", dim);

    // softmax：把 365 个分数变成 0~1 的概率（用 float 精度算，避免 fp16 误差）
    std::vector<float> prob(dim);
    float maxv = -1e30f;
    for (int i = 0; i < dim; i++) {
        float v = (float)out[i];
        if (v > maxv) maxv = v;
        prob[i] = v;
    }
    float sum = 0.0f;
    for (int i = 0; i < dim; i++) {
        prob[i] = std::exp(prob[i] - maxv);
        sum += prob[i];
    }
    if (sum > 0.0f) {
        for (int i = 0; i < dim; i++) prob[i] /= sum;
    }
    // 调试：打印真实 Top5 概率，确认是不是"概率全挤在一个类"（退化）
    {
        std::vector<int> idx(dim);
        for (int i = 0; i < dim; i++) idx[i] = i;
        std::partial_sort(idx.begin(), idx.begin() + 5, idx.end(),
                          [&](int a, int b) { return prob[a] > prob[b]; });
        LOGS("scene top5: %d=%.4f %d=%.4f %d=%.4f %d=%.4f %d=%.4f",
              idx[0], prob[idx[0]], idx[1], prob[idx[1]], idx[2], prob[idx[2]],
              idx[3], prob[idx[3]], idx[4], prob[idx[4]]);
    }

    // 取概率最大的前 topk 个
    std::vector<int> idx(dim);
    for (int i = 0; i < dim; i++) idx[i] = i;
    std::partial_sort(idx.begin(), idx.begin() + std::min(topk, dim), idx.end(),
                      [&](int a, int b) { return prob[a] > prob[b]; });

    std::vector<SceneTop> result;
    int k = std::min(topk, dim);
    for (int i = 0; i < k; i++) {
        SceneTop t;
        t.index = idx[i];
        t.prob = prob[idx[i]];
        result.push_back(t);
    }
    LOGS("scene top1 idx=%d prob=%.4f", result[0].index, result[0].prob);
    return result;
}
