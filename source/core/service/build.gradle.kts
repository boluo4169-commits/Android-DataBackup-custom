plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.protobuf)
    alias(libs.plugins.library.test)
    alias(libs.plugins.refine)
}

android {
    namespace = "com.xayah.core.service"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:util"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:rootservice"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    compileOnly(project(":core:hiddenapi"))

    // Gson
    implementation(libs.gson)

    // libsu：core:util 的 BaseUtil.execute 公开签名含 Shell 类型，编译期需在 classpath
    implementation(libs.libsu.core)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)
}