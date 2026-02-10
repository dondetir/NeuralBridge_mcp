// Top-level build file for NeuralBridge Test Harness App

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
