pluginManagement {
    repositories {
        if (!providers.gradleProperty("eyecad.skipAndroid").isPresent) google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "2.1.21"
        kotlin("android") version "2.1.21"
        id("com.android.application") version "8.7.3"
    }
}

dependencyResolutionManagement {
    repositories {
        if (!providers.gradleProperty("eyecad.skipAndroid").isPresent) google()
        mavenCentral()
    }
}

rootProject.name = "camcad"

include(":shared")
// -Peyecad.skipAndroid builds and tests the shared module without the Android SDK.
if (!providers.gradleProperty("eyecad.skipAndroid").isPresent) {
    include(":androidApp")
}
