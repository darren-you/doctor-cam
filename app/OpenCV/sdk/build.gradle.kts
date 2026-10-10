// 原样消费 OpenCV 4.2.0 Java 绑定与仓内预编译 Native SDK。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.opencv"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf("native/libs"))
            java.setSrcDirs(listOf("java/src"))
            aidl.setSrcDirs(listOf("java/src"))
            res.setSrcDirs(listOf("java/res"))
            manifest.srcFile("java/AndroidManifest.xml")
        }
    }
}
