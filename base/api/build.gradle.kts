// The Myspoti API contract: models and interfaces only, no Android, no Spotify details.
// The UI depends on this module alone.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.coroutines.core)
}
