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
}

kotlin {
    jvmToolchain(22)
}

tasks.test {
    useJUnitPlatform()
}
