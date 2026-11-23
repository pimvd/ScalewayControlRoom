pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: Central answers CI's bursts
        // of downloads with 429 Too Many Requests.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first: Central answers CI's bursts
        // of downloads with 429 Too Many Requests.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "ScalewayControlRoom"

// kit: everything that is not UI, plain Kotlin (the Android twin of
// ios/Packages/ControlRoomKit). app: the Compose app.
include(":kit", ":app")
