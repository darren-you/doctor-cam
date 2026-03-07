# DoctorCam 项目分析

分析时间：2026-03-07

本文档基于仓库当前源码整理，目标是说明这个项目现在“实际做了什么、怎么做的、哪里有风险”。仓库中已有的 `docs/desc.txt` 更偏论文式架构草稿，和当前实现并不完全一致；本文件以代码现状为准。

## 1. 项目定位

DoctorCam 是一个原生 Android 应用，核心目标是：

- 枚举并连接 USB UVC 摄像头。
- 提供实时预览和拍照能力。
- 在端侧对摄像头画面做目标检测。
- 同时保留两套推理后端：
  - ONNX Runtime Java 实现。
  - Paddle Lite JNI/C++ 实现。

从类别名和标签文件看，这个项目明显偏医疗视觉场景，检测对象包括：

- `声门裂`
- `杓状软骨`
- `混合识别`
- `会厌软骨`（仅 Paddle 标签中出现）

## 2. 技术栈概览

| 层级 | 实现 |
| --- | --- |
| 客户端框架 | 原生 Android，Java 11 |
| 构建系统 | Gradle + AGP 8.8.0 |
| 最低/目标 SDK | `minSdk 21` / `targetSdk 35` |
| UI 导航 | AndroidX Navigation + ViewBinding |
| 摄像头接入 | `org.uvccamera:lib:0.0.13` |
| ONNX 推理 | `com.microsoft.onnxruntime:onnxruntime-android:1.16.3` |
| Paddle 推理 | 本地集成 PaddleLite SDK + JNI |
| 图像处理 | OpenCV Java + OpenCV Native |
| Native 构建 | CMake 3.10.2 + NDK 17.2 |
| ABI | 当前只编译 `arm64-v8a` |

## 3. 目录结构

### 根目录

- `build.gradle` / `settings.gradle`：项目级 Gradle 配置。
- `gradle/libs.versions.toml`：版本目录。
- `docs/`：已有架构草图、PlantUML 文件和补充说明。
- `key/`：存在一个 `Untitled` 文件，未见在 Gradle 签名配置中使用。

### `app/`

- `src/main/java/cn/darrenyou/doctorcam/`
  - 页面与业务流程入口。
- `src/main/java/cn/darrenyou/doctorcam/manager/`
  - USB 设备管理。
- `src/main/java/cn/darrenyou/doctorcam/onnx/`
  - ONNX 推理逻辑。
- `src/main/java/cn/darrenyou/doctorcam/paddle/`
  - Paddle Java 封装。
- `src/main/java/cn/darrenyou/doctorcam/utils/`
  - 权限、图像处理、存储、OpenCV 初始化等工具类。
- `src/main/cpp/`
  - Paddle JNI 和 C++ 推理管线。
- `src/main/assets/`
  - `onnx/best_320x320.onnx`
  - `paddle/model.nb`
  - `paddle/doctor_label_list.txt`
- `OpenCV/`
  - 本地拷贝的 OpenCV Android SDK。
- `PaddleLite/`
  - 本地拷贝的 PaddleLite 头文件、jar、so。

## 4. 主流程

### 4.1 应用入口

`MainActivity` 负责：

- 初始化沉浸式状态栏/导航栏。
- 初始化 OpenCV。
- 挂载 Navigation Host。
- 统一申请权限。

### 4.2 设备列表页

`UvcDevicesFragment` 是默认首页，负责：

- 通过 `UsbDeviceManager` + `USBMonitor` 枚举 USB 设备。
- 在列表中展示可用 UVC 设备。
- 点击列表项进入普通预览页。
- 长按列表项进入检测页。

注意：当前长按只会跳转到 Paddle 检测页。ONNX 检测页面虽然已经实现，但入口在代码里被注释掉了，因此它属于“已实现但未暴露”的状态。

### 4.3 预览页

`UvcPreviewFragment` 负责：

- 横屏全屏显示。
- 连接指定 UVC 设备。
- 按最佳分辨率策略选择预览尺寸。
- 将预览输出到 `TextureView`。
- 支持抓拍并保存到系统相册。

### 4.4 检测页

项目里有两个检测页：

- `OnnxDetectionFragment`
- `PaddleDetectionFragment`

两者流程基本一致：

