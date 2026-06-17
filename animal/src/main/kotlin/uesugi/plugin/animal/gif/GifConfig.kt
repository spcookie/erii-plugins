package uesugi.plugin.animal.gif

import com.microsoft.playwright.options.ScreenshotType

data class GifConfig(
    val fps: Int = 15,
    val gifDurationSeconds: Int = 10,
    val animationSampleSeconds: Int = 15,
    val viewportWidth: Int = 800,
    val viewportHeight: Int = 400,
    val maxColors: Int = 128,
    val browserExecutablePath: String? = null,
    val screenshotType: ScreenshotType = ScreenshotType.JPEG,
    val screenshotQuality: Int = 90,
    val ffmpegDockerImage: String = "jrottenberg/ffmpeg:7-alpine",
    val serverBaseUrl: String = "",
)
