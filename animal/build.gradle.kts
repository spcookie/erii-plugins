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
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "uesugi.plugin.animal.gif.AnimalGifDemo"
}

