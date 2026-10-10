# DoctorCam Android 应用模块

本目录由仓根 Gradle 工程装配，不是独立 Gradle 根。构建从仓根执行，工具链、现有失败和发布边界统一见[开发与验证说明](../docs/operations/android-app/development.md)。

| 路径 | 职责 |
| --- | --- |
| `src/main/java/cn/darrenyou/doctorcam/` | Activity、Fragment、UVC 设备管理、Java 推理及图像工具 |
| `src/main/cpp/` | Paddle Lite JNI 接口及 C++ 预处理、推理、后处理 |
| `src/main/assets/` | ONNX 模型、Paddle 模型与标签 |
| `src/main/res/` | ViewBinding 布局、导航和 Android 资源 |
| `OpenCV/sdk/` | 仓内现有 OpenCV Java / Native SDK；Gradle 接入路径待修正 |
| `PaddleLite/cxx/` | 仓内现有 Paddle Lite 头文件与预编译库 |
| `src/test/`、`src/androidTest/` | 现有模板测试，未覆盖摄像头和推理链路 |

普通预览和 Paddle 检测入口位于设备列表页；ONNX 页面存在源码与导航声明，但当前列表跳转被注释。[架构说明](../docs/design/android-app/architecture.md)给出各链路的真实实现边界。

工程标准入口：[Android 工程标准](https://github.com/darren-you/darren-space/blob/master/harness/docs/workspace/standards/android-app/android-app-golden-path.md)。当前保留既有应用身份、Java / XML 界面与 Native 装配，没有新增发布脚本或伪造标准检查通过结果。
