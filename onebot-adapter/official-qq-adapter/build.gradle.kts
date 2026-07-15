plugins {
    id("uesugi.erii-plugin")
}

version = "1.0.0"

dependencies {
    implementation("uesugi.adapter:official-qq-onebot:1.0.0") {
        exclude("com.typesafe", "config")
    }
}