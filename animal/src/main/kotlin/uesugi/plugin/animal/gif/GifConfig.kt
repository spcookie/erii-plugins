package uesugi.plugin.animal.gif

import com.microsoft.playwright.options.ScreenshotType

enum class GifEncoder {
    FFMPEG,
    JVM,
}

data class GifConfig(
    val fps: Int = 15,
    val gifDurationSeconds: Int = 10,
    val animationSampleSeconds: Int = 60,
    val viewportWidth: Int = 600,
    val viewportHeight: Int = 300,
    val maxColors: Int = 128,
    val outputPath: String = "animal-farm.gif",
    val keepTempFrames: Boolean = false,
    val browserExecutablePath: String? = null,
    val screenshotType: ScreenshotType = ScreenshotType.JPEG,
    val screenshotQuality: Int = 90,
    val encoder: GifEncoder = GifEncoder.FFMPEG,
)
