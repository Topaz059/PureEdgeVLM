#!/bin/bash
# build_ncnn_android.sh
# 一键编译 NCNN 安卓静态库（阶段二第 1 步）
set -e

# 把 SDK 里的 cmake/ninja 临时加进 PATH（只对脚本运行期间有效）
export PATH="/c/Users/Blue/AppData/Local/Android/Sdk/cmake/3.22.1/bin:$PATH"

ROOT=/c/Users/Blue/Desktop/work/localai
cd "$ROOT"

# 加大 http 缓冲，减少大仓库克隆时中断
git config --global http.postBuffer 1048576000

echo "===== 1/5 克隆 NCNN 源码（浅克隆，体积小不易断） ====="
if [ -f ncnn/CMakeLists.txt ]; then
  echo "ncnn 已完整存在，跳过克隆"
else
  echo "删除可能残留的半截 ncnn 文件夹，重新克隆..."
  rm -rf ncnn
  git clone --depth 1 https://github.com/Tencent/ncnn.git
fi

cd ncnn

echo "===== 2/5 配置编译参数（arm64，关掉显卡加速） ====="
mkdir -p build-android-arm64-v8a
cd build-android-arm64-v8a
cmake -G Ninja -DCMAKE_TOOLCHAIN_FILE="/c/Users/Blue/AppData/Local/Android/Sdk/ndk/26.3.11579264/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DNCNN_VULKAN=OFF -DNCNN_BUILD_EXAMPLES=OFF -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_TOOLS=OFF ..

echo "===== 3/5 开始编译（约 20~40 分钟，挂着别关） ====="
cmake --build . -j$(nproc)

echo "===== 4/5 安装到本地目录 ====="
cmake --install . --prefix /c/Users/Blue/Desktop/work/localai/ncnn/ncnn-android-install

echo "===== 5/5 验证 libncnn.a ====="
ls -la /c/Users/Blue/Desktop/work/localai/ncnn/ncnn-android-install/lib/libncnn.a
echo "全部完成！如果上面能看到 libncnn.a 这一行，就说明第一步成功了。"
