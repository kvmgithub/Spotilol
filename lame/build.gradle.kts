plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.qiujuer.lame"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28

        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++11", "-O3")
                cFlags += listOf(
                    "-O3",
                    "-ffast-math",
                    "-funroll-loops",
                    "-Wno-error=implicit-function-declaration"
                )
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
