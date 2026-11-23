pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ScalewayControlRoom"

// kit: everything that is not UI, plain Kotlin (the Android twin of
// ios/Packages/ControlRoomKit). app: the Compose app.
include(":kit", ":app")
