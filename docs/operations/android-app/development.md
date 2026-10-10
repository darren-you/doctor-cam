# Android 开发与验证

## 工程与工具链

仓根是唯一 Gradle 工程根，应用模块为 `app`，仓内 OpenCV Java 模块为 `:opencv`。应用按 Android 母版采用 Kotlin DSL、Version Catalog、Kotlin Compose / Serialization 插件、Hilt 与 KSP；设备、推理与相册实现落在本仓源码，独立 checkout 不读取相邻工作区。

| 事实 | 当前源码 |
| --- | --- |
| Wrapper / AGP | Gradle 8.13 / Android Gradle Plugin 8.13.2 |
| Kotlin / JVM | Kotlin 2.3.10，Java / Kotlin 目标 17；Gradle 使用 JDK 17 及以上 |
| UI 与状态 | Compose BOM 2026.03.00、Material 3、Navigation Compose 2.9.7、Lifecycle / ViewModel / StateFlow |
| 依赖装配 | Hilt 2.58、KSP 2.3.6、Version Catalog |
| Android SDK | compile / target 36，min 26；AGP 默认 Build Tools 35.0.0 |
| Native | NDK 17.2.4988734、CMake 3.22.1、C++11、arm64-v8a |
| OpenCV | 4.2.0，仓内同版本 Java 绑定与 Native SDK |
| UVC / ONNX Runtime | org.uvccamera:lib:0.0.13 / onnxruntime-android:1.16.3 |
| 安装身份 | `cn.darrenyou.doctorcam`，Debug 不追加身份后缀 |
| 本地版本 | 工程根 `release.json`：1.0.0；Core 本地验证编号 1 |
| 发布装配 | 仓内固定 `fast-deploy-core`；正式 Job、配置与签名渠道尚未接入 |

