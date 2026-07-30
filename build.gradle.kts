// Gradle 8.10.2 / Kotlin 2.2.0 / JVM 22 are pinned to match Mechanica, which consumes this as a
// composite build (`includeBuild` in Mechanica's settings.gradle.kts). Three things have to agree
// or that build cannot work: the including build's Gradle version runs this one, a JVM 23 class
// file cannot be read by a JVM 22 compilation, and Kotlin refuses metadata newer than the
// compiler reading it. Raise these together with Mechanica's, not on their own.
plugins {
    kotlin("jvm") version "2.2.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(22)
}

tasks.test {
    useJUnitPlatform()
}