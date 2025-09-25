import org.gradle.api.initialization.resolve.RepositoriesMode

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
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
    }
}

rootProject.name = "Orbot"
include(
    ":app",
    ":OrbotLib",
    ":orbotservice",
    ":Meshrabiya:lib-meshrabiya",
    ":meshrabiya-api"
    ,
    ":abhaya-sensor-android"
)

// Wire the sensor subproject's app module into the root build
include(":abhaya-sensor-android:app")
project(":abhaya-sensor-android:app").projectDir = file("abhaya-sensor-android/app")
