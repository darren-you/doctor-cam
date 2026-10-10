## DoctorCam 项目规则

- 本仓是个人 Android 摄像头与端侧视觉实验项目；Android 工程根为仓根，应用模块为 `app`，真实入口见 README 与 `docs/operations/android-app/development.md`。
- 按真实代码区分普通 UVC 预览、Paddle Lite 默认检测入口与当前未从设备列表开放的 ONNX 实验实现；不把模型文件、论文或架构图当作准确率、性能、临床或实机验收证据。
- 保持当前安装身份 `cn.darrenyou.doctorcam` 的业务语义。仓库命名或文档纳管不授权更换应用身份、模型或 Native 依赖；需要调整时单独完成构建与设备验证。
- 本仓当前没有 Core 子模块、Fast Deploy Job 或正式发布配置。缺失入口据实报告，不为通过结构检查添加空发布脚本、伪造版本接线或把本地 Debug APK 当作正式制品。
- 独立开发使用本仓 Gradle Wrapper；不隐式读取相邻工作区源码。修改 Native 装配前核对 `settings.gradle`、`app/build.gradle`、`app/src/main/cpp/CMakeLists.txt` 与真实 SDK 目录，不能只修一处路径就声称构建完成。
- 项目设计正文保存在 `docs/design/android-app/`，开发与发布说明保存在 `docs/operations/android-app/`。`docs/paper/`、`docs/puml/` 和 `docs/mcp/` 是历史研究或工具资料，不能覆盖当前源码与工程文档。
