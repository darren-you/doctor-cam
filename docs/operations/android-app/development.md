# Android 开发与验证

## 工程与工具链

仓根是唯一 Gradle 工程根，应用模块为 `app`，OpenCV Java 模块为 `:opencv`，路径固定为 `app/OpenCV/sdk/`。独立开发不读取相邻工作区源码。

| 事实 | 当前源码 |
| --- | --- |
| Wrapper / AGP | Gradle 8.10.2 / Android Gradle Plugin 8.8.0 |
| Java | Java 11 源码；运行 Gradle 使用 JDK 17 及以上，本机使用 Zulu 21 |
| Android SDK | compile / target 35，min 21 |
| Native | NDK 17.2.4988734、CMake 3.22.1、C++11、arm64-v8a |
| OpenCV | 4.2.0，仓内 Java 绑定与同版本 Native SDK |
| 安装身份 | `cn.darrenyou.doctorcam` |
| 本地版本 | 历史 versionName 1.0 / versionCode 1；尚未接入统一正式版本合同 |

事实源为 [Wrapper](../../../gradle/wrapper/gradle-wrapper.properties)、[版本目录](../../../gradle/libs.versions.toml)、[应用 Gradle](../../../app/build.gradle)、[OpenCV 模块](../../../app/OpenCV/sdk/build.gradle)与 [CMake](../../../app/src/main/cpp/CMakeLists.txt)。[AGP 8.8 默认 NDK 为 27](https://developer.android.com/build/releases/agp-8-8-0-release-notes)，但当前 Paddle 预编译库的动态符号布局不能被该版 lld 接受，必须按真实 Native 依赖固定原 NDK 17.2.4988734；不通过放宽链接器校验、重写 ELF 或替换库绕过。CMake 固定为 3.22.1，移除空 `ANDROID_TOOLCHAIN` 与覆盖 minSdk 的 `ANDROID_PLATFORM`。

## 准备与构建

```bash
git clone https://github.com/darren-you/doctor-cam.git
cd doctor-cam
```

设置本机 `JAVA_HOME` 与 `ANDROID_HOME`，安装明确的 SDK 组件。NDK 17 的 macOS 工具为 Intel 程序，Apple Silicon 宿主还需安装 Rosetta 2；这属于宿主构建依赖，Android APK 仍为 ARM64。

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
  "ndk;17.2.4988734" "cmake;3.22.1"
./gradlew --version
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false help
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false :app:assembleDebug
```

本地 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，仅含 ARM64 Native 库，使用 Android Debug 签名。首次依赖解析需要 Google Maven 与 Maven Central；`--offline` 只在依赖已完整缓存时使用。构建命令禁用 SDK 自动下载，以便缺少组件时明确失败。

`BuildConfig` 由 AGP 按 variant 生成，不再维护手写 `DEBUG=true` 类。OpenCV 模块直接打包仓内 Java 绑定与预编译 Native 库，不调用缺失的 `libcxx_helper` 工程。应用 CMake 从同一个 OpenCV SDK 与 `app/PaddleLite/cxx/` 链接 Paddle JNI；模型和预编译库仍采用仓内原版本。

## 安装与启动

先运行 `adb devices -l` 核对目标，再显式选择序列号，避免安装到其他设备。Android Studio 可从 AVD Manager 启动 ARM64 模拟器；x86 模拟器不能加载本工程的 ARM64 Native 库。

```bash
adb devices -l
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -W -n cn.darrenyou.doctorcam/.MainActivity
adb -s <serial> shell pidof cn.darrenyou.doctorcam
adb -s <serial> logcat -d -b crash
```

允许相机权限后，可接入 USB UVC 摄像头。点击设备进入预览，长按进入 Paddle 检测；USB 权限由对应目标页的 USBMonitor 申请并消费，设备通过实际 `deviceId` 绑定。未连接 UVC 摄像头时显示“暂无设备”，模拟器内置摄像头不等同于 USB UVC 设备。

## 回归与验收范围

```bash
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false :app:testDebugUnitTest
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  -Pandroid.builder.sdkDownload=false :app:connectedDebugAndroidTest
```

Android instrumentation 使用仓内真实 Paddle 模型、标签与 Native 库，覆盖 OpenCV / UVC 加载、位图推理、释放后调用、重复释放与重新初始化。生成位图只用于检查执行和生命周期，不提供检测精度或医学效果证据。模板 JVM 算术测试不能证明业务正确。

模拟器验证应核对前台 Activity、进程存活、OpenCV 初始化、Native 回归与崩溃日志。真实 UVC 仍须在连接摄像头后验证权限拒绝/授予、预览、抓拍、Paddle 帧推理、切页、退后台与拔插；没有摄像头时不能勾选这些验收项。当前 Native 预编译依赖的运行验证针对 ARM64、4 KiB 页大小环境，不代表所有设备或页大小组合都已验证。

## OpenCV Java 绑定来源

原工程仅保存 Native SDK，缺少 Java 绑定；补齐内容来自 [OpenCV 4.2.0 官方 Android SDK](https://github.com/opencv/opencv/releases/download/4.2.0/opencv-4.2.0-android-sdk.zip)，仅导入 `sdk/java/src`、`sdk/java/res` 与 Java Manifest。AGP 8 的 namespace 在 Gradle 声明，Manifest 不保留旧 package 字段；其余 Java 绑定保留上游实现与版权。

- 官方 ZIP SHA-256：`55c85d2d23b244a233c65392770d51b51a1afd8c15cdef72207f774fceba6219`。
- 官方和仓内 ARM64 `libopencv_java4.so` 相同 SHA-256：`8c2a880f9cd1f5d98208ab0a6d10234389b8e49dae1cfde36ba33b8a888a9461`。
- 许可证：[OpenCV LICENSE](../../../app/OpenCV/LICENSE)。

Java 与 Native 必须保持同版本；不要单独导入另一版 Java SDK 或复制第二个 SDK 目录。

## 标准发布边界

当前没有 Core 子模块、`deploy_config.sh`、Fast Deploy Android Job、签名渠道与正式制品验收。本地 Debug APK 不进入正式分发，本地构建与启动不能代替发布或完整 Android 工程标准验收。

正式发布前仍需对齐：

1. [统一版本](https://github.com/darren-you/darren-space/blob/master/harness/docs/workspace/standards/client-version-golden-path.md)：工程根 `release.json`、固定 Core 解析器与 Gradle 输入、Panel 正式编号及本地验证标记。
2. [Android 标准](https://github.com/darren-you/darren-space/blob/master/harness/docs/workspace/standards/android-app/android-app-golden-path.md)：真实发布配置与签名、渠道、制品验证。
3. [Debug Panel](https://github.com/darren-you/darren-space/blob/master/harness/docs/workspace/standards/debug-panel-golden-path.md)：全局面板、类型安全 TestPage 与 Release `_debug` 门禁。

本地修复保留安装身份、模型与既有业务入口；ONNX 仍是未从设备列表开放的实验实现。
