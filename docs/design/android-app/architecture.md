# Android 架构与实现边界

DoctorCam 是 Java / XML 界面的单模块 Android 原型。仓根负责 Gradle 装配，`app` 承载页面、UVC 设备接入与端侧检测；没有自有后端、账号、网络 API 或数据库工程。

## 页面与设备生命周期

[MainActivity](../../../app/src/main/java/cn/darrenyou/doctorcam/MainActivity.java)装配 Navigation Host、权限和 OpenCV 初始化。[设备列表页](../../../app/src/main/java/cn/darrenyou/doctorcam/UvcDevicesFragment.java)通过 [UsbDeviceManager](../../../app/src/main/java/cn/darrenyou/doctorcam/manager/UsbDeviceManager.java)监听 USB 插拔并枚举视频设备。导航前检查相机权限，USB 设备授权由目标页持有的 USBMonitor 请求和消费，按实际 `deviceId` 匹配所选设备。

点击设备进入 [UvcPreviewFragment](../../../app/src/main/java/cn/darrenyou/doctorcam/UvcPreviewFragment.java)，长按进入 [PaddleDetectionFragment](../../../app/src/main/java/cn/darrenyou/doctorcam/PaddleDetectionFragment.java)。[导航图](../../../app/src/main/res/navigation/nav_graph.xml)还声明 ONNX 页面，但设备列表中的 ONNX 跳转目前被注释。不能据此声称用户已能在界面切换两种推理后端。

普通预览由 UVCCamera 输出到 TextureView，支持抓拍；检测页注册 NV21 帧回调，将画面转换为 Bitmap，再推理并通过透明 SurfaceView 绘制检测框。摄像头关闭、USBMonitor 注销和模型释放分别由页面生命周期代码处理，仍需在真实设备验证拔插、切页和异常恢复。

## 两条推理实现

| 实现 | 源码职责 | 当前边界 |
| --- | --- | --- |
| [ONNX Runtime Java](../../../app/src/main/java/cn/darrenyou/doctorcam/onnx/OnnxYOLODetector.java) | 从 assets 加载模型；320 × 320 letterbox、张量构造、置信度筛选、按类 NMS、原图坐标还原 | 当前不是设备列表开放入口；没有实测精度或性能结论 |
| [Paddle Java 接口](../../../app/src/main/java/cn/darrenyou/doctorcam/paddle/PaddleDetector.java)与 [C++ Pipeline](../../../app/src/main/cpp/Pipeline.cc) | Java 持有 Native 上下文，JNI 传入 Bitmap；C++ 预处理、Paddle Lite 推理和检测结果转换 | 当前默认检测入口；模拟器可验证包内模型执行，摄像头帧与效果须分别验收 |

模型资源分别位于 `app/src/main/assets/onnx/` 和 `app/src/main/assets/paddle/`。ONNX 类别常量与 Paddle 标签分别维护；新增模型不能默认复用相同输出格式、标签数量或坐标含义。

## 图像与保存

[ImageProcessingUtils](../../../app/src/main/java/cn/darrenyou/doctorcam/utils/ImageProcessingUtils.java)处理帧到 Bitmap 的转换，[DetectionUtils](../../../app/src/main/java/cn/darrenyou/doctorcam/utils/DetectionUtils.java)绘制检测框和标签。[StorageUtils](../../../app/src/main/java/cn/darrenyou/doctorcam/utils/StorageUtils.java)在 Android 10 及以上使用 MediaStore，低版本使用外部图片目录及媒体登记。现有实现没有图片上传链路。

帧回调当前包含同步转换与推理、逐帧对象分配及主线程绘制通知；源码没有足够证据支持缓冲池、完整背压或零拷贝优化的表述。现有测试仍是模板测试，不能覆盖图像坐标、摄像头生命周期或 JNI 内存边界。

## Native 资源与图像合同

- [PaddleDetector.java](../../../app/src/main/java/cn/darrenyou/doctorcam/paddle/PaddleDetector.java)以非零上下文表示初始化成功；初始化、推理和释放串行访问。释放前清零 Java 上下文，重复释放不再次删除 Native 指针；重新初始化先释放旧上下文。
- [PaddleDetector.cc](../../../app/src/main/cpp/PaddleDetector.cc)按 Bitmap 实际 stride 构造 Mat，在像素锁内复制为 OpenCV 所有的图像，随后解锁并预测。只接受 ARGB8888；Native 失败明确报告，不能伪装成没有检测框。
- Pipeline 按 Android Bitmap 的 RGBA 顺序转换到模型 RGB 输入；JNI 标签索引使用当前模型的标签，不跨上下文保留静态映射。

绘制工具仍使用 ONNX 类别列表查找颜色，而 Paddle 标签独立维护；不能假设两套标签始终一致。模型文件与合成位图回归不提供医学准确率证据。

## 验证与演进边界

工具链、OpenCV 装配、启动和 Native 回归命令统一见[开发与验证说明](../../operations/android-app/development.md)。模拟器没有真实 UVC 摄像头时，设备列表为空；USB 权限、拔插、切页、抓拍与持续帧推理仍需对应真机和摄像头验证。版本接线、Debug Panel 与正式发布由该文档单独说明。

标准入口：[Android 工程标准](https://github.com/darren-you/darren-space/blob/master/harness/docs/workspace/standards/android-app/android-app-golden-path.md)。
