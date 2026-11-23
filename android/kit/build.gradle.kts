// Everything that is not UI: the Scaleway client, the demo, and the logic the
// screens use. Plain Kotlin, so it builds and tests on any JVM (`./gradlew :kit:test`).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
