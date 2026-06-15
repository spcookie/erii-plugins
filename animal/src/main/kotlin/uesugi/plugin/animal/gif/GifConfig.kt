package uesugi.plugin.animal.gif

data class GifConfig(
    val fps: Int = 30,
    val gifDurationSeconds: Int = 10,
    val animationSampleSeconds: Int = 60,
    val viewportWidth: Int = 600,
    val viewportHeight: Int = 300,
    val maxColors: Int = 128,
    val outputPath: String = "animal-farm.gif",
    val keepTempFrames: Boolean = false,
)