1. 初始化 UVC 相机和预览 `SurfaceView`。
2. 注册 `IFrameCallback` 接收 NV21 帧。
3. 将帧转成 `Bitmap`。
4. 调用各自检测器推理。
5. 通过 `DetectionUtils` 在透明叠加层绘制框和标签。
6. 支持把当前帧和标注结果保存到相册。

## 5. 推理实现

### 5.1 ONNX 路线

核心类：`OnnxYOLODetector`

实现特点：

- 模型直接从 `assets/onnx/best_320x320.onnx` 读入内存。
- 使用 ONNX Runtime 直接创建 `OrtSession`。
- 输入固定为 `320x320`。
- 预处理采用 letterbox，保留 `scale`、`offsetX`、`offsetY` 用于坐标反推。
- 输出后处理完全在 Java 里完成：
  - 遍历候选框
  - 计算类别分数
  - 手写 NMS
  - 映射回原图坐标

特点是实现直观、便于调试，但所有前后处理都在 Java 层完成，实时性比较依赖设备性能。

### 5.2 Paddle 路线

核心类：

- Java：`PaddleDetectionFragment`、`paddle/PaddleDetector.java`
- Native：`PaddleDetector.cc`、`Pipeline.cc`、`Utils.cc`

实现特点：

- 启动检测页时，会先把 `assets/paddle/model.nb` 和标签文件复制到缓存目录。
- Java 层只负责初始化参数、传入 `Bitmap`、接收 `float[][]` 结果。
- 真正的预处理、推理、后处理主要在 C++ 中完成。
- C++ 管线使用 `Pipeline` / `Detector` 封装：
  - `Preprocess`
  - `predictor_->Run()`
  - `Postprocess`

Paddle 路线的 native 化程度更高，更像一个可扩展的端侧推理管线雏形。

## 6. 图像与渲染链路

当前的数据流可以概括为：

`USBMonitor -> UVCCamera -> IFrameCallback(ByteBuffer) -> Bitmap 转换 -> 检测器 -> DetectionUtils -> SurfaceView 叠加层`

相关类职责如下：

- `CameraUtils`
  - 预定义常用分辨率并按屏幕比例选最接近的一个。
- `ImageProcessingUtils`
  - NV21/YUV 转 `Bitmap`
  - OpenCV 颜色纠正与简单预处理
- `DetectionUtils`
  - 在 `Canvas` 上绘制框和标签
- `StorageUtils`
  - 将图片保存到系统相册

## 7. 当前实现的优点

- 已经打通了“USB 摄像头接入 -> 预览 -> 推理 -> 绘制 -> 保存”的端到端链路。
- 同时保留 ONNX 和 Paddle 两条技术路线，便于对比速度和精度。
- Paddle 路线已经下沉到 JNI/C++，后续继续做性能优化有基础。
- 仓库里直接携带模型、OpenCV、PaddleLite，本地调试不依赖额外下载模型文件。

## 8. 当前主要问题和风险

以下问题是基于源码和配置核对后得到的结论，优先级从“影响构建/稳定性”到“影响维护性”排序。

### 8.1 构建配置不一致

- `settings.gradle` 把 `:opencv` 指到了根目录下的 `OpenCV-android-sdk/`。
- 仓库实际存在的是 `app/OpenCV/`，而且真正可作为 module 的是其中的 `sdk/`。

这说明当前仓库的 OpenCV Gradle 接入路径和目录现实不一致，项目在新环境下很可能无法直接同步。

### 8.2 OpenCV 模块版本老旧

`app/OpenCV/sdk/build.gradle` 还是旧版 Android Library 配置：

- `compileSdkVersion 26`
- Java 1.6
- 旧式 `apply plugin: 'com.android.library'`

这和主工程的 AGP 8.8 / compileSdk 35 不在同一代工具链上，后续大概率需要重做集成方式。

### 8.3 历史构建记录显示 native 链接失败

仓库内的 `build_log.txt` 记录了一次 C++ 链接失败，最终停在：

- `clang++: error: linker command failed with exit code 1`

日志里没有完整符号级错误，但至少说明 native 部分曾经不是稳定可构建状态。

### 8.4 本次构建验证未能在合理时间内完成

本次分析尝试执行：

```bash
./gradlew :app:assembleDebug
```

Gradle wrapper 下载成功，但首次依赖解析/Android 构建阶段未在合理时间内返回结果，因此本次不能把项目标记为“已验证可构建”。

### 8.5 Paddle Java 封装的返回值语义是反的

`paddle/PaddleDetector.java` 中：

