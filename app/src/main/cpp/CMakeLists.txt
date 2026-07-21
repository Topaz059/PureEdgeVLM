cmake_minimum_required(VERSION 3.22.1)
project("pureedgevlm")

# ===== 强制开优化（关键！否则大模型奇慢）=====
# Android Studio 的 Debug 变体默认用 -O0（无优化），llama.cpp/ggml 靠大量矩阵
# 运算，-O0 会慢 20~50 倍（实测 prefill 42s、0.8 tok/s）。这里把 Debug 也强制
# 拉到 -O3 + -DNDEBUG，让大模型全速跑。只影响 native C/C++ 代码，不影响 Kotlin
# 调试；代价是 native 断点调试会不准，但本工程 native 已稳定、无需断点。
set(CMAKE_C_FLAGS_DEBUG   "${CMAKE_C_FLAGS_DEBUG} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -DNDEBUG")

# ===== llama.cpp（大模型运行库，阶段四）=====
# 作为子目录编进本工程（只编核心库 llama；tests/tools/examples 默认不编）。
# 关键开关：
#  - BUILD_SHARED_LIBS=OFF ：把 llama/ggml 编成【静态库】，直接链进我们的 pureedgevlm.so，
#    省去「多个 .so 按依赖顺序加载」的麻烦（安卓 NDK 下容易 dlopen failed）。
#  - GGML_OPENMP=OFF ：NDK 不支持把 OpenMP 当作 CMake 依赖来编，不关会编挂（只影响 ggml，不干扰 NCNN 的 OpenMP）。
#  - GGML_LLAMAFILE=OFF ：安卓不支持 llamafile，官方安卓文档要求关掉。
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/llama.cpp)

# NCNN 头文件和库的位置
set(NCNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn)

# OpenCV（opencv-mobile，仅 core + imgproc，用于 OCR 的检测后处理）
# 用户需把 opencv-mobile-4.13.0-android 解压到 third_party/ 下。
set(OpenCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv-mobile-4.13.0-android/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native_bridge.cpp
        llm_engine.cpp
        image_util.cpp
        yolo_detector.cpp
        scene_classifier.cpp
        ocr/ppocrv5.cpp
        opencv_omp_shim.cpp)

# 引入 NCNN 与 llama.cpp 的头文件
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${NCNN_DIR}/include
        third_party/llama.cpp/include)

# OpenMP：NCNN 静态库靠它做多线程（NDK 自带）。优先用 find_package，
# 找不到就退回给编译器加 -fopenmp（NDK 会自动链接 libomp）。
find_package(OpenMP)
if(OpenMP_CXX_FOUND)
    target_link_libraries(${CMAKE_PROJECT_NAME} OpenMP::OpenMP_CXX)
else()
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")
endif()

# 链接：llama（核心大模型库，静态链入）+ NCNN 静态库 + OpenCV(core+imgproc)
#      + Android Bitmap 库(jnigraphics) + AssetManager(android) + 日志(log)
# 注意：读 Bitmap 像素必须链 jnigraphics，否则链接报错 undefined reference to AndroidBitmap_*
target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        ${NCNN_DIR}/lib/libncnn.a
        ${OpenCV_LIBS}
        jnigraphics
        android
        log)
