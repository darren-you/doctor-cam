# Android 开发与验证

## 独立 checkout

```bash
git clone https://github.com/darren-you/doctor-cam.git
cd doctor-cam
./gradlew --version
```

仓根是唯一 Gradle 工程根，应用模块为 `app`。开发不依赖 darren-space 或相邻仓库；本机 JDK、Android SDK 与匹配的 Native 工具链必须单独准备。只克隆源码并不表示全部依赖已经可用。

## 当前构建声明

| 事实 | 当前源码 |
| --- | --- |
| Wrapper / AGP | Gradle 8.10.2 / Android Gradle Plugin 8.8.0 |
| Java 源码 | Java 11 |
| Android SDK | compile / target 35，min 21 |
| Native | NDK 17.2.4988734、CMake 3.10.2、C++11、arm64-v8a |
| 安装身份 | `cn.darrenyou.doctorcam` |
| 历史版本声明 | `app/build.gradle` 中 versionName 1.0、versionCode 1；尚未接入统一版本合同 |

事实源为 [Wrapper 配置](../../../gradle/wrapper/gradle-wrapper.properties)、[版本目录](../../../gradle/libs.versions.toml)、[应用 Gradle](../../../app/build.gradle)和 [CMake](../../../app/src/main/cpp/CMakeLists.txt)。

设置本机 `JAVA_HOME`、`ANDROID_HOME` 后，可运行：

```bash
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false help
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false :app:assembleDebug
./gradlew --no-daemon -Pandroid.builder.sdkDownload=false :app:testDebugUnitTest
```

这些是当前源码的检查入口，不是已通过承诺。首次解析需要访问 Google Maven、Maven Central 等声明仓库；`--offline` 只在所需依赖已有本地缓存时适用。关闭自动下载用于暴露缺失工具链，不由验证命令静默安装 SDK 或 Native 工具。

## 已核对的构建缺口

- [settings.gradle](../../../settings.gradle)把 OpenCV 工程指向仓根 `OpenCV-android-sdk/`，该目录缺失；现有受跟踪模块实际位于 `app/OpenCV/sdk/`。只改路径尚不足以证明修复，因为该模块仍采用 compileSdk 26、Java 6 和旧 Library 配置。
- CMake 从 `app/OpenCV/sdk/native/jni/` 与 `app/PaddleLite/cxx/` 装配 Native 依赖，Gradle 的 Java OpenCV 工程入口却不一致。后续需要选择并完整验证一套装配合同，不能复制缺失目录作为永久兼容入口。
- 2026-10-10 在 mac-work-1 核对：Zulu JDK 21.0.6、Gradle 8.10.2 Wrapper 和 SDK 35 可用；本机 NDK 为 27 / 28 系列，CMake 为 3.22 / 4.1 系列，未具备源码声明的版本组合。
- 本次联网 `help` 解析 AGP 后在 `:app` 配置阶段失败，错误为 `NDK not configured. Download it with SDK manager. Preferred NDK version is 27.0.12077973`；随后离线 `:app:assembleDebug` 同样失败。该错误与源码固定 NDK 17.2 的声明分别记录，不能只凭错误推荐版本自动升级。未生成 APK，也未执行单元测试或设备测试。

现有 `ExampleUnitTest` 仅检查算术结果，`ExampleInstrumentedTest` 仅检查安装包名；即使运行成功，也不能证明 UVC、推理或 JNI 业务正确。

## 标准发布边界

当前没有 Core 子模块、`deploy_config.sh`、Fast Deploy Android Job、签名渠道与正式制品验收。项目元数据保留真实 `android-app` 技术域及交付面；处于原型阶段不意味着 Android 工程标准已经通过。

尚未对齐的产品工程合同包括：

1. [统一版本](https://github.com/darren-you/darren-space/blob/master/harness/docs/workspace/standards/client-version-golden-path.md)：缺少工程根 release.json、固定 Core 解析器及 Gradle 版本输入接线；历史手工版本仍在源码中。
2. [Android 标准](https://github.com/darren-you/darren-space/blob/master/harness/docs/workspace/standards/android-app/android-app-golden-path.md)：缺少标准发布配置与可用发布链路；当前构建尚未通过。
3. [Debug Panel 合同](https://github.com/darren-you/darren-space/blob/master/harness/docs/workspace/standards/debug-panel-golden-path.md)：现有 Activity / View 工程尚未实现统一面板、类型安全 TestPage 和 Release `_debug` 静态门禁；手写 BuildConfig.DEBUG 不能替代该合同。

纳管文档不改变安装身份、现有界面、模型、Native 依赖或签名状态。后续产品工程改造须按真实构建、测试、正式版本与设备验收分别交付；本地 Debug APK 不进入正式分发。设备和摄像头验证需要独立确认目标与操作范围，本次纳管没有访问设备。
