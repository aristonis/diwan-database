pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "diwan-database"

include("core", "sqlite", "pgsql", "eloquent")

// The Android driver compiles against android.jar from an Android SDK; without one it is left out.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
if (androidSdk != null && file("$androidSdk/platforms").isDirectory) {
    include("android")
} else {
    logger.lifecycle("Android SDK not found (ANDROID_HOME); the android module is not built")
}
