plugins {
    id("uesugi.erii-plugin")
}

version = "1.0.0"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-XXLanguage:+UnnamedLocalVariables")
    }
}
