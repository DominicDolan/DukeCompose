plugins {
    kotlin("jvm")
}

group = "com.dubulduke"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // `api`: every utility here is an extension on `LayoutScope` or `ElementScope`, so those types
    // are in this module's own public signatures and anything using a utility needs :dsl anyway.
    api(project(":dsl"))

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(22)
}

tasks.test {
    useJUnitPlatform()
    // Forwarded so `./gradlew test -Dduke.nodeNames=false` exercises a release-shaped build. The
    // suite must pass either way: node names are diagnostics, and a test that needs one to hold is
    // asserting on a message rather than on behaviour.
    System.getProperty("duke.nodeNames")?.let { systemProperty("duke.nodeNames", it) }
}
