// Top-level build file for NeuralBridge Companion App

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Upgraded for Java 21 compatibility
        classpath("com.android.tools.build:gradle:8.13.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.0.21")

    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
