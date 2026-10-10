# Android 架构与实现边界

DoctorCam 使用 Kotlin、Compose、Material 3、类型安全 Navigation Compose、Hilt、ViewModel 与协程 / StateFlow。仓根是 Android 工程根，`app` 为业务应用，`:opencv` 提供仓内同版本 Java / Native SDK；Paddle 以 Java JNI 和 C++ 边界连接既有预编译库。应用的持久输出是系统相册图片，没有自有后端、账号或业务数据库。

## 页面与依赖装配

[DoctorCamApplication](../../../app/src/main/java/cn/darrenyou/doctorcam/DoctorCamApplication.kt)建立 Hilt 依赖图，[MainActivity](../../../app/src/main/java/cn/darrenyou/doctorcam/MainActivity.kt)负责单 Activity、边到边系统栏与 Material 3 主题。[DoctorCamApp](../../../app/src/main/java/cn/darrenyou/doctorcam/DoctorCamApp.kt)使用类型安全 `DevicesRoute`、`CameraRoute`、`TestRoute` 组织 Compose 导航。

[设备列表页](../../../app/src/main/java/cn/darrenyou/doctorcam/feature/devices/DevicesPage.kt)通过 ViewModel 收集实际 USB 视频设备。轻点进入普通预览，长按进入 Paddle 检测。导航携带设备 ID、VID、PID 与检测模式；进入预览前由 Activity Result API 申请所需权限，Android 8 / 9 同时要求相机与存储权限，Android 10 及以上只要求相机权限。全部权限授予后再次核对所选设备是否仍存在。

[相机页](../../../app/src/main/java/cn/darrenyou/doctorcam/feature/camera/CameraPage.kt)通过 `AndroidView` 挂载 TextureView。UI 创建 Surface 后将所有权移交相机控制器；控制器在设备线程停止预览并释放 Surface，再回调 UI 释放所属 SurfaceTexture。`onSurfaceTextureDestroyed` 返回 `false`，避免框架提前释放仍被 Native 使用的纹理；迟到的旧视图回调按预期 Surface 对象隔离。页面按真实帧比例居中显示预览，Compose Canvas 根据检测结果的原图尺寸缩放绘制框和标签。没有完整帧或正在保存时，抓拍按钮不可用。

页面在 `ON_RESUME` 打开所选设备，在 `ON_PAUSE` 和离开组合时关闭会话；[CameraViewModel](../../../app/src/main/java/cn/darrenyou/doctorcam/feature/camera/CameraViewModel.kt)销毁时释放控制器线程。保存期间通过独立位图保留同一帧的画面与检测结果，避免下一帧替换保存输入。

## USB 与相机会话

- [UsbDeviceRepository](../../../app/src/main/java/cn/darrenyou/doctorcam/core/camera/UsbDeviceRepository.kt)只观察系统 USB 插拔事件和 UsbManager 设备表，以 StateFlow 提供视频设备列表；列表不持有 USBMonitor 或相机连接。
- [UsbCameraController](../../../app/src/main/java/cn/darrenyou/doctorcam/core/camera/UsbCameraController.kt)由每个相机 ViewModel 独占，拥有 USBMonitor、UVCCamera、控制连接、推理引擎与最近成功帧。设备资源操作在专属 HandlerThread / Looper 串行执行。
- USB 授权请求、连接与回调按设备 ID、VID、PID 匹配；会话标识使已关闭页面的旧异步回调不能重开相机或覆盖新会话。
- 预览尺寸只从摄像头实际声明的偶数宽高与 MJPEG / YUYV 格式中选择；连接或推理失败进入可见错误状态。断开、暂停或销毁时关闭 Native 相机、原始控制连接、监视器、模型及缓存位图。
- 帧回调将 NV21 转为 ARGB8888，在推理成功后把位图与检测结果作为同一批次保存。抓拍返回独立位图，媒体保存入口负责消费和回收。

这些是源码中的所有权和状态合同。没有真实摄像头时，测试不能证明 USB 授权、拔插、持续预览或抓拍已经通过。

## 两条推理实现

[InferenceEngineFactory](../../../app/src/main/java/cn/darrenyou/doctorcam/core/inference/InferenceEngineFactory.kt)初始化 OpenCV，并按模式创建推理引擎；普通预览不创建模型。检测结果统一使用 `Detection`，携带类别、概率、边界与源图像尺寸。

