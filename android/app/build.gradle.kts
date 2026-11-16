plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.iqtvlite.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.iqtvlite.app"
        // Android TV 盒子多为 API 26+；Slint 的 NDK floor 是 23，取 26 兼顾 TV
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-test"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // AGP 自动提供 debug signing config（debug.keystore），测试 APK 直接用它

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Rust .so 由 CI 用 cargo-ndk 构建后放到这个目录
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // 允许 Rust 生成的 .so 打进包
    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // ── Media3 1.11.1 —— 直播主引擎 ──────────────────────────────────
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")

    // ── OkHttp —— 保温连接池（换台速度关键） ─────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── AndroidX ──────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")

    // 频道 logo 加载（轻量，比 Glide/Picasso 更适合）
    implementation("io.coil-kt:coil:2.6.0")
}
