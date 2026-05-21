plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.parental.focus"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.parental.focus"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        viewBinding = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Exclude conflicting TFLite native libraries from duplicate merging
        jniLibs { pickFirsts += listOf("**/*.so") }
    }

    // Allow TFLite .tflite assets to be uncompressed for faster load
    aaptOptions {
        noCompress("tflite")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.13.0")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3")

    // ML Kit Face Detection — used for bounding-box detection + face crop
    // (bundled variant: no Play Services dependency)
    implementation("com.google.mlkit:face-detection:16.1.6")

    // TFLite — runs the FaceNet embedding model for real face identity recognition
    // Core interpreter
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    // Support library: image pre-processing, tensor buffers
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // GPU delegate (optional, falls back to CPU automatically)
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // Google Play Services Tasks — Tasks.await() used in FaceUtils IO coroutine
    implementation("com.google.android.gms:play-services-tasks:18.1.0")

    // Biometric
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Material (traditional views in overlay / enrollment activities)
    implementation("com.google.android.material:material:1.12.0")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
