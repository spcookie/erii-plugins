plugins {
    id("uesugi.erii-plugin")
}

version = "1.0.0"

dependencies {
    compileOnly("com.microsoft.playwright:playwright:1.57.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.20")
}

tasks.register<JavaExec>("runGifDemo") {
    group = "application"
    description = "Run the animal farm GIF demo"
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("uesugi.plugin.animal.gif.AnimalGifDemoKt")
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "true")
}

tasks.test {
    classpath += sourceSets["main"].compileClasspath
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "true")
}

