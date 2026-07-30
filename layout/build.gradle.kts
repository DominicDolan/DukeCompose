plugins {
    kotlin("jvm")
}

group = "com.dubulduke"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // `api`, not `implementation`: Slot, Graph and Computation appear in this module's own
    // public signatures, so anything depending on :layout needs them on its compile classpath.
    api(project(":reactivity"))

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(22)
}

tasks.test {
    useJUnitPlatform()
    // ScaleTest holds two 40k-element scenes at once (~270k graph nodes each) so that a full
    // re-layout can be timed on a fresh graph more than once and the best run taken.
    maxHeapSize = "2g"
}