事实源为 [Wrapper](../../../gradle/wrapper/gradle-wrapper.properties)、[版本目录](../../../gradle/libs.versions.toml)、[应用 Gradle](../../../app/build.gradle.kts)、[OpenCV 模块](../../../app/OpenCV/sdk/build.gradle.kts)、[CMake](../../../app/src/main/cpp/CMakeLists.txt)和 [release.json](../../../release.json)。[AGP 8.13 官方兼容性说明](https://developer.android.google.cn/build/releases/agp-8-13-0-release-notes?hl=en)确认 8.13.2 支持 Kotlin 2.3，要求 Gradle 8.13 / JDK 17，默认 Build Tools 35.0.0。

母版当前的 Hilt 2.57.2 在本仓真实编译时无法读取 Kotlin 2.3 元数据，因此 DoctorCam 固定为 Hilt 2.58，并使用当前 `androidx.hilt.lifecycle.viewmodel.compose` 入口。2.58 的编译器消费支持新元数据的官方依赖，未忽略校验；[官方 Release](https://github.com/google/dagger/releases/tag/dagger-2.58)保留 AGP 8 装配，未引入强制 AGP 9 升级。完整 Hilt 代码生成、聚合 Java 编译，以及 Debug 主包与 instrumentation 包构建已通过。

现有 Paddle 预编译 ELF 与 NDK 17 的链接器合同绑定，工程继续固定 NDK 17.2.4988734。升级 Kotlin / Android 装配没有替换模型、改写 ELF、放宽链接器校验或重建 Native 依赖。CMake 使用 3.22.1，Android Native 目标为 ARM64。

## 准备与构建

```bash
git clone --recurse-submodules https://github.com/darren-you/doctor-cam.git
cd doctor-cam
git submodule update --init fast-deploy-core
```

设置本机 `JAVA_HOME` 与 `ANDROID_HOME`，准备明确的 SDK 组件。NDK 17 的 macOS 工具是 Intel 程序，Apple Silicon 构建宿主需要 Rosetta 2；生成的 Android APK 仍为 ARM64。

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-36" "build-tools;35.0.0" \
  "ndk;17.2.4988734" "cmake;3.22.1"
./gradlew --version
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false help
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

应用与 instrumentation APK 分别位于：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

首次解析依赖需要 Google Maven、Maven Central 与 Gradle 插件仓库；`--offline` 只在所需依赖已完整缓存时使用。命令关闭 SDK 自动下载，让缺失组件明确失败。仓内 Core 子模块必须先物化，否则版本输入不能解析。

AGP 生成 variant 对应的 `BuildConfig`；Compose 页面不再使用 ViewBinding 或 XML 导航。OpenCV 直接打包仓内 Java 绑定与预编译 Native 库，Paddle CMake 从同一 SDK 与 `app/PaddleLite/cxx/` 装配原 Native 依赖。

## 安装与启动

先核对 `adb devices -l`，再显式选择本次授权的序列号；安装包保留原身份，不卸载或清空已有应用数据。选择 ARM64 真机或 ARM64 模拟器，x86 模拟器不能加载本工程的 Native 库。

```bash
adb devices -l
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -W -n cn.darrenyou.doctorcam/.MainActivity
adb -s <serial> shell pidof cn.darrenyou.doctorcam
adb -s <serial> logcat -d -b crash
```

主界面展示设备列表。连接真实 USB UVC 摄像头后轻点预览，长按进入 Paddle 检测；选择设备时先申请相机权限，Android 8 / 9 同时申请存储权限，全部授予且所选设备仍存在后才进入预览。USB 设备权限由相机控制器的 USBMonitor 申请和消费，并按实际设备 ID、VID、PID 匹配。抓拍时不弹出存储授权对话框；Android 8 / 9 的存储权限失效时提示返回设备列表重新授权。

未连接 UVC 时显示“暂无设备”，模拟器内置摄像头不等同于 USB UVC。全局 Debug Panel 默认展开；可拖动、折叠，点击 `TestPage` 进入开发检查页，再通过“返回业务页面”返回。

## Android 回归

```bash
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w \
  cn.darrenyou.doctorcam.test/androidx.test.runner.AndroidJUnitRunner
```

也可在确认目标后使用 Gradle 标准入口：

```bash
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  -Pandroid.builder.sdkDownload=false :app:connectedDebugAndroidTest
```

当前源码共 10 条 instrumentation 用例；下表列出覆盖范围，执行结果以目标设备的测试报告为准。

| 用例 | 数量 | 验证范围 |
| --- | --- | --- |
| [DoctorCamUiInstrumentedTest](../../../app/src/androidTest/java/cn/darrenyou/doctorcam/DoctorCamUiInstrumentedTest.kt) | 3 | 真实 Activity 的设备空态与重建、隐藏 ONNX 可点击入口、面板拖动 / 折叠 / 展开、TestPage 导航与返回、真实横屏与系统安全区；要求没有接入 UVC 且旋转用例从竖屏启动，结束前恢复原 Activity 方向请求 |
| [NativeRuntimeInstrumentedTest](../../../app/src/androidTest/java/cn/darrenyou/doctorcam/NativeRuntimeInstrumentedTest.java) | 4 | OpenCV Native 运算、UVC Native 构造 / 销毁、NV21 红色色序与借用 Buffer、原 Paddle 模型推理、格式拒绝、重复释放与重初始化 |
| [InferenceEngineInstrumentedTest](../../../app/src/androidTest/java/cn/darrenyou/doctorcam/InferenceEngineInstrumentedTest.kt) | 2 | 重构后的真实 Paddle / 隐藏 ONNX 引擎执行原 assets；输入尺寸、检测结果合同、重复关闭、关闭后拒绝与重建 |
| [MediaRepositoryInstrumentedTest](../../../app/src/androidTest/java/cn/darrenyou/doctorcam/MediaRepositoryInstrumentedTest.kt) | 1 | API 29 及以上实际保存并读回 JPEG，检查 URI / MIME / 待完成状态、检测框色序、尺寸与输入回收；仅删除本次测试生成的 URI |

合成图像用于验证执行、资源与图像合同，允许模型输出零检测，不提供识别准确率或真实摄像头证据。媒体回归验证独立的绘制与存储入口，不能替代真实 UVC 抓拍。JVM 检查、源码链接检查和本地 APK 构建也不能替代 Android instrumentation。

真实摄像头验收应覆盖相机与 USB 权限拒绝 / 授予、预览、抓拍图片、Paddle 持续帧推理、切页、Activity 重建、后台和拔插。没有摄像头时保持这些项未验收。现有预编译 Native 依赖的设备与页大小兼容性需单独验证，不能把 ARM64 / 4 KiB 环境的成功扩展为 16 KiB 页设备结论。

## 版本与 Release 门禁

[release.json](../../../release.json)只维护人工三段版本。Gradle 消费仓内固定 Core 的 `shared/client_version.py`，本地构建使用验证编号 `1` 并写入 `client_local_validation=true` 与空 `client_run_id`；构建不修改版本文件或提交 Git。

正式构建必须读取 Panel 分配的冻结编号快照，并校验真实 variant、安装身份、ABI 和版本。缺少快照时只允许前置检查与准备，禁止产包。`prepareReleaseClientVersion` 只生成准备事实，不分配正式编号或建立正式发布任务。

[DoctorCamApp.kt](../../../app/src/main/java/cn/darrenyou/doctorcam/DoctorCamApp.kt)中唯一的 `_debug = true` 是开发默认值。所有 Release variant 在编译前调用同一 `verifyReleaseDebugDisabled`；编号准备也依赖该门禁。缺失、重复、非字面量或值为 `true` 时明确失败，须由维护者显式改为唯一 `false`。

```bash
bash tool/test_check_release_debug.sh
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false :app:verifyReleaseDebugDisabled
```

第二条在默认开发源码下应失败。门禁 fixture 验证唯一 `false` 可接受，以及 `true`、缺失、重复、可变或伪装声明等拒绝情形。Debug 构建不依赖 Release 门禁。

当前没有 `deploy_config.sh`、Fast Deploy Android Job、正式签名和渠道验收。本地 APK 只用于验证，不进入正式分发；发布接入仍须按工作区 Android 与统一版本标准完成真实配置、编号、签名、制品和设备验收。

## OpenCV Java 绑定来源

Java 绑定来自 [OpenCV 4.2.0 官方 Android SDK](https://github.com/opencv/opencv/releases/download/4.2.0/opencv-4.2.0-android-sdk.zip)，仅导入 `sdk/java/src`、`sdk/java/res` 与 Java Manifest。AGP 的 namespace 在 Kotlin DSL 声明，Manifest 不保留旧 package 字段；Java 绑定保留上游实现与版权。

- 官方 ZIP SHA-256：`55c85d2d23b244a233c65392770d51b51a1afd8c15cdef72207f774fceba6219`。
- 官方和仓内 ARM64 `libopencv_java4.so` SHA-256：`8c2a880f9cd1f5d98208ab0a6d10234389b8e49dae1cfde36ba33b8a888a9461`。
- 许可证：[OpenCV LICENSE](../../../app/OpenCV/LICENSE)。

Java 与 Native 保持同版本；不另建第二个 SDK 目录或运行时读取邻仓依赖。