| 实现 | 职责 | 当前入口 |
| --- | --- | --- |
| [PaddleInferenceEngine](../../../app/src/main/java/cn/darrenyou/doctorcam/core/inference/PaddleInferenceEngine.kt) | 从当前 assets 复制原模型与标签；通过 Java / JNI 推理，校验结果并转换为统一检测类型 | 设备长按进入的默认检测实现 |
| [OnnxInferenceEngine](../../../app/src/main/java/cn/darrenyou/doctorcam/core/inference/OnnxInferenceEngine.kt) | 原模型的 320 × 320 letterbox、RGB 张量、置信度筛选、按类 NMS 与原图坐标还原；释放自身 Session | 保留内部实现，设备列表没有公开入口 |

Paddle 使用 `paddle/model.nb`、`paddle/doctor_label_list.txt` 和 `initDefault` 的既有输入尺寸、均值、方差、线程数、功耗模式与阈值。ONNX 使用 `onnx/best_320x320.onnx` 及自身类别顺序，两套标签不混用。重构没有替换模型或声称识别准确率改善。

每个 Paddle 引擎使用独立的临时模型目录，构造失败或关闭时清理；ONNX 引擎只拥有自身 Session，进程共享的 OrtEnvironment 不由单个引擎释放。两种引擎都支持重复关闭，关闭后拒绝继续推理；有效检测框约束在原图边界内。

## Native 图像与资源合同

- [PaddleDetector.java](../../../app/src/main/java/cn/darrenyou/doctorcam/paddle/PaddleDetector.java)以非零上下文表示初始化成功；初始化、推理和释放同步访问。释放前清零 Java 上下文，重复释放不再次删除 Native 指针；重新初始化先释放旧上下文。
- [PaddleDetector.cc](../../../app/src/main/cpp/PaddleDetector.cc)按 Bitmap 实际 stride 读取像素，在锁内复制为 OpenCV 所有的图像后解锁预测。只接受 ARGB8888；Native 失败明确报告，不能冒充零检测结果。
- [ImageProcessingUtils](../../../app/src/main/java/cn/darrenyou/doctorcam/utils/ImageProcessingUtils.java)复用同一个 NV21 → RGBA → Bitmap 转换入口；借用 Buffer 的读取不改变调用方 position / limit，数据不足或尺寸不合法返回失败。
- C++ Pipeline 按 Android Bitmap 的 RGBA 顺序转为模型 RGB 输入；JNI 标签索引来自当前模型，不使用跨上下文静态标签映射。

帧转换仍分配位图，推理仍在真实回调链路执行。本工程没有以合成测试证明零拷贝、持续帧率或完整性能优化。

## 相册与调试入口

[MediaRepository](../../../app/src/main/java/cn/darrenyou/doctorcam/core/media/MediaRepository.kt)在 IO 上绘制同帧检测结果，以质量 100 的 JPEG 写入系统 Pictures 相册，返回真实 URI 或失败。Android 10 及以上用 MediaStore 的待完成状态保护未写完的图片；Android 8 / 9 的存储权限在进入预览前申请，避免保存时的授权对话框暂停相机并丢失当前帧。若该权限失效，相机页提示返回设备列表重新授权，不在抓拍时弹出权限对话框。编码后、提交前检查协程取消，尚未提交的 MediaStore 条目或文件会清理；提交成功后的取消属于已完成保存边界。独立输入位图在成功、失败或取消时都回收，不建立图片上传或数据库链路。

[Debug Panel](../../../app/src/main/java/cn/darrenyou/doctorcam/core/ui/components/DebugPanel.kt)由应用组合根挂载在全局最高层，默认展开、可拖动和折叠，并约束到当前安全区域。[TestPage](../../../app/src/main/java/cn/darrenyou/doctorcam/feature/test/TestPage.kt)通过真实类型安全路由进入，展示构建与设备信息，提供返回业务页面的按钮；面板在该页仍可交互。唯一 `_debug` 字面量与 Release 门禁由[开发说明](../../operations/android-app/development.md)定义。

## 验证边界

Compose instrumentation 验证无设备空态、Activity 重建、隐藏 ONNX 入口、面板触摸、真实横屏安全区与 TestPage 导航，并在结束前恢复 Activity 原方向请求；Native 回归验证库加载、NV21 色序和借用 Buffer、真实模型执行与释放。Kotlin 推理引擎另用实际 assets 验证迁移后的消费者。相册回归实际写入并读回 JPEG，只删除本次生成的 URI，不把独立存储回归当作 UVC 抓拍验收。

实际 USB 权限、预览、抓拍、持续 Paddle 帧推理、切页、后台和拔插仍需要真实 UVC 摄像头。模型与合成位图不提供医学效果证据。构建、安装、运行与正式发布的边界见[开发与验证说明](../../operations/android-app/development.md)。
