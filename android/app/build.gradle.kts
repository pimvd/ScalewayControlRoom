plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Google Play Billing needs the app on Google Play. Until then the app sells
// Pro from a local test store (MockProStore), as the iPhone app does from
// Xcode's StoreKit configuration. Build with -PplayBilling=true to use Play.
val playBilling = providers.gradleProperty("playBilling").orNull == "true"

android {
    namespace = "com.pvd.scwcontrolroom"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pvd.scwcontrolroom"
        minSdk = 28
        targetSdk = 37
        // The build number is the git commit count, passed by the release
        // workflow, as on iOS; the version matches the iPhone app's.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = "1.0"
        buildConfigField("boolean", "PLAY_BILLING", playBilling.toString())
    }

    signingConfigs {
        // The upload key, from the release workflow's secrets. Without it a
        // release build is unsigned.
        val keystore = providers.environmentVariable("UPLOAD_KEYSTORE").orNull
        if (keystore != null) {
            create("upload") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("UPLOAD_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("UPLOAD_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("UPLOAD_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric downloads its Android jar when the tests run: from the
        // same mirror as Gradle (see settings.gradle.kts).
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":kit"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.billing)
    implementation(libs.review)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
