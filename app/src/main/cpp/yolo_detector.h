#pragma once
#include <vector>
#include <ncnn/net.h>

// 一个检测结果：原图像素坐标的框 + 类别编号 + 分数
struct YoloBox {
    float x1, y1, x2, y2; // 左上、右下（原图像素坐标）
    int label;
    float score;
};

class YoloDetector {
public:
    // 从内存加载 NCNN 模型（param/bin 都在 assets 里读完传进来）
    // 从内存加载 NCNN 模型；返回 0=成功，1=param 失败，2=model 失败
    int loadFromMemory(const unsigned char* param_mem, size_t param_len,
                       const unsigned char* bin_mem, size_t bin_len);
    // 对一张 Bitmap 做检测，返回所有通过阈值的框（已做 NMS）
    std::vector<YoloBox> detect(JNIEnv* env, jobject bitmap,
                                float conf_thresh, float nms_thresh,
                                float* max_score_out = nullptr,
                                int* max_label_out = nullptr);
    // 设置推理线程数（Benchmark 用），范围 1~8
    void setNumThreads(int n) { if (n > 0) net.opt.num_threads = n; }
private:
    ncnn::Net net;
    // NCNN 从内存加载模型时，权重只是引用这块内存，不复制。
    // 所以必须自己把模型数据留着，不能加载完就释放。
    std::vector<char> param_buf;           // param 文本，保证以 \0 结尾
    std::vector<unsigned char> bin_buf;    // bin 权重二进制
};
