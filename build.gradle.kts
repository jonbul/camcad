buildscript {
    // AGP must share the root classloader with the Kotlin plugin, or kotlin("android") can't see AGP's classes.
    // -Peyecad.skipAndroid leaves it out so the shared module builds without Google's Maven repository.
    if (!project.hasProperty("eyecad.skipAndroid")) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}

plugins {
    // Loaded once here so all modules share the same Kotlin plugin classes.
    kotlin("multiplatform") apply false
    kotlin("android") apply false
}
