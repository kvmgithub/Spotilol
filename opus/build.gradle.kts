plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.project.lol.opus"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28

        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
                cFlags += listOf("-O3", "-funroll-loops")
            }
        }

        ndk {
            abiFilters += providers.gradleProperty("forkAbis").orElse("arm64-v8a,armeabi-v7a").get().split(",")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
