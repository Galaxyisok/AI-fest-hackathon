plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

// Optional: keep build output out of a synced folder (OneDrive/Dropbox lock files mid-build).
// Set scamguard.buildRoot=C:/some/dir in ~/.gradle/gradle.properties. Unset = normal ./build dirs.
val buildRoot = providers.gradleProperty("scamguard.buildRoot").orNull
if (buildRoot != null) {
    allprojects { layout.buildDirectory.set(file("$buildRoot/${project.name}")) }
}
