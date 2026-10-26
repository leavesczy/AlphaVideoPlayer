package github.leavesczy.alphavideoplayer

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun Project.configureAndroidApplication(applicationExtension: ApplicationExtension) {
    applicationExtension.apply {
        defaultConfig {
            applicationId = "github.leavesczy.alphavideoplayer.sample"
            targetSdk {
                version = release(version = androidTargetSdkVersion())
            }
            versionCode = 1
            versionName = "1.0.0"
        }
        val basePluginExtension = project.extensions.getByType(BasePluginExtension::class.java)
        basePluginExtension.apply {
            archivesName.set("AlphaVideoPlayer_v${defaultConfig.versionName}_${defaultConfig.versionCode}_${getApkBuildTime()}")
        }
        signingConfigs {
            create("release") {
                storeFile = File(rootDir, "key.jks")
                keyAlias = "leavesczy"
                keyPassword = "123456"
                storePassword = "123456"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        buildTypes {
            debug {
                signingConfig = signingConfigs.getByName("release")
                isMinifyEnabled = false
                isShrinkResources = false
                isDebuggable = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            release {
                signingConfig = signingConfigs.getByName("release")
                isMinifyEnabled = true
                isShrinkResources = true
                isDebuggable = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }
        packaging {
            jniLibs {
                excludes += setOf(element = "META-INF/{AL2.0,LGPL2.1}")
            }
            resources {
                excludes += setOf(
                    "**/*.md",
                    "**/*.version",
                    "**/*.properties",
                    "**/*.kotlin_module",
                    "**/CHANGES",
                    "**/LICENSE.txt",
                    "**/{AL2.0,LGPL2.1}",
                    "**/DebugProbesKt.bin",
                    "**/app-metadata.properties",
                    "**/kotlin-tooling-metadata.json",
                    "**/version-control-info.textproto",
                    "**/androidsupportmultidexversion.txt"
                )
            }
        }
    }
}

private fun getApkBuildTime(): String {
    val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    return now.format(formatter)
}
