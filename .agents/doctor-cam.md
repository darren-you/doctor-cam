## DoctorCam 项目规则

- 本仓是个人 Android 摄像头与端侧视觉实验项目；Android 工程根为仓根，应用模块为 `app`，真实入口见 README 与 `docs/operations/android-app/development.md`。
- 按真实代码区分普通 UVC 预览、Paddle Lite 默认检测入口与当前未从设备列表开放的 ONNX 实验实现；不把模型文件、论文或架构图当作准确率、性能、临床或实机验收证据。
- 保持当前安装身份 `cn.darrenyou.doctorcam` 的业务语义。仓库命名或文档纳管不授权更换应用身份、模型或 Native 依赖；需要调整时单独完成构建与设备验证。
- 本仓使用 Kotlin / Compose 单 Activity、类型安全 Navigation、Hilt ViewModel 与后台 UVC 控制器。仓内精确 Core 子模块只消费统一版本解析；当前没有 Fast Deploy Job 或正式发布配置，缺失入口据实报告，不为结构检查添加空发布脚本或把本地 Debug APK 当作正式制品。
- 独立开发使用本仓 Gradle Wrapper；不隐式读取相邻工作区源码。AGP 使用内置 Kotlin，KGP 与 Compose / Serialization 编译插件版本统一，禁止恢复旧 `kotlin-android` 插件或以 opt-out 绕过迁移。修改 Native 装配前核对 `settings.gradle.kts`、`app/build.gradle.kts`、`app/src/main/cpp/CMakeLists.txt` 及官方 Maven / Prefab 与本仓 `tool/prepare_paddle_lite.py` 声明的固定 SDK 制品，不能只修一处路径就声称构建完成。旧仓内 SDK 已移除，禁止通过新目录或旧制品回退重建平行装配；依赖更新后验证原模型实际执行与 Native 版本。
- Native 依赖变更后运行 `:app:verifyDebugNativeAlignment`，核对实际 APK 的全库 ELF、RELRO 保护隔离与 ZIP 对齐，并检查本地 AAB 的 `PAGE_ALIGNMENT_16K`。页大小结论还须有关闭 backcompat 的真实 16 KiB Android 回归，不能用 4 KiB 设备或仅静态对齐替代；原模型和 UVC 硬件功能分别验收。
- 项目设计正文保存在 `docs/design/android-app/`，开发与发布说明保存在 `docs/operations/android-app/`。`docs/paper/`、`docs/puml/` 和 `docs/mcp/` 是历史研究或工具资料，不能覆盖当前源码与工程文档。
