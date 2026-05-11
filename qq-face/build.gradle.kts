plugins {
    id("uesugi.erii-plugin")
}

version = "0.0.1"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-XXLanguage:+UnnamedLocalVariables")
    }
}
