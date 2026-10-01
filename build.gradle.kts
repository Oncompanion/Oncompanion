// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.googleServices) apply false
}

// Lock dependency versions so every build (local and CI) resolves the same artifacts.
// After changing a dependency, run `./gradlew :app:dependencies --write-locks` and commit the lock files.
allprojects { dependencyLocking { lockAllConfigurations() } }
