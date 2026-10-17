import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
}

group = "com.eyecad"
version = "1.0.0"

kotlin {
    // The Android app consumes the JVM target; it has no Android-specific code, so no Android plugin is needed here.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    val xcf = XCFramework("Shared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test>().configureEach {
    // Tests write sample exports here so they can be inspected and committed.
    systemProperty("eyecad.samples", rootProject.layout.projectDirectory.dir("samples").asFile.absolutePath)
}
