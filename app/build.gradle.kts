plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.line"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "app.line"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll", "libsignal_jni*.so")
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    signingConfigs {
        val keystore = System.getenv("LINE_KEYSTORE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("LINE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LINE_KEY_ALIAS") ?: "line"
                keyPassword = System.getenv("LINE_KEY_PASSWORD") ?: System.getenv("LINE_KEYSTORE_PASSWORD")
            }
        }
    }
    androidResources { localeFilters += listOf("ru", "en", "kk") }
    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

// UnifiedPush brings Tink; LiveKit already ships protobuf-lite, so use Tink's Android flavour to avoid duplicate classes.
configurations.configureEach {
    val tink = "com.google.crypto.tink:tink-android:1.20.0"
    resolutionStrategy {
        force(tink)
        dependencySubstitution { substitute(module("com.google.crypto.tink:tink")).using(module(tink)) }
    }
}

dependencies {
    implementation("org.unifiedpush.android:connector:3.0.10")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("io.livekit:livekit-android:2.29.0")
    implementation("org.signal:libsignal-client:0.104.0")
    implementation("org.signal:libsignal-android:0.104.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
