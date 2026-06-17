plugins {
    id("uesugi.erii-plugin")
}

version = "1.0.0"

dependencies {
    compileOnly("com.microsoft.playwright:playwright:1.57.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.20")
}

tasks.test {
    classpath += sourceSets["main"].compileClasspath
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "true")
}

