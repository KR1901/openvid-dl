// Top-level build file.
//
// AGP 9 has built-in Kotlin support, so the `org.jetbrains.kotlin.android`
// plugin is no longer applied (doing so is now an error). We still pin the
// Kotlin Gradle plugin version on the buildscript classpath so it matches the
// Compose compiler plugin version below.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
