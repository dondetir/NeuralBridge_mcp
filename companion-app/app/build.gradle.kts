plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "com.neuralbridge.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neuralbridge.companion"
        minSdk = 24  // Android 7.0 - required for AccessibilityService.dispatchGesture()
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Specify ABIs to build for
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                // CMake arguments
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-24"
                )
                // C++ flags
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Kotlin source sets
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    // Kotlin (updated for Kotlin 2.0.21 compatibility)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")

    // Protobuf
    implementation("com.google.protobuf:protobuf-kotlin:3.24.0")
    implementation("com.google.protobuf:protobuf-java:3.24.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Protobuf configuration
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.24.0"
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

// Copy proto files from mcp-server
tasks.register<Copy>("copyProtoFiles") {
    from("../../mcp-server/proto")
    into("src/main/proto")
    include("*.proto")
}

// Make proto generation depend on copying proto files
tasks.configureEach {
    if (name.startsWith("generate") && name.endsWith("Proto")) {
        dependsOn("copyProtoFiles")
    }
}
