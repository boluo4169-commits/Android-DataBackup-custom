pluginManagement {
    includeBuild("build-logic")
    repositories {
        if (System.getenv("CI") != null) {
            // CI 直连官方源：阿里云镜像对海外 IP 可能返回 502，会中断 Gradle 插件解析
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != null) {
            google()
            mavenCentral()
            maven("https://jitpack.io")
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            google()
            mavenCentral()
            maven("https://jitpack.io")
        }
    }
}

rootProject.name = "DataBackup"
include(":app")
include(":core:common")
include(":core:service")
include(":core:ui")
include(":core:model")
include(":core:database")
include(":core:data")
include(":core:datastore")
include(":core:util")
include(":core:work")
include(":core:hiddenapi")
include(":core:systemapi")
include(":core:rootservice")
include(":core:network")
include(":core:provider")
include(":feature:crash")
include(":feature:setup")
include(":feature:main:dashboard")
include(":feature:main:restore")
include(":feature:main:cloud")
include(":feature:main:settings")
include(":feature:main:configurations")
include(":feature:main:processing")
include(":feature:main:list")
include(":feature:main:details")
include(":feature:main:history")
include(":feature:main:directory")
include(":feature:flavor:foss")
include(":feature:flavor:premium")
include(":feature:flavor:alpha")
include(":native")
