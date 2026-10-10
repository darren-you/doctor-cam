import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val releaseDebugSourcePath = "app/src/main/java/cn/darrenyou/doctorcam/DoctorCamApp.kt"
val releaseDebugCheckerPath = "tool/CheckReleaseDebug.java"
val javaExecutableName = if (System.getProperty("os.name").lowercase().contains("windows")) {
    "java.exe"
} else {
    "java"
}
val javaExecutable = file("${System.getProperty("java.home")}/bin/$javaExecutableName")

val verifyReleaseDebugDisabled by tasks.registering(Exec::class) {
    group = "verification"
    description = "检查 Release 构建的 _debug 开关"
    workingDir(rootProject.projectDir)
    executable(javaExecutable)
    args(releaseDebugCheckerPath, "--source", releaseDebugSourcePath, "--display", releaseDebugSourcePath)
    inputs.file(rootProject.file(releaseDebugCheckerPath))
    inputs.file(rootProject.file(releaseDebugSourcePath))
}

val releaseDebugFixturePath = providers.gradleProperty("releaseDebugFixture")
    .orElse("app/src/testFixtures/release-debug-false.kt").get()
val verifyReleaseDebugFixture by tasks.registering(Exec::class) {
    group = "verification"
    description = "使用 fixture 验证 Release _debug 检查器"
    workingDir(rootProject.projectDir)
    executable(javaExecutable)
    args(releaseDebugCheckerPath, "--source", releaseDebugFixturePath, "--display", releaseDebugFixturePath)
    inputs.file(rootProject.file(releaseDebugCheckerPath))
    inputs.file(rootProject.file(releaseDebugFixturePath))
}

val paddleLiteSdkDirectory = layout.buildDirectory.dir("native-sdk/paddle-lite")
val paddleLiteSdkPath = paddleLiteSdkDirectory.get().asFile.absolutePath
val paddleLitePreparationScript = rootProject.file("tool/prepare_paddle_lite.py")
val preparePaddleLiteSdk by tasks.registering(Exec::class) {
    group = "build setup"
    description = "下载并校验固定版本的官方 Paddle Lite ARM64 SDK"
    commandLine("python3", paddleLitePreparationScript, "--output-dir", paddleLiteSdkPath)
    inputs.file(paddleLitePreparationScript)
    outputs.files(
        paddleLiteSdkDirectory.map { it.file("include/paddle_api.h") },
        paddleLiteSdkDirectory.map { it.file("include/paddle_place.h") },
        paddleLiteSdkDirectory.map { it.file("lib/arm64-v8a/libpaddle_api_light_bundled.a") },
    )
}
tasks.matching {
    it.name == "preBuild" || it.name.startsWith("configureCMake")
}.configureEach { dependsOn(preparePaddleLiteSdk) }

val nativeAlignmentChecker = rootProject.file("tool/check_native_alignment.py")
val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
val debugNativeAlignmentReport = layout.buildDirectory.file("reports/native-alignment-debug.json")
val verifyDebugNativeAlignment by tasks.registering(Exec::class) {
    group = "verification"
    description = "检查 Debug APK 全部 Native 库的 16 KiB ELF 与 ZIP 对齐"
    dependsOn("assembleDebug")
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", nativeAlignmentChecker, debugApk.get().asFile,
        "--report", debugNativeAlignmentReport.get().asFile,
    )
    inputs.file(nativeAlignmentChecker)
    inputs.file(debugApk)
    outputs.file(debugNativeAlignmentReport)
}

