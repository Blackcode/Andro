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

// The Android app needs AGP and the Android SDK. It is included by default - an IDE that opened this
// project without it would silently show a library and no app, which is a worse first experience than
// a clear "SDK location not found". Opt out with -Pcascoscan.includeApp=false, which is how the
// engine's tests are run on a machine with no Android toolchain at all (CI, a bare container).
val includeApp = (providers.gradleProperty("cascoscan.includeApp").orNull ?: "true").toBoolean()

if (includeApp) {
    include(":app")
} else {
    logger.lifecycle("cascoscan: :app excluded; building the detection engine only.")
}
