// Deliberately no plugins block.
//
// Declaring one plugin here with `apply false` puts its jar on the root classpath, which every
// subproject inherits. :app then asks for org.jetbrains.kotlin.android - a different plugin id living
// in the same Kotlin Gradle Plugin jar - and Gradle finds that id already on the classpath without a
// version it can check, which fails the build before a single file is compiled.
//
// The obvious alternative, declaring every plugin here with `apply false`, would make the root project
// resolve the Android Gradle Plugin even when :app is excluded - and that would break the property
// this project is built around: that the detection engine compiles and tests with nothing but a JDK,
// no Android SDK and no access to Google's Maven repository.
//
// So each module declares the plugins it needs, with the version coming from gradle/libs.versions.toml
// in both cases, which keeps them pinned in one place regardless.
