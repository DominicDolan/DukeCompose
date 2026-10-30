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
            dependencies { api(project(":layout")) }
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":utils"))
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    System.getProperty("duke.nodeNames")?.let { systemProperty("duke.nodeNames", it) }
}

tasks.register("test") { dependsOn("jvmTest") }
