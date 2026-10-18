// Spotify implementation of :api. Plain JVM: it talks to the Web Player page through a
// BridgeHost (Android WebView or desktop Chrome) and to Spotify's HTTP API directly.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":api"))
    api(libs.serialization.json)
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
