#pragma once
#include <vector>
#include <ncnn/net.h>

// 一个场景识别结果：类别编号（对应 categories_places365.txt 的行号）+ 概率
struct SceneTop {
    int index;
    float prob;
};

class SceneClassifier {
public:
    // 从内存加载 NCNN 模型；返回 0=成功，1=param 失败，2=model 失败
    int loadFromMemory(const unsigned char* param_mem, size_t param_len,
                       const unsigned char* bin_mem, size_t bin_len);
    // 对一张 Bitmap 做场景识别，返回概率最高的前 k 个（已做 softmax）
    std::vector<SceneTop> classify(JNIEnv* env, jobject bitmap, int topk = 5);
    // 设置推理线程数（Benchmark 用），范围 1~8
    void setNumThreads(int n) { if (n > 0) net.opt.num_threads = n; }
private:
    ncnn::Net net;
    // NCNN 从内存加载模型时只是引用，不复制；所以自己把数据留着
    std::vector<char> param_buf;
    std::vector<unsigned char> bin_buf;
};
