plugins {
    id("uesugi.erii-plugin") version "1.0.0"
}

dependencies {
    compileOnly("uesugi:erii-spi-annotation:1.0.0")
    kapt("uesugi:erii-spi-annotation:1.0.0")
}
