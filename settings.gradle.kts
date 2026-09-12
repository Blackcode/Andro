pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "cascoscan"

// The detection engine is a plain JVM library: it builds and tests anywhere a JDK exists.
include(":detection")

// The Android app needs AGP + the Android SDK. Include it only when an SDK is actually
// reachable, so that `gradle :detection:test` keeps working on a machine (or CI sandbox)
// without the Android toolchain. Force either way with -Pcascoscan.includeApp=true|false.
val sdkFromLocalProperties: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) }.getProperty("sdk.dir") }

val androidSdkPresent = sdkFromLocalProperties != null ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null

val includeApp = (providers.gradleProperty("cascoscan.includeApp").orNull ?: "$androidSdkPresent").toBoolean()

if (includeApp) {
    include(":app")
} else {
    logger.lifecycle("cascoscan: no Android SDK found -> skipping :app. Use -Pcascoscan.includeApp=true to force.")
}
