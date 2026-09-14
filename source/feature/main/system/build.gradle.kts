plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.compose)
}

android {
    namespace = "com.xayah.feature.main.system"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:util"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    compileOnly(project(":core:hiddenapi"))
    implementation(project(":core:rootservice"))

    // libsu（BaseUtil.execute 的签名暴露了 Shell 类型，调用方需要能解析）
    implementation(libs.libsu.core)

    // Hilt navigation
    implementation(libs.androidx.hilt.navigation.compose)
}
