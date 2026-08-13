plugins {
    alias(libs.plugins.library.common)
}

android {
    namespace = "com.xayah.libnative"
    ndkVersion = "29.0.14206865" // NDK r28+ 默认 16 KB 页对齐（Android 16 兼容）

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
}