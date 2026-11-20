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
        resValue("string", "app_name", if (providers.gradleProperty("ISOLATED_TEST_PACKAGE").orNull == "true") "IQTV Lite 验证版" else "IQTV Lite")
        versionCode = providers.gradleProperty("APP_VERSION_CODE").orNull?.toInt()
            ?: (10000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1))
        versionName = (providers.gradleProperty("APP_VERSION_NAME").orNull
            ?: "2.2.0-tivimini-${System.getenv("GITHUB_SHA")?.take(8) ?: "local"}") +
            (if (providers.gradleProperty("ISOLATED_TEST_PACKAGE").orNull == "true") "-validation" else "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    val signingFile = System.getenv("ANDROID_SIGNING_KEYSTORE_FILE")
    val signingPassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
    val signingAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
    val stableSigning = listOf(signingFile, signingPassword, signingAlias, signingKeyPassword).all { !it.isNullOrBlank() }
    require(stableSigning || listOf(signingFile, signingPassword, signingAlias, signingKeyPassword).all { it.isNullOrBlank() }) { "Stable signing requires all four ANDROID_SIGNING_* environment values" }
    if (stableSigning) signingConfigs.create("ciStable") {
        storeFile = file(signingFile!!)
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
            manifest.srcFile("src/tivimini/AndroidManifest.xml")
            java.setSrcDirs(listOf("src/tivimini/java", "src/bridge/kotlin"))
            res.setSrcDirs(listOf("src/tivimini/res"))
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
    packaging { jniLibs.useLegacyPackaging = false }
}
kotlin.sourceSets.getByName("main").kotlin.setSrcDirs(listOf("src/tivimini/java", "src/bridge/kotlin"))

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
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
