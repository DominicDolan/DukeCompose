plugins {
    kotlin("multiplatform")
}

group = "com.dubulduke"
version = "1.0-SNAPSHOT"
repositories { mavenCentral() }

kotlin {
    jvmToolchain(22)
    jvm()
    linuxX64()
    sourceSets {
        commonMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies { api(project(":reactivity")) }
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(kotlin("test"))

            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    maxHeapSize = "2g"
    System.getProperty("duke.nodeNames")?.let { systemProperty("duke.nodeNames", it) }
}

tasks.register("test") { dependsOn("jvmTest") }
