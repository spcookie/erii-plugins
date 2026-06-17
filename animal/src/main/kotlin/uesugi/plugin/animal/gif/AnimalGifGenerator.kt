package uesugi.plugin.animal.gif

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ScreenshotType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AnimalGifGenerator(private val config: GifConfig = GifConfig()) {

    private val log = KotlinLogging.logger {}

    suspend fun generate(groupId: String, userId: Long): ByteArray {
        log.info { "Generating GIF for group=$groupId user=$userId" }
        val url = "${config.serverBaseUrl}/card/gif/$groupId/$userId"
        val frames = PlaywrightBrowserPool.useContext(config) { _, page ->
            captureFrames(page, url)
        }
        return encodeGif(frames)
    }

    private fun captureFrames(page: Page, url: String): List<ByteArray> {
        page.navigate(url)
        page.waitForLoadState(LoadState.NETWORKIDLE)

        page.evaluate("() => { document.getAnimations().forEach(a => a.pause()); }")

        val totalFrames = config.fps * config.gifDurationSeconds
        val sampleDurationMs = config.animationSampleSeconds * 1000
        val stepMs = sampleDurationMs / totalFrames
        val frames = mutableListOf<ByteArray>()

        for (i in 0 until totalFrames) {
            val timeMs = i * stepMs
            page.evaluate(
                "time => { document.getAnimations().forEach(a => a.currentTime = time); }",
                timeMs
            )
            val screenshotOptions = Page.ScreenshotOptions()
                .setType(config.screenshotType)
                .setFullPage(false)
            if (config.screenshotType == ScreenshotType.JPEG) {
                screenshotOptions.setQuality(config.screenshotQuality)
            }
            frames.add(page.screenshot(screenshotOptions))
            if (i % 30 == 0) {
                log.info { "Rendered frame $i / $totalFrames" }
            }
        }

        return frames
    }

    private suspend fun encodeGif(frames: List<ByteArray>): ByteArray = coroutineScope {
        val filter = "fps=${config.fps},scale=${config.viewportWidth}:-1:flags=lanczos," +
                "split[s0][s1];[s0]palettegen=max_colors=${config.maxColors}[p];" +
                "[s1][p]paletteuse=dither=bayer"

        val command = listOf(
            "docker", "run", "--rm", "-i",
            config.ffmpegDockerImage
        ) + listOf(
            "-y",
            "-f", "image2pipe",
            "-framerate", config.fps.toString(),
            "-i", "-",
            "-vf", filter,
            "-f", "gif",
            "pipe:1"
        )

        log.info { "Running: ${command.joinToString(" ")}" }

        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        // Read stdout and stderr concurrently to prevent pipe buffer deadlock.
        val stdoutDeferred = async(Dispatchers.IO) {
            process.inputStream.use { it.readBytes() }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            process.errorStream.use { it.readBytes() }
        }

        withContext(Dispatchers.IO) {
            process.outputStream.use { stdin ->
                frames.forEach { frame ->
                    stdin.write(frame)
                }
            }
        }

        val outputBytes = stdoutDeferred.await()
        val stderrBytes = stderrDeferred.await()

        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("FFmpeg process timed out")
        }

        if (process.exitValue() != 0) {
            val stderr = stderrBytes.toString(Charsets.UTF_8)
            log.error { "FFmpeg stderr: $stderr" }
            throw RuntimeException("FFmpeg failed with exit code ${process.exitValue()}")
        }

        outputBytes
    }
}
