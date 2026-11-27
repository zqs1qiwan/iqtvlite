plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "tv.iqtvlite.app"
    compileSdk = 36
    defaultConfig {
        applicationId = if (providers.gradleProperty("ISOLATED_TEST_PACKAGE").orNull == "true") "tv.iqtvlite.app.validation" else "tv.iqtvlite.app"
        minSdk = 24
        targetSdk = 35
        resValue("string", "app_name", if (providers.gradleProperty("ISOLATED_TEST_PACKAGE").orNull == "true") "IQTV Lite Validation" else "IQTV Lite")
        versionCode = providers.gradleProperty("APP_VERSION_CODE").orNull?.toInt() ?: 301
        versionName = (providers.gradleProperty("APP_VERSION_NAME").orNull ?: "0.3.1") +
            (if (providers.gradleProperty("ISOLATED_TEST_PACKAGE").orNull == "true") "-validation" else "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    // ── 正式 / Validation 稳定签名（见 docs/RELEASE_SIGNING.md） ──
    // 首选 VALIDATION_*（见 docs/CI_STABLE_SIGNING.md），兼容旧名 ANDROID_SIGNING_*。
    // 四个值必须同时提供；keystore 文件必须存在。都没配置时回退 Android 自动 debug
    // keystore（本地开发 / 未加 secrets 的 CI 照常构建，只是签名每个 run 都会变）。
    val signingFile = System.getenv("VALIDATION_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SIGNING_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    val signingPassword = System.getenv("VALIDATION_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val signingAlias = System.getenv("VALIDATION_KEY_ALIAS")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val signingKeyPassword = System.getenv("VALIDATION_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    val signingValues = listOf(signingFile, signingPassword, signingAlias, signingKeyPassword)
    require(signingValues.all { it == null } || signingValues.all { it != null }) {
        "Stable validation signing needs all four values together: " +
            "VALIDATION_KEYSTORE_FILE / VALIDATION_KEYSTORE_PASSWORD / VALIDATION_KEY_ALIAS / VALIDATION_KEY_PASSWORD " +
            "(legacy aliases ANDROID_SIGNING_KEYSTORE_FILE / ANDROID_SIGNING_STORE_PASSWORD / " +
            "ANDROID_SIGNING_KEY_ALIAS / ANDROID_SIGNING_KEY_PASSWORD). Missing: " +
            listOf("file", "storePassword", "alias", "keyPassword")
                .filterIndexed { index, _ -> signingValues[index] == null }
                .joinToString(", ")
    }
    val signingKeystore = signingFile?.let { file(it) }
    require(signingKeystore == null || signingKeystore.isFile) {
        "Configured validation keystore does not exist: $signingKeystore"
    }
    // 仅当 keystore 文件确实存在时才启用，避免配置中断导致构建失败或静默换签名。
    val stableSigning = signingKeystore != null && signingKeystore.isFile
    if (stableSigning) signingConfigs.create("ciStable") {
        storeFile = signingKeystore
        storePassword = signingPassword
        keyAlias = signingAlias
        keyPassword = signingKeyPassword
    }
    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (providers.gradleProperty("ISOLATED_TEST_PACKAGE").orNull == "true") {
                proguardFile("instrumentation-shared-keep.pro")
            }
            if (stableSigning) signingConfig = signingConfigs.getByName("ciStable")
        }
        release {
            if (stableSigning) signingConfig = signingConfigs.getByName("ciStable")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("src/main/java", "src/bridge/kotlin"))
            res.setSrcDirs(listOf("src/main/res"))
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
    packaging { jniLibs.useLegacyPackaging = false }
}
kotlin.sourceSets.getByName("main").kotlin.setSrcDirs(listOf("src/main/java", "src/bridge/kotlin"))

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // MP2/MP3/AAC software audio fallback (Media3 FFmpeg audio extension, LGPL; arm64+armv7 only).
    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha12")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
