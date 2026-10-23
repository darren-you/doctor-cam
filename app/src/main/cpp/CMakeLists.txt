cmake_minimum_required(VERSION 4.1.2)
project(UvcVision LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(PADDLE_LITE_SDK_DIR "" CACHE PATH "已校验的 Paddle Lite SDK 构建目录")

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "UVC Vision 只构建 arm64-v8a")
endif()
if(NOT EXISTS "${PADDLE_LITE_SDK_DIR}/include/paddle_api.h" OR
   NOT EXISTS "${PADDLE_LITE_SDK_DIR}/lib/arm64-v8a/libpaddle_api_light_bundled.a")
    message(FATAL_ERROR "请通过 Gradle preparePaddleLiteSdk 准备官方 SDK")
endif()

find_package(OpenCV REQUIRED CONFIG)

add_library(paddle_lite STATIC IMPORTED)
set_target_properties(paddle_lite PROPERTIES
    IMPORTED_LOCATION "${PADDLE_LITE_SDK_DIR}/lib/arm64-v8a/libpaddle_api_light_bundled.a"
    INTERFACE_INCLUDE_DIRECTORIES "${PADDLE_LITE_SDK_DIR}/include"
)

add_library(PaddleDetector SHARED PaddleDetector.cc Pipeline.cc Utils.cc)
target_compile_options(PaddleDetector PRIVATE
    -ffast-math -Ofast -Os -DNDEBUG -fno-exceptions -fomit-frame-pointer
    -fno-asynchronous-unwind-tables -fno-unwind-tables -fvisibility=hidden
    -fvisibility-inlines-hidden -fdata-sections -ffunction-sections
)
# 同版官方 SDK 的静态集成使用 NDK 自有 OpenMP，不另打包 libomp.so。
target_link_options(PaddleDetector PRIVATE
    -Wl,--gc-sections -Wl,-z,nocopyreloc -fopenmp -static-openmp
)
target_link_libraries(PaddleDetector PRIVATE
    "$<LINK_LIBRARY:WHOLE_ARCHIVE,paddle_lite>" OpenCV::opencv_java5 jnigraphics log
)
