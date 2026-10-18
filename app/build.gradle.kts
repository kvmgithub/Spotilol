import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

    defaultConfig {
        applicationId = providers.gradleProperty("forkApplicationId").orElse("com.project.lol.fork").get()
        minSdk = 28
        targetSdk = 36
        versionCode = providers.gradleProperty("forkVersionCode").orElse("1003").get().toInt()
        versionName = providers.gradleProperty("forkVersionName").orElse("1.2.1003").get()
        ndk {
            abiFilters += providers.gradleProperty("forkAbis").orElse("arm64-v8a").get().split(",")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // signing from env vars first (KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD),
            // falling back to keystore/keystore.properties
            val envStoreFile = System.getenv("APK_KEYSTORE") ?: System.getenv("KEYSTORE_FILE")
            val envStorePass = System.getenv("APK_KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            val envAlias = System.getenv("APK_KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
            val envKeyPass = System.getenv("APK_KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
            if (envStoreFile != null && envStorePass != null && envAlias != null && envKeyPass != null) {
                storeFile = file(envStoreFile)
                storePassword = envStorePass
                keyAlias = envAlias
                keyPassword = envKeyPass
            } else {
                storeFile = rootProject.file("keystore/${keystoreProperties.getProperty("storeFile")}")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
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
    implementation(libs.security.crypto)
    implementation(libs.okhttp)

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

    // Core library desugaring (java.time in Logger)
    coreLibraryDesugaring(libs.desugaring)
}