- `init(...)` 返回 `ctx == 0`
- `initDefault(...)` 返回 `ctx == 0`

而 `ctx` 是 native 返回的上下文指针，正常成功时应该是非零值。也就是说，这两个初始化方法在成功时会返回 `false`。

当前页面代码没有依赖这个返回值做分支，所以问题暂时没有暴露成 UI 错误，但这是一个真实 bug。

### 8.6 JNI 中存在潜在的像素生命周期问题

`PaddleDetector.cc` 的 `nativeProcessWithResults(...)` 里：

- 先 `AndroidBitmap_lockPixels`
- 再用像素地址构造 `cv::Mat`
- 然后立刻 `AndroidBitmap_unlockPixels`
- 解锁后才继续调用 `Predict(...)`

这意味着后续推理可能在使用已经解锁的像素内存，存在未定义行为风险。这个问题比普通性能问题更严重，应该优先修正。

### 8.7 帧处理链路是同步串行的，且每帧分配对象很多

`OnnxDetectionFragment` 和 `PaddleDetectionFragment` 中，帧回调里每次都会做：

- 新建 `byte[]`
- 新建 `Bitmap`
- Java/OpenCV 转换
- 同步推理
- 再切回主线程绘制

当前没有看到缓冲池、限流队列、背压控制或丢帧策略。实际运行时很容易出现：

- 帧积压
- GC 抖动
- 延迟升高
- UI 卡顿

### 8.8 功能入口和实现状态不完全一致

- ONNX 检测页存在，但默认入口被注释。
- `deviceId` 在多个 Fragment 中传入，但实际匹配主要使用 `vendorId + productId`。
- 检测布局里的 `camera_stream_preview` 目前没有参与主流程。

这类代码不会马上导致崩溃，但说明页面和功能还处在 demo/试验阶段。

### 8.9 权限配置有冗余

`PermissionUtils` 会申请：

- `RECORD_AUDIO`
- 存储读写权限

但项目当前没有录音功能，`AndroidManifest.xml` 里也没有声明录音权限。这会让权限模型显得混乱。

### 8.10 类别体系没有统一

- ONNX 类别常量是 3 类。
- Paddle 标签文件是 4 类。
- `DetectionUtils` 还用 `OnnxYOLODetector.CLASSES` 去推断颜色索引。

结果是：

- Paddle 的类别颜色映射不完全可靠。
- 新增模型或调整标签顺序时，绘制层会跟着出错。

### 8.11 测试几乎为空

当前只有两个模板测试：

- `ExampleUnitTest`
- `ExampleInstrumentedTest`

没有覆盖任何：

- UVC 设备管理
- 推理后处理
- JNI 结果映射
- 坐标换算

## 9. 建议的后续治理顺序

如果要把这个项目从 demo 推进到可持续维护的工程，建议按下面顺序处理。

### 第一阶段：先恢复可构建性

1. 修正 OpenCV module 路径与接入方式。
2. 统一 OpenCV 集成方案。
3. 明确 NDK / CMake / PaddleLite / OpenCV 的版本组合。
4. 补一份最小可运行的构建说明。

### 第二阶段：先修稳定性问题

1. 修正 `PaddleDetector.init*` 返回值。
2. 修正 `nativeProcessWithResults` 的 bitmap 生命周期问题。
3. 去掉未使用权限和无效字段。
4. 把 ONNX / Paddle 的类别定义抽象成统一配置，而不是把颜色映射绑定在 ONNX 常量上。

### 第三阶段：再做性能优化

1. 把推理从 `IFrameCallback` 直接串行执行，改成单独工作线程。
2. 引入最新帧覆盖策略或有界队列。
3. 减少每帧 `byte[]` / `Bitmap` 分配。
4. 如果继续走 Paddle 路线，可把更多预处理和后处理留在 native 层。

### 第四阶段：补工程化能力

1. 增加最小化 smoke test。
2. 给模型输入输出格式补文档。
3. 给设备接入、拍照、推理结果保存增加日志分级和错误码。
4. 明确哪些页面是实验页，哪些是正式入口。

## 10. 一句话结论

这是一个已经跑通 UVC 摄像头和端侧检测主链路的 Android 原型项目，Paddle 路线的 native 管线已经具备继续演进的基础；但当前仍存在构建配置不一致、JNI 生命周期风险、推理链路同步阻塞和工程化不足等问题，整体更接近“可验证方案的实验工程”，还不是“稳定可交付应用”。
