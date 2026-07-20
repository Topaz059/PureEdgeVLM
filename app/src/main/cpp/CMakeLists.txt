cmake_minimum_required(VERSION 3.22.1)
project("pureedgevlm")

# NCNN 头文件和库的位置
set(NCNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native_bridge.cpp
        image_util.cpp
        yolo_detector.cpp
        scene_classifier.cpp)

# 引入 NCNN 头文件
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${NCNN_DIR}/include)

# OpenMP：NCNN 静态库靠它做多线程（NDK 自带）。优先用 find_package，
# 找不到就退回给编译器加 -fopenmp（NDK 会自动链接 libomp）。
find_package(OpenMP)
if(OpenMP_CXX_FOUND)
    target_link_libraries(${CMAKE_PROJECT_NAME} OpenMP::OpenMP_CXX)
else()
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")
endif()

# 链接 NCNN 静态库 + Android Bitmap 库(jnigraphics) + AssetManager(android) + 日志(log)
# 注意：读 Bitmap 像素必须链 jnigraphics，否则链接报错 undefined reference to AndroidBitmap_*
target_link_libraries(${CMAKE_PROJECT_NAME}
        ${NCNN_DIR}/lib/libncnn.a
        jnigraphics
        android
        log)
