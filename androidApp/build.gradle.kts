import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    id("com.android.application")
    kotlin("android")
}

// CI passes these; local builds fall back to a debug-signed release.
val releaseKeystore = System.getenv("ANDROID_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.eyecad.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eyecad.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("eyecad.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("eyecad.versionName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "tflite"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.google.ar:core:1.46.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("androidx.core:core-ktx:1.13.1")
}

// EfficientDet-Lite0 (COCO classes) for MediaPipe; downloaded once instead of committed.
val downloadDetectorModel by tasks.registering {
    val out = file("src/main/assets/efficientdet_lite0.tflite")
    outputs.file(out)
    onlyIf { !out.exists() }
    doLast {
        out.parentFile.mkdirs()
        URI("https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite")
            .toURL().openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
    }
}
tasks.named("preBuild") { dependsOn(downloadDetectorModel) }
