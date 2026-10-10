# Android 开发与验证

## 工程与工具链

仓根是唯一 Gradle 工程根，应用模块为 `app`，OpenCV 通过官方 Maven AAR / Prefab 提供。应用按 Android 母版采用 Kotlin DSL、Version Catalog、Kotlin Compose / Serialization 插件、Hilt 与 KSP；设备、推理与相册实现落在本仓源码，独立 checkout 不读取相邻工作区。

| 事实 | 当前源码 |
| --- | --- |
| Wrapper / AGP | Gradle 8.13 / Android Gradle Plugin 8.13.2 |
| Kotlin / JVM | Kotlin 2.3.10，Java / Kotlin 目标 17；Gradle 使用 JDK 17 及以上 |
| UI 与状态 | Compose BOM 2026.03.00、Material 3、Navigation Compose 2.9.7、Lifecycle / ViewModel / StateFlow |
| 依赖装配 | Hilt 2.58、KSP 2.3.6、Version Catalog |
| Android SDK | compile / target 36，min 26；AGP 默认 Build Tools 35.0.0 |
| Native | NDK 30.0.16248370、CMake 4.1.2、C++17、arm64-v8a |
| OpenCV | org.opencv:opencv:5.0.0.1，官方 Java / Native / Prefab；运行 API 版本 5.0.0 |
| Paddle Lite | 官方最新 v2.14-rc with_extra；固定制品 URL 与整包 SHA-256，构建时仅准备头文件和 bundled 静态库 |
| UVC / ONNX Runtime | org.uvccamera:lib:0.0.13 / onnxruntime-android:1.31.0 |
| 安装身份 | `cn.darrenyou.doctorcam`，Debug 不追加身份后缀 |
| 本地版本 | 工程根 `release.json`：1.0.0；Core 本地验证编号 1 |
| 发布装配 | 仓内固定 `fast-deploy-core`；正式 Job、配置与签名渠道尚未接入 |

