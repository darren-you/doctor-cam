# Android 架构与实现边界

DoctorCam 是 Java / XML 界面的单模块 Android 原型。仓根负责 Gradle 装配，`app` 承载页面、UVC 设备接入与端侧检测；没有自有后端、账号、网络 API 或数据库工程。

## 页面与设备生命周期

[MainActivity](../../../app/src/main/java/cn/darrenyou/doctorcam/MainActivity.java)装配 Navigation Host、权限和 OpenCV 初始化。[设备列表页](../../../app/src/main/java/cn/darrenyou/doctorcam/UvcDevicesFragment.java)通过 [UsbDeviceManager](../../../app/src/main/java/cn/darrenyou/doctorcam/manager/UsbDeviceManager.java)监听 USB 插拔、枚举设备并申请访问权限。

点击设备进入 [UvcPreviewFragment](../../../app/src/main/java/cn/darrenyou/doctorcam/UvcPreviewFragment.java)，长按进入 [PaddleDetectionFragment](../../../app/src/main/java/cn/darrenyou/doctorcam/PaddleDetectionFragment.java)。[导航图](../../../app/src/main/res/navigation/nav_graph.xml)还声明 ONNX 页面，但设备列表中的 ONNX 跳转目前被注释。不能据此声称用户已能在界面切换两种推理后端。

普通预览由 UVCCamera 输出到 TextureView，支持抓拍；检测页注册 NV21 帧回调，将画面转换为 Bitmap，再推理并通过透明 SurfaceView 绘制检测框。摄像头关闭、USBMonitor 注销和模型释放分别由页面生命周期代码处理，仍需在真实设备验证拔插、切页和异常恢复。

## 两条推理实现

| 实现 | 源码职责 | 当前边界 |
| --- | --- | --- |
| [ONNX Runtime Java](../../../app/src/main/java/cn/darrenyou/doctorcam/onnx/OnnxYOLODetector.java) | 从 assets 加载模型；320 × 320 letterbox、张量构造、置信度筛选、按类 NMS、原图坐标还原 | 当前不是设备列表开放入口；没有实测精度或性能结论 |
| [Paddle Java 接口](../../../app/src/main/java/cn/darrenyou/doctorcam/paddle/PaddleDetector.java)与 [C++ Pipeline](../../../app/src/main/cpp/Pipeline.cc) | Java 持有 Native 上下文，JNI 传入 Bitmap；C++ 预处理、Paddle Lite 推理和检测结果转换 | 当前默认检测入口；Native 装配与完整构建仍待恢复验证 |

模型资源分别位于 `app/src/main/assets/onnx/` 和 `app/src/main/assets/paddle/`。ONNX 类别常量与 Paddle 标签分别维护；新增模型不能默认复用相同输出格式、标签数量或坐标含义。

## 图像与保存

[ImageProcessingUtils](../../../app/src/main/java/cn/darrenyou/doctorcam/utils/ImageProcessingUtils.java)处理帧到 Bitmap 的转换，[DetectionUtils](../../../app/src/main/java/cn/darrenyou/doctorcam/utils/DetectionUtils.java)绘制检测框和标签。[StorageUtils](../../../app/src/main/java/cn/darrenyou/doctorcam/utils/StorageUtils.java)在 Android 10 及以上使用 MediaStore，低版本使用外部图片目录及媒体登记。现有实现没有图片上传链路。

帧回调当前包含同步转换与推理、逐帧对象分配及主线程绘制通知；源码没有足够证据支持缓冲池、完整背压或零拷贝优化的表述。现有测试仍是模板测试，不能覆盖图像坐标、摄像头生命周期或 JNI 内存边界。

## 已知实现缺口

- [PaddleDetector.java](../../../app/src/main/java/cn/darrenyou/doctorcam/paddle/PaddleDetector.java)的 `init` / `initDefault` 返回 `ctx == 0`，与非零 Native 上下文表示成功的语义相反；当前调用页面未使用该返回值判断初始化结果。
- [PaddleDetector.cc](../../../app/src/main/cpp/PaddleDetector.cc)的 `nativeProcessWithResults` 借用 Bitmap 像素构造 `cv::Mat`，在执行 `Predict` 前已调用 `AndroidBitmap_unlockPixels`。后续推理仍使用该像素地址，存在借用生命周期风险。
- 绘制工具使用 ONNX 类别列表查找颜色，而 Paddle 标签独立维护；不能假设两套标签始终一致。

这些缺口保留为现有实现事实，本轮文档与纳管没有修改或验证修复结果。

## 验证与演进边界

结构与调用关系来自源码核对，不代表已经在本次任务运行成功。工具链、OpenCV 装配、版本接线、Debug Panel 和正式发布缺口统一见[开发与验证说明](../../operations/android-app/development.md)。当前工作只补齐文档与仓库治理，不改动产品实现。

标准入口：[Android 工程标准](https://github.com/darren-you/darren-space/blob/master/harness/docs/workspace/standards/android-app/android-app-golden-path.md)。
