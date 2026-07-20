// opencv_omp_shim.cpp
//
// 修复：opencv-mobile（v35 / opencv 4.13.0）以及本项目用 NDK r28 编译的部分代码
// （例如 ppocrv5.cpp 里的 #pragma omp parallel for）会调用 OpenMP 的“调度收尾”
// 函数 __kmpc_dispatch_deinit，但 NDK r28 自带的 libomp 只有老接口
// __kmpc_dispatch_fini_*，并没有 deinit。链接期会报 undefined symbol。
//
// 为什么不能“转发给 fini_4u”：
//   init_4u / next_4u（老接口）已经由 NDK libomp 正确完成了“把循环分给多个线程、
//   每个线程跑自己那一块”的实际工作；deinit 只是 runtime 的收尾清理。
//   但新接口的 deinit 期望读取的内部数据结构（th_dispatch）布局，和老接口
//   init_4u 写出来的不一样——直接把 deinit 转调 fini_4u，等于用新布局去读老布局
//   的数据，一访问就空指针崩。实机已验证：SIGSEGV / null pointer / fault addr 0x8c，
//   崩溃栈稳定在 libomp.so 的 fini 函数里。
//
// 正确做法：提供一个“空实现”的 deinit（什么都不做，直接返回）。
//   - 不碰任何内存，绝不崩；
//   - 循环的实际工作已被 init/next 跑完，空 deinit 不影响结果正确性；
//   - 唯一代价是每次并行循环结尾有一点点 runtime 缓冲没释放（轻微内存泄漏），
//     OCR 调用不频繁，可忽略；
//   - ncnn 走的是 fork_call，根本不调用 deinit，完全不受影响。

extern "C" void __kmpc_dispatch_deinit(void* loc, int gtid) {
    (void)loc;
    (void)gtid;
    // 故意空实现：NDK r28 的 OpenMP 运行时缺这个新接口，但老接口 init/next 已
    // 正确完成并行分发，此处无需做任何事。
}
