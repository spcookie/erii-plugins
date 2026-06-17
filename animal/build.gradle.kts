plugins {
    id("uesugi.erii-plugin")
}

version = "1.0.0"

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("uesugi:erii-spi-core:1.0.0")
    testImplementation("uesugi:erii-spi-annotation:1.0.0")
    testImplementation("uesugi:erii-common:1.0.0")
    testImplementation("org.pf4j:pf4j:3.15.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("io.github.oshai:kotlin-logging-jvm:8.0.02")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

dependencies {
    compileOnly("com.microsoft.playwright:playwright:1.57.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.20")
}

tasks.test {
    classpath += sourceSets["main"].compileClasspath
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "true")
}