val clientVersionTool = rootProject.file("fast-deploy-core/shared/client_version.py")
val clientRunId = providers.environmentVariable("FAST_DEPLOY_RUN_ID").orElse("").get()
val clientSnapshotPath = providers.environmentVariable("FAST_DEPLOY_CLIENT_VERSION_SNAPSHOT_FILE").orNull
val clientVersionPending = clientRunId.isNotEmpty() && clientSnapshotPath == null
val clientVersionInput: Map<*, *> = if (clientVersionPending) {
    val version = providers.exec {
        commandLine("python3", clientVersionTool, "read", "--project-root", rootProject.projectDir)
    }.standardOutput.asText.get().trim()
    mapOf("version" to version)
} else {
    val arguments = mutableListOf<Any>(
        "python3", clientVersionTool, "resolve-build-input", "--platform", "android",
        "--project-root", rootProject.projectDir,
    )
    clientSnapshotPath?.let { arguments.addAll(listOf("--snapshot-file", it)) }
    val result = providers.exec { commandLine(arguments) }.standardOutput.asText.get()
    groovy.json.JsonSlurper().parseText(result) as Map<*, *>
}
val clientVersion = clientVersionInput["version"] as String
// 正式编号未分配时只允许前置检查与准备任务，不能产包。
val clientVersionCode = (clientVersionInput["version_code"] as? Number)?.toInt() ?: 1
val clientLocalValidation = clientRunId.isEmpty()
if (clientVersionPending) {
    gradle.taskGraph.whenReady {
        require(allTasks.all {
            it.name.startsWith("prepare") && it.name.endsWith("ClientVersion") ||
                it.name == "verifyReleaseDebugDisabled" || it.name == "dependencies"
        }) {
            "正式 Android 构建必须先分配 Panel 编号快照"
        }
    }
}

android {
    namespace = "cn.darrenyou.doctorcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.darrenyou.doctorcam"
        minSdk = 26
        targetSdk = 36
        versionCode = clientVersionCode
        versionName = clientVersion
        manifestPlaceholders["clientLocalValidation"] = clientLocalValidation.toString()
        manifestPlaceholders["clientRunId"] = clientRunId
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DPADDLE_LITE_SDK_DIR=$paddleLiteSdkPath",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // AGP 8.13 先合并本项目 Native 输出，保留 NDK 30 的唯一共享 C++ 运行库。
        jniLibs.pickFirsts += "lib/arm64-v8a/libc++_shared.so"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.opencv)
    implementation(libs.uvc.camera)
    implementation(libs.onnx.runtime)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercaseChar() }
        val clientVersionMatches = clientLocalValidation || (!clientVersionPending && run {
            val buildInputs = clientVersionInput["build_inputs"] as Map<*, *>
            clientVersionInput["application_id"] == variant.applicationId.get() &&
                buildInputs["variant"] == variant.name &&
                buildInputs["architectures"] == android.defaultConfig.ndk.abiFilters.sorted()
        })
        if (variant.buildType == "release") {
            val output = providers.environmentVariable("FAST_DEPLOY_CLIENT_VERSION_PREPARATION_FILE").orNull
            val arguments = mutableListOf<Any>(
                "python3", clientVersionTool, "prepare", "--project-root", rootProject.projectDir,
                "--platform", "android", "--application-id", variant.applicationId.get(),
                "--variant", variant.name, "--output", output.orEmpty(),
            )
            android.defaultConfig.ndk.abiFilters.sorted().forEach {
                arguments.addAll(listOf("--architecture", it))
            }
            tasks.register<Exec>("prepare${name}ClientVersion") {
                group = "verification"
                dependsOn(verifyReleaseDebugDisabled)
                commandLine(arguments)
                doFirst {
                    require(!output.isNullOrEmpty()) {
                        "版本准备必须提供 FAST_DEPLOY_CLIENT_VERSION_PREPARATION_FILE"
                    }
                }
            }
        }
        tasks.matching { it.name == "pre${name}Build" }.configureEach {
            if (variant.buildType == "release") dependsOn(verifyReleaseDebugDisabled)
            doFirst {
                require(clientVersionMatches) {
                    "Android 实际 variant、applicationId 或 ABI 与编号快照不一致"
                }
            }
        }
    }
}
