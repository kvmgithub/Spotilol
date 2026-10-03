import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}


android {
    namespace = "com.project.lol"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = providers.gradleProperty("forkApplicationId").orElse("com.project.lol").get()
        minSdk = 28
        targetSdk = 36
        versionCode = providers.gradleProperty("forkVersionCode").orElse("18").get().toInt()
        versionName = providers.gradleProperty("forkVersionName").orElse("1.1.8").get()
        ndk {
            abiFilters += providers.gradleProperty("forkAbis").orElse("arm64-v8a,armeabi-v7a").get().split(",")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = System.getenv("APK_KEYSTORE")?.let { file(it) } ?: rootProject.file("keystore/${keystoreProperties.getProperty("storeFile")}")
            storePassword = System.getenv("APK_KEY_PASSWORD") ?: keystoreProperties.getProperty("storePassword")
            keyAlias = System.getenv("APK_KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
            keyPassword = System.getenv("APK_KEY_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.bouncyprov)
    implementation(libs.bouncypkix)
    implementation(libs.security.crypto)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.tabler.icons)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Glance (home screen widgets)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Ktor + serialization (YouTube InnerTube client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)

    // NewPipe + YouTube streaming
    implementation(libs.newpipeextractor)
    implementation(libs.brotli)
    implementation(libs.okhttp)

    // Audio downloads: opus decoding, mp3 encoding
    implementation(project(":lame"))
    implementation(project(":opus"))

    // Core library desugaring (required by NewPipeExtractor)
    coreLibraryDesugaring(libs.desugaring)
}