import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

private fun Project.configureCommon() {
    pluginManager.apply("com.android.application")
    pluginManager.apply("org.jetbrains.kotlin.android")

    extensions.getByType<ApplicationExtension>().apply {
        signingConfigs {
            create("release") {
                // 优先从本地 keystore.properties 读签名信息（该文件不进 git，密钥保留本地）；
                // 没有该文件时回退到环境变量（CI 场景）。
                val props = java.util.Properties()
                val propsFile = rootProject.file("keystore.properties")
                if (propsFile.exists()) {
                    propsFile.inputStream().use { props.load(it) }
                }
                fun prop(key: String): String = props.getProperty(key) ?: System.getenv(key) ?: ""
                storeFile = file(prop("STORE_FILE").ifEmpty { "placeholder" })
                storePassword = prop("STORE_PASSWORD")
                keyAlias = prop("KEY_ALIAS")
                keyPassword = prop("KEY_PASSWORD")
            }
        }

        buildTypes {
            release {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                buildConfigField("Boolean", "ENABLE_VERBOSE", "false")
                signingConfig = signingConfigs.getByName("release")
            }
            debug {
                isMinifyEnabled = false
                isShrinkResources = false
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                buildConfigField("Boolean", "ENABLE_VERBOSE", "false")
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        buildFeatures {
            buildConfig = true
        }

        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
                excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            }
        }

        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xcontext-receivers")
            }
        }
    }
}

class ApplicationCommonConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            configureCommon()
        }
    }
}
