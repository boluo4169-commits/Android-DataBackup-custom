plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.compose)
}

android {
    namespace = "com.xayah.feature.main.dashboard"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:datastore"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:util"))
    implementation(project(":core:work"))
    implementation(project(":core:network"))
    implementation(project(":core:rootservice"))

    // 一键备份/恢复复用系统数据（短信/通话/WiFi）的云端编排，无环依赖
    implementation(project(":feature:main:system"))

    // Compose Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Animation graphics (animated vector)
    implementation(libs.androidx.animation.graphics)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)

    // libsu
    implementation(libs.libsu.core)
}