事实源为 [Wrapper](../../../gradle/wrapper/gradle-wrapper.properties)、[版本目录](../../../gradle/libs.versions.toml)、[应用 Gradle](../../../app/build.gradle.kts)、[Paddle 准备脚本](../../../tool/prepare_paddle_lite.py)、[CMake](../../../app/src/main/cpp/CMakeLists.txt)和 [release.json](../../../release.json)。[AGP 8.13 官方兼容性说明](https://developer.android.google.cn/build/releases/agp-8-13-0-release-notes?hl=en)确认 8.13.2 支持 Kotlin 2.3，要求 Gradle 8.13 / JDK 17，默认 Build Tools 35.0.0。

母版当前的 Hilt 2.57.2 在本仓真实编译时无法读取 Kotlin 2.3 元数据，因此 DoctorCam 固定为 Hilt 2.58，并使用当前 `androidx.hilt.lifecycle.viewmodel.compose` 入口。2.58 的编译器消费支持新元数据的官方依赖，未忽略校验；[官方 Release](https://github.com/google/dagger/releases/tag/dagger-2.58)保留 AGP 8 装配，未引入强制 AGP 9 升级。完整 Hilt 代码生成、聚合 Java 编译，以及 Debug 主包与 instrumentation 包构建已通过。

旧仓内 SDK、Java JAR / JNI、静态模块、非目标 ABI 和旧 `app/release` 输出已删除。OpenCV 使用官方最新 5.0.0.1 修复包，Paddle Lite 使用官方最新 v2.14-rc with_extra，ONNX Runtime 使用 1.31.0；UVC 发布方当前最新仍为 0.0.13。Native 工具链采用 NDK 30 / CMake 4.1.2，Paddle 使用同版官方 bundled 静态库链接到本仓 JNI。Android 平台与应用版本合同仍按模板保持 compile / target 36、min 26。

## 准备与构建

```bash
git clone --recurse-submodules https://github.com/darren-you/doctor-cam.git
cd doctor-cam
git submodule update --init fast-deploy-core
```

设置本机 `JAVA_HOME` 与 `ANDROID_HOME`，准备明确的 SDK 组件。生成的 Android APK 仅为 ARM64；本轮删除的是产品仓库中的旧 SDK 文件，不会卸载其他产品可能消费的本机 SDK 组件。

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-36" "build-tools;35.0.0" \
  "ndk;30.0.16248370" "cmake;4.1.2"
./gradlew --version
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false help
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

应用与 instrumentation APK 分别位于：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

首次解析依赖需要 Google Maven、Maven Central、Gradle 插件仓库及 Paddle 官方 GitHub Release；`--offline` 只在所需依赖已完整缓存时使用。命令关闭 SDK 自动下载，让缺失组件明确失败。仓内 Core 子模块必须先物化，否则版本输入不能解析。

AGP 生成 variant 对应的 `BuildConfig`；Compose 页面不再使用 ViewBinding 或 XML 导航。OpenCV 由 `implementation(libs.opencv)` 提供同版本 Java / Native，CMake 通过 Prefab 的 `OpenCV::opencv_java5` 链接共享库。`preparePaddleLiteSdk` 在构建前校验官方压缩包 SHA-256，只准备两个头文件与 ARM64 bundled 静态库到 `app/build/native-sdk/paddle-lite/`；该目录与其他构建产物一样不提交 Git，干净 checkout 可独立重建。共享 C++ 运行库只打包本项目使用的 NDK 30 版本；AGP 8.13 的项目 Native 合并顺序与精确重复项规则共同确定归属，产包后仍须核对实际库的 SHA-256。

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

## 官方 SDK 来源与升级边界

OpenCV 使用 [官方 Maven 发行](https://central.sonatype.com/artifact/org.opencv/opencv/5.0.0.1)，版本事实源在 Version Catalog。5.0.0.1 是 OpenCV 5.0.0 的 Android 页大小修复包，Java / Native API 仍返回 5.0.0；使用其 Prefab，仓内不复制 SDK 头文件、Java 源码或预编译库。来源与许可见 [OpenCV 5.0.0 Release](https://github.com/opencv/opencv/releases/tag/5.0.0)与[官方 Apache 2.0 许可](https://github.com/opencv/opencv/blob/5.0.0/LICENSE)。

Paddle Lite 使用[官方最新 v2.14-rc Release](https://github.com/PaddlePaddle/Paddle-Lite/releases/tag/v2.14-rc)的 ARM64 clang / c++_shared with_extra 压缩包。地址、完整 SHA-256 `cb6a03060fa10676f7f599aed41efdcc48bbcee9779c24499afcde111313be03` 与精确提取成员由本仓 SDK 准备脚本拥有；校验失败立即终止，不保留旧 SDK 回退。该版本名称明确包含 rc，不能称为无候选标记的稳定版。使用同一官方压缩包内的 `cxx/lib/libpaddle_api_light_bundled.a`，SHA-256 为 `3942ced06f7064d85fcb0c81ab93aee2422a06b0de5ed53c150488a51d697751`；521 个 ARM64 对象的符号分组合法，with_extra 包含原模型使用的 hard_swish 等扩展算子，CMake 通过 whole-archive 保留其算子注册，再由 NDK 30 链接到本仓 JNI；其 OpenMP 符号由同一 NDK 的静态运行库提供，不新增共享 OpenMP 或旧 SDK 依赖。官方 shared SO 的动态符号分组错误会被现代 lld 拒绝，因此本仓直接采用同版合法静态发行库，不修改第三方二进制或放宽链接检查。许可证见 [Paddle Lite Apache 2.0](https://github.com/PaddlePaddle/Paddle-Lite/blob/v2.14-rc/LICENSE)。

原模型与标签不变。`model.nb` 记录的导出版本为 v2.13-rc，运行库 2.14-rc 支持相同序列化格式，但版本不一致会警告；只有用包内模型实际初始化、推理和重建的 Android 回归才提供执行证据，不能替代识别准确率或模型重新导出。ONNX Runtime 1.31.0 的来源见[官方 Release](https://github.com/microsoft/onnxruntime/releases/tag/v1.31.0)。该版本 AAR 新增自动遥测 Provider 与网络权限；本产品只有端侧推理，因此 Manifest 移除它们，并在创建唯一 OrtEnvironment 前按[官方关闭合同](https://github.com/microsoft/onnxruntime/blob/v1.31.0/docs/Privacy.md)设置 `ORT_DISABLE_TELEMETRY=1`，保持离线运行。环境变量须在 Native 初始化前设置，初始化后的事件开关不能替代此顺序。

OpenCV 修复包、ONNX Runtime 与本仓 NDK 30 链接的 Paddle JNI 采用 16 KiB 或更大的 ELF 段对齐；UVC 发布方的[最新 0.0.13](https://github.com/alexey-pelykh/UVCCamera/releases/tag/0.0.13)仍包含 4 KiB 对齐库。因此当前整个 APK 不具备完整 16 KiB 页适配证据，4 KiB Pixel 的成功不能扩大为 16 KiB 设备结论。页对齐检查与实际设备运行属于不同证据。
