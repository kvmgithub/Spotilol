import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}


android {
    namespace = "app.myspoti.engine"
    compileSdk = 37

    // MySpotilol: own package next to Spotilol Lite (com.project.lol.fork), same signing key.
    // CI passes -PappVersionName / -PappVersionCode; local builds use the defaults.
    val version = (findProperty("appVersionName") as String?) ?: "0.1.0"
    defaultConfig {
        applicationId = "com.project.lol.fork.ui"
        manifestPlaceholders["appLabel"] = "MySpotilol"
        minSdk = 29
        targetSdk = 36
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = version
        // Inspectable hidden player in a test build (-PwebDebug=true); never in releases.
        buildConfigField("boolean", "WEB_DEBUG", "${(findProperty("webDebug") as String?) == "true"}")
        // In-app updates are removed in this variant; kept empty so no update request is made.
        buildConfigField("String", "UPDATE_REPO", "\"\"")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // On-device tests (codecs): ./gradlew connectedDebugAndroidTest
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Release key from Spotilol's CI bundle (APK_KEYSTORE/APK_KEY_PASSWORD/APK_KEY_ALIAS),
    // else keystore/keystore.properties.
    val envStore = System.getenv("APK_KEYSTORE")
    val hasReleaseKey = envStore != null || keystorePropertiesFile.exists()
    signingConfigs {
        create("release") {
            if (envStore != null) {
                storeFile = file(envStore)
                storePassword = System.getenv("APK_KEY_PASSWORD")
                keyAlias = System.getenv("APK_KEY_ALIAS")
                keyPassword = System.getenv("APK_KEY_PASSWORD")
            } else if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file("keystore/${keystoreProperties.getProperty("storeFile")}")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release apps (own data, own sign-in).
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "MySpotilol dev"
        }
        release {
            // Real key when provided (CI releases); otherwise the debug key, so a local
            // release build installs over the debug one while testing.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            // -PoverDev: the optimised build under the dev app's id, to measure the speed of a
            // beta on the test phone without replacing the signed beta installed there.
            if (findProperty("overDev") != null) {
                applicationIdSuffix = ".dev"
                manifestPlaceholders["appLabel"] = "Myspoti dev"
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Logs stay (redacted diagnostics, as in Spotilol); only this app can read its logcat.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":host-android"))
    testImplementation("junit:junit:4.13.2")
    // The real org.json in unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    // Spotilol's encrypted account profiles (Keystore-backed EncryptedSharedPreferences).
    implementation(libs.security.crypto)


    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Native UI (ported from Spotui, GPL-3.0)
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("com.github.bumptech.glide:compose:1.0.0-beta01")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // MediaTransferReceiver: lets the system output switcher transfer to our Connect routes
    implementation("androidx.mediarouter:mediarouter:1.8.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(libs.serialization.json)
    // Looping Spotify Canvas video on the now-playing screen
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    // Installs the Baseline Profiles shipped by Compose & co. on sideloaded builds
    // (faster start, less jank; Play does this itself for store installs).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(libs.okhttp)
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-database:1.8.0")





}