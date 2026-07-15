pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "erii-plugins"

include("create-image:create-image-seeddream")
include("tts:tts-minimax")
include("onebot-adapter:official-qq-adapter")