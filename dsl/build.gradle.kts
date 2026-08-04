plugins {
    kotlin("jvm")
}

group = "com.dubulduke"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // `api`: Element, Graph and the geometry reads appear in this module's own public
    // signatures, so anything writing a UI needs them on its compile classpath.
    api(project(":layout"))

    testImplementation(kotlin("test"))
    // The utilities are *downstream* of this module — `:utils` is where `fill()` and `below()` live
    // now — and the tests here use them for the geometry boilerplate every scene needs. Gradle
    // resolves dsl:test → utils → dsl:main without a cycle, since nothing in `:utils` is on the
    // main compile classpath. Production code in this module must not reach for them.
    testImplementation(project(":utils"))
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
